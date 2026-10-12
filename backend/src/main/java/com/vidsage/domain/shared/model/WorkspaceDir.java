package com.vidsage.domain.shared.model;

import java.util.Locale;
import java.util.Set;

/**
 * 任务工作区目录名值对象：{@code <安全化名字>-<工作区短哈希>}
 *
 * <p><b>安全化名字</b>只是给人看的标签；区分不同工作区靠尾部的 key 后缀（哈希碰撞无法完全排除）。
 * 非法字符与控制字符替换为下划线、去掉结尾的点与空格、截断长度；结果为空或命中 Windows 保留名
 * （{@code CON} / {@code NUL} / {@code COM1}…，含带扩展名形式）时回退成 {@link #FALLBACK_NAME}。
 *
 * @param value 目录名（不含根路径，不含任何建目录副作用）
 */
public record WorkspaceDir(String value) {

    /**
     * 安全化名字的最大长度：避免超长输入撑爆路径上限。
     */
    public static final int MAX_NAME_LENGTH = 80;

    /**
     * 结果为空或命中保留名时的兜底名；工作区根未配置时同样回退此名。
     */
    public static final String FALLBACK_NAME = "media";

    /**
     * Windows 保留设备名（不区分大小写）；保留判定只看第一个点之前的基名。
     * 目录前半段来自用户输入（URL 或文件名），在 Windows 上拿这些名字当路径，
     * 行为不可靠——可能创建失败，也可能落到设备语义上去。
     */
    static final Set<String> RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /**
     * 文件名里非法或需要规避的字符（含 Windows 禁止的 {@code < > : " / \ | ? *}）。
     */
    private static final String ILLEGAL_CHARS = "<>:\"/\\|?*";

    /**
     * 目录名公式：{@code sanitize(标签)-key}。建目录与落库 {@code workspace_dir} 共用这一处——
     * 两条路线都按本公式取名，落库的值必须与磁盘上的目录名逐字一致。
     *
     * @param label 供人阅读的标签（可为 {@code null} 或空白，sanitize 后回退 {@link #FALLBACK_NAME}）
     * @param key   内容寻址 key（取值由调用方保证）
     * @return 目录名值对象
     */
    public static WorkspaceDir of(String label, WorkspaceKey key) {
        return new WorkspaceDir(sanitizeName(label) + "-" + key.value());
    }

    /**
     * 安全化文件名：替换非法字符与控制字符、去掉结尾的点与空格、截断长度，
     * 结果为空或命中 Windows 保留名时回退成 {@link #FALLBACK_NAME}。
     *
     * <p>保留名判定只看第一个点之前的基名，因此 {@code CON.txt} 也命中保留名。
     *
     * @param raw 原始候选名；可为 {@code null} 或空白
     * @return 安全化的名字，非空且不含非法字符
     */
    public static String sanitizeName(String raw) {
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
}
