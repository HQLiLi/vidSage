package com.vidsage.shared.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 产物落盘的公共助手：读文件大小、原子写盘、原子改名、静默清理，外加 JSON 检查点的读写。
 *
 * <p>全静态、无状态，构造器私有，不要 new 它。
 *
 * <p><b>它不再是「零依赖字节助手」</b>：为 JSON 断点读写（{@link #writeJsonAtomically} /
 * {@link #readJsonOrNull}）它现在认识 Jackson。这是有意的——全项目「需要落盘的地方」都归口在这里
 * （媒体 / 音频走 {@link #moveAtomically}、字幕走 {@link #writeAtomically}），只差 JSON 这层；
 * 不归口的话「序列化成 JSON 再原子写」那三行会在每个 service 里各抄一遍。
 * 规范允许这个依赖：{@code directory-structure.md} 把 Jackson 与 JDK、slf4j 并列在 {@code shared/}（原 utils/）的允许清单里
 * ——不过加这里的条件仍是「把 Spring 容器整个拿掉还能编译」，Jackson 满足。
 *
 * <p><b>两套字节原子落盘是有意保留的，别合并</b>：
 * <ul>
 *   <li>{@link #writeAtomically} 用 {@code .tmp} + {@code REPLACE_EXISTING}——字节由我们自己写；</li>
 *   <li>{@link #moveAtomically} 用 {@code .part} + {@code ATOMIC_MOVE}——ffmpeg 自己写完了，我们只做改名。</li>
 * </ul>
 * 两者机制不同，合成一个会丢掉 {@code ATOMIC_MOVE} 的语义。
 */
@Slf4j
public final class ArtifactFiles {


    private ArtifactFiles() {
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
     * <p><b>语义边界：只服务「断点形状的读」</b>——文件不存在、为空、解析失败，一律当「没有检查点」
     * 返回 {@code null}，让调用方照未完成重跑。这与 {@code SubtitleService} / {@code SubtitleCleanupService}
     * 现有断点判据同一条语义。
     *
     * <p><b>不要拿它读「必须能解析」的文件</b>：像 {@code SubtitleCleanupService.readRaw} 那样
     * 原始字幕坏了就该报错的场景，得留着自己的 try-catch 抛 {@link IllegalStateException}——
     * 那是另一种语义，不该被这个方法统一掉。
     *
     * @param path   文件路径
     * @param mapper Jackson 反序列化器
     * @param type   目标类型
     * @param <T>    目标类型
     * @return 反序列化结果；文件缺失 / 为空 / 解析失败时为 {@code null}
     */
    public static <T> T readJsonOrNull(Path path, ObjectMapper mapper, Class<T> type) {
        if (!isNonEmpty(path)) {
            return null;
        }
        try {
            return mapper.readValue(path.toFile(), type);
        } catch (JacksonException e) {
            log.warn("JSON 检查点解析失败，按未完成处理: file={}, reason={}", path.getFileName(), e.getMessage());
            return null;
        }
    }
}
