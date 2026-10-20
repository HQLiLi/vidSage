package com.vidsage.shared.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 产物落盘的公共助手：读文件大小、原子写盘、原子改名、静默清理，外加 JSON 检查点的读写。
 *
 * <p>全静态、无状态，构造器私有，不要 new 它。
 *
 * <p><b>本类不只是字节助手</b>：JSON 检查点的读写（{@link #writeJsonAtomically} /
 * {@link #readJsonOrNull}）也归这里，故认识 Jackson——全项目「需要落盘的地方」都归口本类
 * （媒体 / 音频走 {@link #moveAtomically}、字幕走 {@link #writeAtomically}）。
 *
 * <p><b>两套字节原子落盘是有意保留的，别合并</b>：
 * <ul>
 *   <li>{@link #writeAtomically} 用 {@code .tmp} + {@code REPLACE_EXISTING}——字节由我们自己写；</li>
 *   <li>{@link #moveAtomically} 用 {@code .part} + {@code ATOMIC_MOVE}——ffmpeg 自己写完了，我们只做改名。</li>
 * </ul>
 * 两者机制不同，合成一个会丢掉 {@code ATOMIC_MOVE} 的语义。
 */
@Slf4j
public final class ArtifactFileUtils {

    private ArtifactFileUtils() {
    }

    /**
     * 文件大小；不存在、不是普通文件或读取失败时返回 0。
     *
     * <p>先判 {@code isRegularFile} 再读大小：目录在部分文件系统上也有「大小」（ext4 上是 4096），
     * 不挡的话目录会冒充成合法产物。
     */
    public static long sizeOrZero(Path path) {
        try {
            return Files.isRegularFile(path) ? Math.max(Files.size(path), 0L) : 0L;
        } catch (IOException e) {
            // 读不到就当没有产物。这是断点判定的正常路径（文件还没生成），不记日志
            return 0L;
        }
    }

    /**
     * 文件存在且大小 &gt; 0（0 字节视为不存在，用于产物断点判定）。
     */
    public static boolean isNonEmpty(Path path) {
        return sizeOrZero(path) > 0;
    }

    /**
     * 路径是不是一个普通文件。
     *
     * <p>只看「在不在、是不是文件」，<b>不看大小</b>：空转写产出的 0 字节 SRT 是合法产物，
     * 「0 字节算不算存在」由调用方按用途决定，这里不替它判。
     *
     * @param path 路径
     * @return 是普通文件时为 {@code true}
     */
    public static boolean isRegularFile(Path path) {
        return Files.isRegularFile(path);
    }

    /**
     * 算文件的 sha256，返回 64 位小写十六进制。
     *
     * @param path 文件路径
     * @return 内容摘要
     * @throws IllegalStateException 文件读不到（不存在、不是文件或无权限），或运行环境不带 SHA-256
     */
    public static String sha256(Path path) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // 标准 JVM 必带 SHA-256；走到这里说明运行环境被裁剪过
            throw new IllegalStateException("当前 JVM 不支持 SHA-256，无法计算产物哈希：请使用标准 JDK 运行本服务", e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            // 只带文件名与异常类名，不带消息：IO 异常的消息就是完整路径，而目录名内嵌来源原文
            throw new IllegalStateException("产物文件读取失败：" + path.getFileName() + "（"
                    + e.getClass().getSimpleName() + "）；请检查文件是否还在工作区、是否有读权限", e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 原子写盘：先写 {@code <name>.tmp} 再替换到目标名，避免断电留下半截文件冒充成品。
     */
    public static void writeAtomically(byte[] bytes, Path target) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("产物写入失败：" + target
                    + "；请检查磁盘空间与目录写权限。原因：" + e.getMessage(), e);
        }
    }

    /**
     * 原子改名落盘；失败时清掉临时文件并抛异常，绝不让半截文件冒充成最终产物。
     */
    public static void moveAtomically(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(from);
            throw new IllegalStateException("音频产物落盘失败：" + to + "；" + e.getMessage(), e);
        }
    }

    /**
     * 删除文件（不存在即忽略）。清理是尽力而为：失败只记 DEBUG，不掩盖原本要抛的异常。
     */
    public static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("清理临时文件失败（忽略）: file={}, reason={}", path.getFileName(), e.getMessage());
        }
    }

    /**
     * 把对象序列化成 JSON 并原子写盘。
     *
     * <p>用带缩进的格式（{@code writerWithDefaultPrettyPrinter}）而不是紧凑 JSON：这些文件是人要
     * 打开看的检查点，缩进过一眼就懂。序列化失败抛 {@link IllegalStateException}——写不出去等于
     * 产物没落地，不能静默。
     *
     * @param value  要序列化的对象
     * @param mapper Jackson 序列化器
     * @param target 目标文件
     * @throws IllegalStateException 序列化或写盘失败
     */
    public static void writeJsonAtomically(Object value, ObjectMapper mapper, Path target) {
        try {
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
            writeAtomically(json, target);
        } catch (JacksonException e) {
            throw new IllegalStateException("JSON 产物写入失败：" + target
                    + "；请检查对象能否序列化、磁盘空间与目录写权限。原因：" + e.getMessage(), e);
        }
    }

    /**
     * 读一份 JSON 检查点；读不出来就返回 {@code null}，不抛异常。
     *
     * <p>日志里的角色名固定为 {@code "JSON"}；要区分坏的是哪一环，用
     * {@link #readJsonOrNull(Path, ObjectMapper, Class, String)}。
     *
     * @param path   文件路径
     * @param mapper Jackson 反序列化器
     * @param type   目标类型
     * @param <T>    目标类型
     * @return 反序列化结果；文件缺失 / 为空 / 解析失败时为 {@code null}
     */
    public static <T> T readJsonOrNull(Path path, ObjectMapper mapper, Class<T> type) {
        return readJsonOrNull(path, mapper, type, "JSON");
    }

    /**
     * 读一份 JSON 检查点；读不出来就返回 {@code null}，不抛异常。
     *
     * <p><b>语义边界：只服务「断点形状的读」</b>——文件不存在、为空、解析失败，一律当「没有检查点」
     * 返回 {@code null}，让调用方照未完成重跑。
     *
     * <p><b>不要拿它读「必须能解析」的文件</b>：坏了就该报错的那类场景（如清洗前的原始字幕），
     * 调用方要留着自己的 try-catch 抛 {@link IllegalStateException}——那是另一种语义，不该被本方法统一掉。
     *
     * @param path   文件路径
     * @param mapper Jackson 反序列化器
     * @param type   目标类型
     * @param role   这份检查点的角色名，只进日志（如 {@code "segments"} / {@code "clean"}）。文件名本身
     *               已能定位，这个标签只为让日志一眼看出坏的是哪一环
     * @param <T>    目标类型
     * @return 反序列化结果；文件缺失 / 为空 / 解析失败时为 {@code null}
     */
    public static <T> T readJsonOrNull(Path path, ObjectMapper mapper, Class<T> type, String role) {
        if (!isNonEmpty(path)) {
            return null;
        }
        try {
            return mapper.readValue(path.toFile(), type);
        } catch (JacksonException e) {
            log.warn("{} 检查点解析失败，按未完成重跑: file={}, reason={}", role, path.getFileName(), e.getMessage());
            return null;
        }
    }
}
