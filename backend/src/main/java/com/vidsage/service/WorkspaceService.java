package com.vidsage.service;

import com.vidsage.config.WorkspaceProperties;
import com.vidsage.model.media.MediaInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 存放产物的目录：按<b>输入内容寻址</b>给每条 {@link MediaInput} 算出唯一的本地目录。
 *
 * <p>目录名 = {@code <安全化名字>-<sha256(规范化输入) 前 12 位>}，其中：
 * <ul>
 *   <li><b>规范化串</b>：{@link MediaInput.RemoteUrl} 取 URL 本身；{@link MediaInput.LocalPath}
 *       取「绝对路径 + 文件大小 + 最后修改时间」。后者的意图是内容寻址——同一份文件即使被重新
 *       拷贝成另一路径，只要大小与时间戳不变就落回同一目录；</li>
 *   <li><b>安全化名字</b>只是给人看的标签，唯一性完全由哈希保证。命中 Windows 保留名
 *       （{@code CON} / {@code NUL} / {@code COM1}…，含带扩展名形式）或结果为空时回退成
 *       {@code media}。</li>
 * </ul>
 *
 * <p>目录<b>懒创建</b>：{@link #workspaceFor} 被调用时才 {@link Files#createDirectories}，
 * 构造函数不碰文件系统（否则上下文加载就会写盘）。
 */
@Slf4j
@Service
public class WorkspaceService {

    /** 安全化名字的最大长度：避免超长输入撑爆路径上限。 */
    static final int MAX_NAME_LENGTH = 80;

    /** 结果为空或命中保留名时的兜底名。 */
    static final String FALLBACK_NAME = "media";

    /** Windows 保留设备名（不区分大小写）；保留判定只看第一个点之前的基名。
     * 目录前半段来自用户输入（URL或文件名），在 Windows 上拿这些名字当路径，
     * 行为不可靠——可能创建失败，也可能落到设备语义上去。
     */
    static final Set<String> RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /** 文件名里非法或需要规避的字符（含 Windows 禁止的 {@code < > : " / \ | ? *}）。 */
    private static final String ILLEGAL_CHARS = "<>:\"/\\|?*";

    /** 规范化串中「文件不可读」时的大小 / 时间戳占位值。 */
    private static final long UNAVAILABLE = -1L;

    private final WorkspaceProperties properties;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param properties 工作目录根配置
     */
    public WorkspaceService(WorkspaceProperties properties) {
        this.properties = properties;
    }

    /**
     * 取（必要时创建）该输入对应的任务工作目录。
     *
     * @param input 媒体输入；不可为空
     * @return 工作目录的绝对路径，已确保存在
     * @throws IllegalArgumentException 输入为空
     * @throws IllegalStateException    目录创建失败（根路径不可写等）
     */
    public Path workspaceFor(MediaInput input) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }
        String normalized = normalize(input);
        String dirName = sanitizeName(displayLabel(input)) + "-" + shortHash(normalized);
        Path dir = Path.of(properties.root() == null ? FALLBACK_NAME : properties.root())
                .resolve(dirName)
                .toAbsolutePath();
        try {
            // 如果已存在则静默返回
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建任务工作目录：" + dir
                    + "；请检查 vidsage.workspace.root 是否存在且可写。原因：" + e.getMessage(), e);
        }
        log.debug("任务工作目录: dir={}", dir.getFileName());
        return dir;
    }

    /**
     * 输入的规范化串：内容寻址的哈希输入。
     *
     * <p>包级 static：测试可直接断言「同一输入同串、不同输入不同串」，不必落盘。
     *
     * @param input 媒体输入（不可为 {@code null}）
     * @return 规范化后的字符串
     */
    static String normalize(MediaInput input) {
        return switch (input) {
            case MediaInput.RemoteUrl url -> url.value() == null ? "" : url.value();
            case MediaInput.LocalPath local -> localPathKey(local.value());
        };
    }

    /**
     * 本地文件的规范化串：绝对路径 + 文件大小 + 最后修改时间（毫秒）。
     *
     * <p>文件不存在或不可读时大小与时间戳取 {@link #UNAVAILABLE} 占位——<b>此处不报错</b>：
     * 「文件不存在 / 不可读」的权威报错属于输入获取适配器，服务层重复报一次只会让错误来源含糊。
     *
     * @param path 本地文件路径；为 {@code null} 时返回占位串
     * @return 规范化后的字符串
     */
    static String localPathKey(Path path) {
        if (path == null) {
            return "<null>|" + UNAVAILABLE + "|" + UNAVAILABLE;
        }
        Path absolute = path.toAbsolutePath().normalize();
        long size = UNAVAILABLE;
        long lastModifiedMs = UNAVAILABLE;
        try {
            if (Files.isRegularFile(absolute)) {
                size = Files.size(absolute);
                lastModifiedMs = Files.getLastModifiedTime(absolute).toMillis();
            }
        } catch (IOException e) {
            log.debug("读取本地文件属性失败，按占位值计算工作目录: file={}, reason={}",
                    absolute.getFileName(), e.getMessage());
        }
        return absolute + "|" + size + "|" + lastModifiedMs;
    }

    /**
     * 供人阅读的标签：远程取 URL，本地取文件名。
     *
     * <p>包级 static：可直测。
     */
    static String displayLabel(MediaInput input) {
        return switch (input) {
            case MediaInput.RemoteUrl url -> url.value() == null ? "" : url.value();
            case MediaInput.LocalPath local -> {
                Path value = local.value();
                Path fileName = value == null ? null : value.getFileName();
                yield fileName == null ? "" : fileName.toString();
            }
        };
    }

    /**
     * 安全化文件名：替换非法字符与控制字符、去掉结尾的点与空格、截断长度，
     * 结果为空或命中 Windows 保留名时回退成 {@link #FALLBACK_NAME}。
     *
     * <p>包级 visible static：可直测。保留名判定只看第一个点之前的基名，因此
     * {@code CON.txt} 也命中保留名。
     *
     * @param raw 原始候选名；可为 {@code null} 或空白
     * @return 安全化的名字，非空且不含非法字符
     */
    static String sanitizeName(String raw) {
        if (raw == null) {
            return FALLBACK_NAME;
        }
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7F || ILLEGAL_CHARS.indexOf(c) >= 0) {
                builder.append('_');
            } else {
                builder.append(c);
            }
        }
        String name = stripTrailingDotsAndSpaces(builder.toString());
        if (name.length() > MAX_NAME_LENGTH) {
            name = stripTrailingDotsAndSpaces(name.substring(0, MAX_NAME_LENGTH));
        }
        if (name.isEmpty() || isReservedName(name)) {
            return FALLBACK_NAME;
        }
        return name;
    }

    /**
     * 是否命中 Windows 保留设备名（不区分大小写，只看第一个点之前的基名）。
     */
    static boolean isReservedName(String name) {
        int dot = name.indexOf('.');
        String base = (dot < 0 ? name : name.substring(0, dot)).trim().toUpperCase(Locale.ROOT);
        return RESERVED_NAMES.contains(base);
    }

    /**
     * 去掉结尾的点与空格（Windows 不允许文件名以它们结尾）。可能因去掉而再次暴露，故循环处理。
     */
    private static String stripTrailingDotsAndSpaces(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '.' || value.charAt(end - 1) == ' ')) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * 取 sha256 的前 12 位十六进制（6 字节）。12 位已足够避免单机任务目录碰撞。
     *
     * <p>包级 static：可直测（同一串稳定、不同串不同）。
     *
     * @param value 规范化输入串
     * @return 12 位十六进制摘要
     */
    static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            // 标准 JVM 必带 SHA-256，走到这里说明运行环境被裁剪过
            throw new IllegalStateException("当前 JVM 不支持 SHA-256，无法计算任务工作目录："
                    + "请使用标准 JDK 运行本服务", e);
        }
    }
}
