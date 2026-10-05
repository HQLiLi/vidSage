package com.vidsage.web.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vidsage.domain.shared.StageEvent;
import com.vidsage.application.task.TaskProgressMessage;
import com.vidsage.application.task.query.TaskArtifactFile;
import com.vidsage.domain.task.TaskInputType;
import com.vidsage.domain.task.TaskStatus;
import com.vidsage.application.task.query.TaskView;
import com.vidsage.domain.task.TranslationStatus;
import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.application.task.TaskService;
import com.vidsage.application.task.subscribe.TaskSubscriptionRegistry;
import com.vidsage.web.sse.TaskSseHub;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TaskController} 的切片测试：只装配 web 层（含全局 {@link ApiExceptionHandler}），
 * 服务层用 {@link MockitoBean} 替换，不触发真实落库与管线；SSE 连接管理与订阅注册表用
 * <b>真实实例</b>（{@code @Import}）——快照首推 / 增量广播 / 终态收口走的就是生产接线。
 *
 * <p>覆盖「202 提交（两条路线）」「边界卫语句 400」「重复提交 409 + 既有任务视图」「查询 404」
 * 「SSE 订阅（快照首推 / 增量 / 终态收口 / 窗口竞态重读 / 未知任务）」「产物下载（含穿越 400）」。</p>
 */
@WebMvcTest(TaskController.class)
@Import({TaskSseHub.class, TaskSubscriptionRegistry.class})
class TaskControllerTest {

    private static final String TASK_NO = "3f2a9c1e7b5d4a6089abcdef01234567";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskService taskService;

    @Autowired
    private TaskSseHub sseHub;

    @TempDir
    Path tempDir;

    // ---------- 提交：URL 路线 ----------

