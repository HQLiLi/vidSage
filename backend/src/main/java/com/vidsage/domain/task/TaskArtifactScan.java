package com.vidsage.domain.task;

/**
 * 任务产物可见性政策（纯函数领域政策，不做文件 I/O）：工作区里哪些文件<b>对外可见</b>、该进产物
 * 清单——各阶段字幕 {@code *.srt} 与转写检查点 {@code segments-*.json}。
 *
 * <p>{@code terms-} / {@code context-} 等 LLM 中间产物刻意不进清单——它们是内部缓存，不面向使用者。
 * 本判据是<b>白名单</b>而非黑名单：只有命中上述两种形状的文件可见，其余一律不进；将来新增面向
 * 使用者的产物形状时在此放行，而不是在调用方加特例。
 *
 * <p>原 {@code TaskService.scanArtifacts} 内联的过滤谓词，2026-10-06 业务逻辑下沉单源于此；
 * 列举目录、量字节数是应用层的事——调用方以 {@code Files.list} 等技术原语取事实，再逐名交给
 * 本类裁决。
 */
public final class TaskArtifactScan {

    private TaskArtifactScan() {
    }

    /**
     * 文件名是否为对外可见的任务产物。
     *
     * @param fileName 工作区内的文件名（不含目录）；调用方自 {@code Files.list} 取得
     * @return {@code *.srt}（各阶段字幕）或 {@code segments-*.json}（转写检查点）时为 {@code true}
     */
    public static boolean isVisible(String fileName) {
        return fileName.endsWith(".srt")
                || (fileName.startsWith("segments-") && fileName.endsWith(".json"));
    }
}
