package com.vidsage.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vidsage.application.task.SubtitleMainService;
import com.vidsage.application.task.SubtitleRecleanService;
import com.vidsage.application.task.SubtitleTaskQueryService;
import com.vidsage.application.task.SubtitleTranslateService;
import com.vidsage.application.task.TaskRunRef;
import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.application.task.message.TaskRunProgress;
import com.vidsage.application.task.query.KeywordsSnapshot;
import com.vidsage.application.task.query.RunSummary;
import com.vidsage.application.task.query.TaskSnapshot;
import com.vidsage.domain.task.TaskRun;
import com.vidsage.domain.task.model.TaskRunStatus;
import com.vidsage.domain.task.model.TaskRunType;
import com.vidsage.domain.task.model.RunRef;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.workspace.model.SourceType;
import com.vidsage.web.sse.SseTaskProgressBroadcaster;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@link TaskController} 的切片测试：只装配 web 层（含全局 {@link ApiExceptionHandler}），
 * 服务层用 {@link MockitoBean} 替换，不触发真实落库与管线；SSE 广播器用<b>真实实例</b>
 * （{@code @Import}）——连接建立 / 快照首推走的就是生产接线。
 *
 * <p>覆盖「202 提交（两条路线）与同形状 409」「边界卫语句 400」「查询身份 + lastRun 与 404」
 * 「SSE 三种结果：404 / RUNNING 开流 / 已终态 204」「按需翻译与重新生成」「关键词查询」。</p>
 */
@WebMvcTest(TaskController.class)
@Import(SseTaskProgressBroadcaster.class)
class TaskControllerTest {

    private static final String TASK_NO = "3f2a9c1e7b5d4a6089abcdef01234567";

    private static final String SOURCE_LANG = "zh-Hans";

    private static final String TARGET_LANG = "zh-Hant";

