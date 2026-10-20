package com.vidsage.shared.fs;

import java.util.Set;

/**
 * 产出过程中的过渡态文件后缀：临时写盘、原子替换的中途、下载器的进行中标记。
 *
 * <p>两个消费者分处 infrastructure（yt-dlp 的兜底扫描）与 application（对账 ③ 扫孤儿），
 * 故放 shared 让两边都能用，不产生反向依赖。
 *
 * <p>刻意<b>不含</b> {@code .info.json}：它现在是 {@code intermediate_file} 表里的一类登记
 * （{@code INFO_JSON}），留着会让对账把真孤儿漏报——一个残留的 {@code source.info.json}
 * 会因为是「非产物后缀」被静默跳过，哪怕表里已经把它标成 {@code DELETED}。
 */
public final class TransientFiles {

    /**
     * 过渡态后缀：{@code .tmp} 是原子写盘的中间名，{@code .part} / {@code .ytdl} 是下载器的进行中标记。
     */
    private static final Set<String> TRANSIENT_SUFFIXES = Set.of(".tmp", ".part", ".ytdl");

    private TransientFiles() {
    }

    /**
     * 文件名后缀是否属于过渡态（扫盘时该忽略）。
     *
     * @param fileName 工作区内文件名；{@code null} 视为不是过渡态
     * @return 后缀命中白名单时为 {@code true}
     */
    public static boolean isTransient(String fileName) {
        if (fileName == null) {
            return false;
        }
        return TRANSIENT_SUFFIXES.stream().anyMatch(fileName::endsWith);
    }
}