    @Test
    void submitUrlReturnsAcceptedWithTaskView() throws Exception {
        when(taskService.submitUrl("https://example.com/watch/1"))
                .thenReturn(pendingView(TaskInputType.URL, "https://example.com/watch/1"));

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/watch/1"}"""))
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.taskNo").value(TASK_NO))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.inputType").value("URL"))
                .andExpect(jsonPath("$.sourceUri").value("https://example.com/watch/1"))
                .andExpect(jsonPath("$.artifacts").isEmpty());
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

        verify(taskService, never()).submitUrl(anyString());
    }

    @Test
    void submitUrlConflictReturns409WithExistingTask() throws Exception {
        TaskView existing = new TaskView("aaaabbbbccccddddeeeeffff00001111", TaskInputType.UPLOAD,
                "demo.mp4", TaskStatus.RUNNING, null, "transcribe", null,
                LocalDateTime.of(2026, 9, 30, 10, 0), LocalDateTime.of(2026, 9, 30, 10, 0, 1), null,
                List.of());
        when(taskService.submitUrl(anyString())).thenThrow(new TaskConflictException(existing));

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/watch/1"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        containsString("taskNo=" + existing.taskNo())))
                .andExpect(jsonPath("$.existingTask.taskNo").value(existing.taskNo()))
                .andExpect(jsonPath("$.existingTask.status").value("RUNNING"));
    }

    // ---------- 提交：上传路线 ----------

    @Test
    void submitUploadReturnsAcceptedWithTaskView() throws Exception {
        when(taskService.submitUpload(any(), any()))
                .thenReturn(pendingView(TaskInputType.UPLOAD, "视频.mp4"));
        MockMultipartFile file = new MockMultipartFile("file", "视频.mp4", "video/mp4",
                "fake-bytes".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/tasks/upload").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskNo").value(TASK_NO))
                .andExpect(jsonPath("$.inputType").value("UPLOAD"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void submitUploadRejectsEmptyFileBeforeOpeningStream() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.mp4", "video/mp4", new byte[0]);

        mockMvc.perform(multipart("/api/tasks/upload").file(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("上传文件不能为空：请选择本地视频 / 音频文件后再提交"));

        verify(taskService, never()).submitUpload(any(), any());
    }

    // ---------- 查询 ----------

    @Test
    void getTaskReturnsTaskView() throws Exception {
        when(taskService.findByTaskNo(TASK_NO))
                .thenReturn(pendingView(TaskInputType.UPLOAD, "demo.mp4"));

        mockMvc.perform(get("/api/tasks/{taskNo}", TASK_NO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskNo").value(TASK_NO))
                .andExpect(jsonPath("$.currentStage").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void getTaskMapsMissingTaskToNotFound() throws Exception {
        when(taskService.findByTaskNo("nope")).thenThrow(new TaskNotFoundException("nope"));

        mockMvc.perform(get("/api/tasks/{taskNo}", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("任务不存在：nope")));
    }

    // ---------- SSE 订阅 ----------

    @Test
    void subscribeEventsPushesSnapshotFirstThenIncrementalBroadcasts() throws Exception {
        when(taskService.snapshot(TASK_NO)).thenReturn(new TaskProgressMessage(TASK_NO, TaskStatus.RUNNING,
                new StageEvent("transcribe", 60, "开始转写", StageEvent.StageState.RUNNING), null));

        // 连接建立：连接保持挂起（asyncStarted），快照已随响应写出（emitter 初始化时冲刷缓冲）
        MvcResult result = mockMvc.perform(get("/api/tasks/{taskNo}/events", TASK_NO))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        String snapshotBody = result.getResponse().getContentAsString();
        assertTrue(snapshotBody.contains("event:progress"), () -> "应先推快照：" + snapshotBody);
        assertTrue(snapshotBody.contains("\"taskStatus\":\"RUNNING\""), () -> snapshotBody);

        // 模拟管线推进：经真实 hub 写进同一批 emitter（注册表订阅的就是「写 emitter」这个动作）
        sseHub.send(TASK_NO, new TaskProgressMessage(TASK_NO, TaskStatus.RUNNING,
                new StageEvent("transcribe", 95, "转写完成", StageEvent.StageState.SUCCEEDED), null));
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"progress\":60"), () -> "快照的进度应先到：" + body);
        assertTrue(body.contains("\"progress\":95"), () -> "增量事件应随后到达：" + body);
        assertTrue(body.contains("\"state\":\"SUCCEEDED\""), () -> body);
    }

    @Test
    void subscribeEventsToTerminalTaskCompletesConnectionAfterSnapshot() throws Exception {
        when(taskService.snapshot(TASK_NO)).thenReturn(new TaskProgressMessage(TASK_NO, TaskStatus.FAILED,
                new StageEvent("transcribe", 0, "任务在 transcribe 阶段失败：quota exceeded",
                        StageEvent.StageState.FAILED), null));

        // 终态快照推完即收口（hub 对终态事件 complete emitter），连接不等 30 分钟超时；
        // 响应里应已带终态事件。连接收口的行为细节由 TaskSseHubTest 钉住。
        mockMvc.perform(get("/api/tasks/{taskNo}/events", TASK_NO))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(allOf(
                        containsString("event:progress"),
                        containsString("\"taskStatus\":\"FAILED\""))));
    }

    @Test
    void subscribeEventsReReadsSnapshotAfterSubscribingWhenTaskFinishesInBetween() throws Exception {
        // 竞态钉子：任务恰在「读快照 → 挂订阅」的窗口里到达终态——第一次读 RUNNING，
        // 挂上订阅后重读 FAILED。若实现只推第一次的快照，客户端拿到陈旧 RUNNING、
        // 且这条订阅既等不到终态事件也永远不会退订（注册表泄漏）。
        when(taskService.snapshot(TASK_NO))
                .thenReturn(new TaskProgressMessage(TASK_NO, TaskStatus.RUNNING,
                        new StageEvent("transcribe", 60, "开始转写", StageEvent.StageState.RUNNING), null))
                .thenReturn(new TaskProgressMessage(TASK_NO, TaskStatus.FAILED,
                        new StageEvent("transcribe", 0, "任务在 transcribe 阶段失败：quota exceeded",
                                StageEvent.StageState.FAILED), null));

        MvcResult result = mockMvc.perform(get("/api/tasks/{taskNo}/events", TASK_NO))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"taskStatus\":\"FAILED\""), () -> "重读后的终态快照应被推送：" + body);
        assertFalse(body.contains("\"taskStatus\":\"RUNNING\""), () -> "陈旧的 RUNNING 快照不得出现：" + body);
    }

    @Test
    void subscribeEventsMapsMissingTaskToNotFoundBeforeCreatingEmitter() throws Exception {
        when(taskService.snapshot("ghost")).thenThrow(new TaskNotFoundException("ghost"));

        mockMvc.perform(get("/api/tasks/{taskNo}/events", "ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("任务不存在：ghost")));

        verify(taskService).snapshot("ghost");
    }

    // ---------- 按需触发翻译（2026-10-02 两段式） ----------

    @Test
    void translateReturnsAcceptedWithRefreshedTaskView() throws Exception {
        when(taskService.translate(TASK_NO))
                .thenReturn(succeededView(TranslationStatus.RUNNING));

        mockMvc.perform(post("/api/tasks/{taskNo}/translate", TASK_NO))
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.taskNo").value(TASK_NO))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.translationStatus").value("RUNNING"));
    }

    @Test
    void translateMapsMissingTaskToNotFound() throws Exception {
        when(taskService.translate("ghost")).thenThrow(new TaskNotFoundException("ghost"));

        mockMvc.perform(post("/api/tasks/{taskNo}/translate", "ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("任务不存在：ghost")));
    }

    @Test
    void translateMapsNotReadyTaskToBadRequest() throws Exception {
        // 主任务未 SUCCEEDED 时触发翻译：错在调用方触发太早 → IAE → 400（error-handling.md「错在调用方」）
        when(taskService.translate(TASK_NO)).thenThrow(new IllegalArgumentException(
                "字幕尚未就绪：任务状态为 RUNNING，翻译只能在任务成功（SUCCEEDED）后触发；"
                        + "请先等主管线完成再试"));

        mockMvc.perform(post("/api/tasks/{taskNo}/translate", TASK_NO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("字幕尚未就绪")));
    }

    @Test
    void translateRejectsBlankTaskNoBeforeTouchingService() throws Exception {
        mockMvc.perform(post("/api/tasks/{taskNo}/translate", " "))
                .andExpect(status().isBadRequest());

        verify(taskService, never()).translate(anyString());
    }

    // ---------- SSE 订阅：翻译期间（2026-10-02 两段式） ----------

    @Test
    void subscribeEventsDuringTranslationKeepsConnectionOpenForTranslateEvents() throws Exception {
        // 主任务已 SUCCEEDED、翻译进行中：快照虽是「主状态终态」，但翻译事件还要继续推，
        // 连接不得按主状态终态收口（终态判定 = 主状态终态且翻译非 RUNNING）
        when(taskService.snapshot(TASK_NO)).thenReturn(new TaskProgressMessage(TASK_NO,
                TaskStatus.SUCCEEDED,
                new StageEvent("translate", 0, "翻译进行中", StageEvent.StageState.RUNNING),
                TranslationStatus.RUNNING));

        MvcResult result = mockMvc.perform(get("/api/tasks/{taskNo}/events", TASK_NO))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        // 模拟管线推进：翻译进度事件经真实 hub 写进同一批 emitter（若连接被提前收口，此事件无人可写）
        sseHub.send(TASK_NO, new TaskProgressMessage(TASK_NO, TaskStatus.SUCCEEDED,
                new StageEvent("translate", 55, "第 2/3 批", StageEvent.StageState.RUNNING),
                TranslationStatus.RUNNING));

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"translationStatus\":\"RUNNING\""), () -> "快照应携带翻译子状态：" + body);
        assertTrue(body.contains("\"progress\":0"), () -> "翻译进行中快照应先到：" + body);
        assertTrue(body.contains("\"progress\":55"), () -> "翻译进度增量事件应随后到达：" + body);
    }

    @Test
    void subscribeEventsToTranslatedTaskCompletesAfterTranslationTerminalSnapshot() throws Exception {
        // 主任务 SUCCEEDED + 翻译 SUCCEEDED：真正再无后续事件，快照推完即收口
        when(taskService.snapshot(TASK_NO)).thenReturn(new TaskProgressMessage(TASK_NO,
                TaskStatus.SUCCEEDED,
                new StageEvent("translate", 100, "翻译完成", StageEvent.StageState.SUCCEEDED),
                TranslationStatus.SUCCEEDED));

        MvcResult result = mockMvc.perform(get("/api/tasks/{taskNo}/events", TASK_NO))
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"translationStatus\":\"SUCCEEDED\""), () -> body);
        assertTrue(body.contains("翻译完成"), () -> body);
    }

    // ---------- 产物下载 ----------

    @Test
    void downloadArtifactStreamsFileWithAttachmentDisposition() throws Exception {
        Path artifact = tempDir.resolve("subtitles-cloud-translate-ja.srt");
        Files.writeString(artifact, "1\n00:00:00,000 --> 00:00:01,000\nこんにちは\n", StandardCharsets.UTF_8);
        when(taskService.openArtifact(TASK_NO, "subtitles-cloud-translate-ja.srt"))
                .thenReturn(new TaskArtifactFile(artifact, "subtitles-cloud-translate-ja.srt", Files.size(artifact)));

        mockMvc.perform(get("/api/tasks/{taskNo}/artifacts/{fileName}", TASK_NO,
                        "subtitles-cloud-translate-ja.srt"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(content().bytes(Files.readAllBytes(artifact)));
    }

    @Test
    void downloadArtifactMapsMissingTaskToNotFound() throws Exception {
        when(taskService.openArtifact("ghost", "demo.srt")).thenThrow(new TaskNotFoundException("ghost"));

        mockMvc.perform(get("/api/tasks/{taskNo}/artifacts/{fileName}", "ghost", "demo.srt"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("任务不存在：ghost")));
    }

    @Test
    void downloadArtifactMapsPathGuardRejectionToBadRequest() throws Exception {
        // 穿越检测本体在 TaskServiceTest（../、绝对路径、反斜杠、URL 编码四个形态）；
        // 这里只钉「服务层拒绝 → 400」的映射。注意：URL 里裸写 ../ 会被容器规范化成 404，
        // 到不了控制器——这正是编码绕过必须在服务层 resolve 后校验的原因。
        // fileName 的具体解码形态随 servlet 实现而异，用 anyString() 只锚定映射关系。
        when(taskService.openArtifact(eq(TASK_NO), anyString()))
                .thenThrow(new IllegalArgumentException("非法产物路径：..%2Fsecret.txt；"
                        + "产物名只能是不含路径分隔符的文件名，请从 GET /api/tasks/{taskNo} 响应的 artifacts 清单里取"));

        mockMvc.perform(get("/api/tasks/{taskNo}/artifacts/{fileName}", TASK_NO, "%2e%2e%2fsecret.txt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("非法产物路径")));
    }

    @Test
    void downloadArtifactRejectsBlankFileNameBeforeTouchingService() throws Exception {
        mockMvc.perform(get("/api/tasks/{taskNo}/artifacts/{fileName}", TASK_NO, " "))
                .andExpect(status().isBadRequest());

        verify(taskService, never()).openArtifact(anyString(), anyString());
    }

    // ---------- 工厂 ----------

    private static TaskView pendingView(TaskInputType inputType, String sourceUri) {
        return new TaskView(TASK_NO, inputType, sourceUri,
                TaskStatus.PENDING, null, null, null, LocalDateTime.of(2026, 9, 30, 10, 0), null, null,
                List.of());
    }

    /** 主任务已成功的视图（自动管线跑完；翻译子状态由用例给定）。 */
    private static TaskView succeededView(TranslationStatus translationStatus) {
        return new TaskView(TASK_NO, TaskInputType.URL, "https://example.com/watch/1",
                TaskStatus.SUCCEEDED, translationStatus, "clean", null,
                LocalDateTime.of(2026, 10, 2, 10, 0), LocalDateTime.of(2026, 10, 2, 10, 0, 1),
                LocalDateTime.of(2026, 10, 2, 10, 5), List.of());
    }
}
