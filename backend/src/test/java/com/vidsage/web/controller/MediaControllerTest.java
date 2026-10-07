package com.vidsage.web.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vidsage.application.diagnostics.ToolDiagnosticReport;
import com.vidsage.application.diagnostics.port.ToolDiagnostics;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MediaController 的切片测试：只装配 web 层（含全局 {@link ApiExceptionHandler}），
 * 服务层用 {@link MockitoBean} 替换，不触发真实诊断。
 *
 * <p>原「抽音频」端点（{@code POST /api/media/audio}）已随 MVP-1 异步管线收编删除，
 * 本类只剩工具诊断端点的用例。
 */
@WebMvcTest(MediaController.class)
class MediaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ToolDiagnostics toolDiagnostics;

    @Test
    void returnsToolReports() throws Exception {
        when(toolDiagnostics.diagnoseAll()).thenReturn(List.of(
                new ToolDiagnosticReport("yt-dlp", true, "2026.08.19", "F:/tools/yt-dlp.exe", null),
                new ToolDiagnosticReport("ffmpeg", false, null, null,
                        "未找到 ffmpeg：请设置 vidsage.tools.ffmpeg-path 或加入 PATH")));

        // 只断言状态码是不够的：这里断言响应体真能序列化出可读的字段
        mockMvc.perform(get("/api/media/tools"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].toolName").value("yt-dlp"))
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[0].version").value("2026.08.19"))
                .andExpect(jsonPath("$[0].path").value("F:/tools/yt-dlp.exe"))
                .andExpect(jsonPath("$[1].toolName").value("ffmpeg"))
                .andExpect(jsonPath("$[1].available").value(false))
                .andExpect(jsonPath("$[1].hint").value(
                        "未找到 ffmpeg：请设置 vidsage.tools.ffmpeg-path 或加入 PATH"));
    }
}
