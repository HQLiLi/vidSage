package com.vidsage.service;

import com.vidsage.model.process.ExternalToolReport;
import com.vidsage.provider.tool.ExternalToolDiagnostics;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 外部工具诊断的应用服务：HTTP 层唯一可见的诊断入口。
 *
 * <p><b>为什么需要这一层</b>：{@code directory-structure.md} 规定 {@code controller/} 不许直接依赖
 * {@code provider/}，而诊断能力落在 {@code provider/tool/ExternalToolDiagnostics}。本类把这次调用
 * 隔在服务层，维持「Controller → service → provider」这条依赖方向，让 {@code MediaController}
 * 不出现任何 provider 类型（将来诊断实现替换时才不必回头改 HTTP 层）。
 */
@Service
public class ToolDiagnosticsService {

    private final ExternalToolDiagnostics diagnostics;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param diagnostics 工具诊断实现
     */
    public ToolDiagnosticsService(ExternalToolDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    /**
     * 诊断全部外部工具（yt-dlp / ffmpeg / ffprobe）的存在性、路径与版本，yt-dlp 另含新鲜度告警。
     *
     * @return 三份诊断报告，顺序固定
     */
    public List<ExternalToolReport> diagnoseAll() {
        return diagnostics.diagnoseAll();
    }
}