    /** 翻译触发端点的请求体：两个语种都必填（源语种也过白名单）。 */
    private static final String TRANSLATE_BODY = """
            {"sourceLanguage":"zh-Hans","targetLanguage":"zh-Hant"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubtitleMainService mainService;

    @MockitoBean
    private SubtitleRecleanService recleanService;

    @MockitoBean
    private SubtitleTranslateService translateService;

    @MockitoBean
    private SubtitleTaskQueryService queryService;

    // ---------- 提交：URL 路线 ----------

    @Test
    void submitUrlReturnsAcceptedWithTaskRunRef() throws Exception {
        when(mainService.submitUrl("https://example.com/watch/1", null)).thenReturn(new TaskRunRef(TASK_NO, 1));

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/watch/1"}"""))
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.taskNo").value(TASK_NO))
                .andExpect(jsonPath("$.runNo").value(1));
    }

    @Test
    void submitUrlRejectsMissingUrlBeforeTouchingService() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("url 不能为空：请传公网媒体页面地址（如 B 站 / YouTube 链接），"
                        + "上传本地文件请走 POST /api/tasks/upload"));

        verify(mainService, never()).submitUrl(anyString(), isNull());
    }

    @Test
    void submitUrlConflictReturns409WithSameShapeAsAccepted() throws Exception {
        // 409 与 202 同形状 {taskNo, runNo}，且不含文案——调用方靠状态码判冲突
        when(mainService.submitUrl(anyString(), isNull())).thenThrow(new TaskConflictException(TASK_NO, 2));

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/watch/1"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.taskNo").value(TASK_NO))
                .andExpect(jsonPath("$.runNo").value(2))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    // ---------- 提交：上传路线 ----------

    @Test
    void submitUploadReturnsAcceptedWithTaskRunRef() throws Exception {
        when(mainService.submitUpload(any(), any(), isNull())).thenReturn(new TaskRunRef(TASK_NO, 1));
        MockMultipartFile file = new MockMultipartFile("file", "视频.mp4", "video/mp4",
                "fake-bytes".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/tasks/upload").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskNo").value(TASK_NO))
                .andExpect(jsonPath("$.runNo").value(1));
    }

    @Test
    void submitUploadRejectsEmptyFileBeforeOpeningStream() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.mp4", "video/mp4", new byte[0]);

        mockMvc.perform(multipart("/api/tasks/upload").file(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("上传文件不能为空：请选择本地视频 / 音频文件后再提交"));

        verify(mainService, never()).submitUpload(any(), any(), isNull());
    }

    // ---------- 查询 ----------

    @Test
    void getTaskReturnsIdentityAndLastRunWithoutFlattenedFields() throws Exception {
        when(queryService.findByTaskNo(TASK_NO)).thenReturn(taskView(new RunSummary(3, TaskRunType.TRANSLATE,
                "zh-Hant", TaskRunStatus.RUNNING, "translate-llm", null,
                LocalDateTime.of(2026, 10, 17, 10, 5), null)));

        mockMvc.perform(get("/api/tasks/{taskNo}", TASK_NO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskNo").value(TASK_NO))
                .andExpect(jsonPath("$.inputType").value("URL"))
                .andExpect(jsonPath("$.sourceUri").value("https://example.com/watch/1"))
                .andExpect(jsonPath("$.lastRun.runNo").value(3))
                .andExpect(jsonPath("$.lastRun.runType").value("TRANSLATE"))
                .andExpect(jsonPath("$.lastRun.targetLanguage").value("zh-Hant"))
                .andExpect(jsonPath("$.lastRun.status").value("RUNNING"))
                .andExpect(jsonPath("$.lastRun.currentStage").value("translate-llm"))
                // 扁平五字段与产物清单都随新契约删掉
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.currentStage").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist())
                .andExpect(jsonPath("$.startedAt").doesNotExist())
                .andExpect(jsonPath("$.finishedAt").doesNotExist())
                .andExpect(jsonPath("$.artifacts").doesNotExist());
    }

    @Test
    void getTaskReturnsNullLastRunWhenNeverExecuted() throws Exception {
        when(queryService.findByTaskNo(TASK_NO)).thenReturn(taskView(null));

        mockMvc.perform(get("/api/tasks/{taskNo}", TASK_NO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskNo").value(TASK_NO))
                .andExpect(jsonPath("$.lastRun").value(nullValue()));
    }

    @Test
    void getTaskMapsMissingTaskToNotFound() throws Exception {
        when(queryService.findByTaskNo("nope")).thenThrow(new TaskNotFoundException("nope"));

        mockMvc.perform(get("/api/tasks/{taskNo}", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("任务不存在：nope")));
    }

    // ---------- SSE：三种结果 ----------

    @Test
    void subscribeRunEventsPushesDbSnapshotFirstWhileRunning() throws Exception {
        when(queryService.streamSnapshot(TASK_NO, 1)).thenReturn(Optional.of(runningProgress(1)));
        when(queryService.renderSnapshot(TASK_NO, 1)).thenReturn(runningProgress(1));

        MvcResult result = mockMvc.perform(get("/api/tasks/{taskNo}/runs/{runNo}/events", TASK_NO, 1))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("event:progress"), () -> "应推一条进度事件：" + body);
        assertTrue(body.contains("\"runType\":\"MAIN\""), () -> "runType 要与路径上的 run 一致：" + body);
        assertTrue(body.contains("\"status\":\"RUNNING\""), () -> body);
        assertTrue(body.contains("\"terminal\":false"), () -> "前端靠 terminal 判是否收口：" + body);
    }

    @Test
    void subscribeRunEventsToTerminalRunReturnsNoContent() throws Exception {
        // 已终态：不推终态快照，直接 204 —— 告诉浏览器别再重连（去看 GET /{taskNo} 的 lastRun）
        when(queryService.streamSnapshot(TASK_NO, 1)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/tasks/{taskNo}/runs/{runNo}/events", TASK_NO, 1))
                .andExpect(status().isNoContent());
    }

    @Test
    void subscribeRunEventsMapsUnknownRunToNotFound() throws Exception {
        when(queryService.streamSnapshot("ghost", 9)).thenThrow(new TaskNotFoundException("ghost"));

        mockMvc.perform(get("/api/tasks/{taskNo}/runs/{runNo}/events", "ghost", 9))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("任务不存在：ghost")));

        verify(queryService).streamSnapshot("ghost", 9);
    }

    // ---------- 按需触发翻译 ----------

    @Test
    void translateReturnsAcceptedWithTaskRunRef() throws Exception {
        when(translateService.translate(TASK_NO, SOURCE_LANG, TARGET_LANG)).thenReturn(new TaskRunRef(TASK_NO, 2));

        mockMvc.perform(post("/api/tasks/{taskNo}/translate", TASK_NO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRANSLATE_BODY))
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.taskNo").value(TASK_NO))
                .andExpect(jsonPath("$.runNo").value(2));
    }

    @Test
    void translateMapsMissingTaskToNotFound() throws Exception {
        when(translateService.translate("ghost", SOURCE_LANG, TARGET_LANG)).thenThrow(new TaskNotFoundException("ghost"));

        mockMvc.perform(post("/api/tasks/{taskNo}/translate", "ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRANSLATE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("任务不存在：ghost")));
    }

    @Test
    void translateMapsNotReadyTaskToBadRequest() throws Exception {
        // 字幕尚未就绪：错在调用方触发太早 → IAE → 400（不是 409）
        when(translateService.translate(TASK_NO, SOURCE_LANG, TARGET_LANG)).thenThrow(new IllegalArgumentException(
                "字幕尚未就绪：工作区里没有 清洗后的字幕（clean-subtitles.srt）；请先等主管线跑完再试"));

        mockMvc.perform(post("/api/tasks/{taskNo}/translate", TASK_NO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRANSLATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("字幕尚未就绪")));
    }

    @Test
    void translateConflictReturns409WithSameShape() throws Exception {
        when(translateService.translate(TASK_NO, SOURCE_LANG, TARGET_LANG))
                .thenThrow(new TaskConflictException(TASK_NO, 2));

        mockMvc.perform(post("/api/tasks/{taskNo}/translate", TASK_NO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRANSLATE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.taskNo").value(TASK_NO))
                .andExpect(jsonPath("$.runNo").value(2))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void translateRejectsBlankTaskNoBeforeTouchingService() throws Exception {
        mockMvc.perform(post("/api/tasks/{taskNo}/translate", " ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRANSLATE_BODY))
                .andExpect(status().isBadRequest());

        verify(translateService, never()).translate(anyString(), eq(SOURCE_LANG), eq(TARGET_LANG));
    }

    // ---------- 重新生成字幕（关键词） ----------

    @Test
    void regenerateReturnsAcceptedAndPassesKeywordsThrough() throws Exception {
        when(recleanService.regenerate(TASK_NO, List.of("影视飓风"))).thenReturn(new TaskRunRef(TASK_NO, 4));

        mockMvc.perform(post("/api/tasks/{taskNo}/subtitles/regenerate", TASK_NO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"keywords":["影视飓风"]}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskNo").value(TASK_NO))
                .andExpect(jsonPath("$.runNo").value(4));

        verify(recleanService).regenerate(TASK_NO, List.of("影视飓风"));
    }

    @Test
    void regenerateMapsMissingTaskToNotFound() throws Exception {
        when(recleanService.regenerate("ghost", null)).thenThrow(new TaskNotFoundException("ghost"));

        mockMvc.perform(post("/api/tasks/{taskNo}/subtitles/regenerate", "ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void regenerateMapsNotReadyTaskToBadRequest() throws Exception {
        when(recleanService.regenerate(TASK_NO, null)).thenThrow(new IllegalArgumentException(
                "字幕尚未就绪：工作区里没有 原始转写（original-segments.json）；请先等主管线跑完再试"));

        mockMvc.perform(post("/api/tasks/{taskNo}/subtitles/regenerate", TASK_NO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("字幕尚未就绪")));
    }

    @Test
    void regenerateRejectsBlankTaskNoBeforeTouchingService() throws Exception {
        mockMvc.perform(post("/api/tasks/{taskNo}/subtitles/regenerate", " ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(recleanService, never()).regenerate(anyString(), any());
    }

    // ---------- 关键词查询 ----------

    @Test
    void keywordsReturnsListAndFingerprint() throws Exception {
        when(queryService.findKeywords(TASK_NO))
                .thenReturn(new KeywordsSnapshot(List.of("影视飓风", "Tim"), "a1b2c3d4e5f60708"));

        mockMvc.perform(get("/api/tasks/{taskNo}/keywords", TASK_NO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keywords[0]").value("影视飓风"))
                .andExpect(jsonPath("$.keywords[1]").value("Tim"))
                .andExpect(jsonPath("$.fingerprint").value("a1b2c3d4e5f60708"));
    }

    @Test
    void keywordsMapsMissingTaskToNotFound() throws Exception {
        when(queryService.findKeywords("ghost")).thenThrow(new TaskNotFoundException("ghost"));

        mockMvc.perform(get("/api/tasks/{taskNo}/keywords", "ghost"))
                .andExpect(status().isNotFound());
    }

    // ---------- 夹具 ----------

    private static TaskSnapshot taskView(RunSummary lastRun) {
        return new TaskSnapshot(TASK_NO, SourceType.URL, "https://example.com/watch/1",
                LocalDateTime.of(2026, 10, 17, 10, 0), lastRun);
    }

    private static TaskRun runningRun(int runNo) {
        return TaskRun.restore(1L, 7L, runNo, TaskRunType.MAIN, null, TaskRunStatus.RUNNING,
                null, null, LocalDateTime.of(2026, 10, 17, 10, 5), null, LocalDateTime.of(2026, 10, 17, 10, 5));
    }

    private static TaskRunProgress runningProgress(int runNo) {
        RunRef ref = new RunRef(new TaskNo(TASK_NO), runNo);
        return TaskRunProgress.of(ref, runningRun(runNo), null);
    }
}
