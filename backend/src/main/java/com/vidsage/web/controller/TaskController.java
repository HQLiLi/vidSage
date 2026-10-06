package com.vidsage.web.controller;

import com.vidsage.application.task.TaskProgressMessage;
import com.vidsage.application.task.query.TaskArtifactFile;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.application.task.TaskService;
import com.vidsage.application.task.subscribe.Subscription;
import com.vidsage.application.task.subscribe.TaskSubscriptionRegistry;
import com.vidsage.web.dto.SubmitUrlRequest;
import com.vidsage.web.dto.TaskResponse;
import com.vidsage.web.sse.TaskSseHub;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 任务提交、状态查询、按需翻译、进度订阅与产物下载 HTTP 入口（MVP-1 异步管线的唯一前端依赖）。
 *
 * <p>本类只做「接收 → 卫语句 → 委托 → 返回」，不写业务逻辑；提交即返回 202 与任务视图，
 * <b>不等管线跑完</b>——长视频不再顶客户端超时。自动管线跑到清洗 / 原文字幕为止（2026-10-02
 * 两段式裁定），翻译由用户对 SUCCEEDED 任务显式触发（{@code POST /{taskNo}/translate}）。
 *
 * <p>SSE 订阅（{@code /events}）的接线：读 DB 快照（未知 taskNo 在此 404）→ 建 emitter →
 * 挂「写 emitter」的 Consumer 进注册表 → 重读快照推送（防「读快照 → 挂订阅」窗口内任务到达
 * 终态造成的陈旧快照与订阅泄漏）→ 挂起等增量。终态快照经 {@link TaskSseHub#send} 推完即收口
 * 连接；终态任务不再有增量，随后退订防泄漏。连接的持有 / 心跳 / 清理全部在 {@code web/sse/TaskSseHub}。
 *
 * <p>日志只记「请求形态」：URL 原文与上传文件原始名指向用户资源，一律不落日志
 * （design.md 任务表 source_uri 注、logging-guidelines 脱敏红线）。
 */
@Slf4j
@Tag(name = "任务", description = "任务提交（URL / 文件上传）、状态查询、按需翻译、进度订阅（SSE）与"
        + "产物下载：异步管线入口，提交即返回 202；自动管线到原文字幕为止，翻译按需触发")
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    private final TaskSseHub sseHub;

    private final TaskSubscriptionRegistry subscriptionRegistry;

    /**
     * 构造函数注入。
     *
     * @param taskService          提交与查询的唯一服务层入口
     * @param sseHub               SSE 连接管理（emitter 持有 / 心跳 / 清理，仅本层可见 HTTP 细节）
     * @param subscriptionRegistry 中立订阅注册表（管线广播的落点）
     */
    public TaskController(TaskService taskService, TaskSseHub sseHub,
                          TaskSubscriptionRegistry subscriptionRegistry) {
        this.taskService = taskService;
        this.sseHub = sseHub;
        this.subscriptionRegistry = subscriptionRegistry;
    }

    /**
     * 提交 URL 任务：异步执行 下载 → 抽音频 → 转写 → 清洗，到原文字幕就绪为止（2026-10-02
     * 两段式）；翻译由任务 SUCCEEDED 后按 {@link #translate} 显式触发。
     *
     * <p><b>当前支持的输入（2026-10-02 记录，待前端落地后复议校验层级）</b>：公网媒体页面 URL，
     * 实测可用的是 yt-dlp 支持的站点——本项目验证过 B 站视频页与 YouTube；未验证站点不提前拦截，
     * 交给 yt-dlp 提取，失败即任务 FAILED + SSE 推 FAILED 事件（不做站点白名单：等于把 yt-dlp 的
     * 站点知识复制一份进来，维护成本高）。本条是 PRD 验收标准「不支持的链接 → 4xx」被
     * 项目所有者改为「暂以 FAILED 呈现」的依据；提交口预检（如域名白名单）与解析期失败
     * 何者更合适，等前端做完按真实交互再定（见 09-24-vidsage-mvp1-submit prd.md 验收标准）。
     *
     * @param request 请求体（url 必填）
     * @return 新任务的 PENDING 视图（拿 taskNo 去查询 / 订阅进度）
     * @throws IllegalArgumentException url 为空白（400）
     * @throws com.vidsage.application.task.exception.TaskConflictException 同 URL 已有进行中任务（409，响应体附既有任务）
     */
    @Operation(summary = "提交 URL 任务",
            description = "异步执行到原文字幕就绪（下载 → 抽音频 → 转写 → 清洗），立即返回 202 与任务视图；"
                    + "翻译需任务成功后另调 POST /api/tasks/{taskNo}/translate 触发。"
                    + "同一 URL 已有排队 / 执行中任务时返回 409")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskResponse submitUrl(@RequestBody SubmitUrlRequest request) {
        // 卫语句先于一切 I/O（error-handling.md）：url 缺失在边界拦下，不进服务层
        if (request == null || !StringUtils.hasText(request.url())) {
            throw new IllegalArgumentException("url 不能为空：请传公网媒体页面地址（如 B 站 / YouTube 链接），"
                    + "上传本地文件请走 POST /api/tasks/upload");
        }
        log.info("收到任务提交: sourceType=URL");
        return TaskResponse.from(taskService.submitUrl(request.url()));
    }

    /**
     * 上传本地视频 / 音频文件提交任务：流式落盘（按内容寻址），异步执行到原文字幕就绪为止
     * （2026-10-02 两段式）；翻译由任务 SUCCEEDED 后按 {@link #translate} 显式触发。
     *
     * @param file 上传的媒体文件（multipart 字段名 file）；不能为空
     * @return 新任务的 PENDING 视图
     * @throws IllegalArgumentException 文件缺失或为空（400）
     * @throws com.vidsage.application.task.exception.TaskConflictException 同内容文件已有进行中任务（409）
     * @throws IllegalStateException    读取上传流失败 / 落盘失败（500）
     */
    @Operation(summary = "上传本地文件提交任务",
            description = "multipart 字段名 file；流式落盘按内容寻址，同一文件重复上传命中同一工作区。"
                    + "上限见 spring.servlet.multipart.max-file-size（默认 2GB）")
    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskResponse submitUpload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空：请选择本地视频 / 音频文件后再提交");
        }
        // 原始文件名是用户资源，不落日志；字节数是度量，可以记
        log.info("收到任务提交: sourceType=UPLOAD, sizeBytes={}", file.getSize());
        try (InputStream in = file.getInputStream()) {
            return TaskResponse.from(taskService.submitUpload(in, file.getOriginalFilename()));
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件流失败：" + e.getMessage() + "；请重试或换一个文件", e);
        }
    }

    /**
     * 查询任务状态：状态、当前阶段、错误信息与产物清单。
     *
     * @param taskNo 提交响应返回的任务号
     * @return 任务视图（artifacts 为工作区内可见产物）
     * @throws IllegalArgumentException taskNo 为空（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     */
    @Operation(summary = "查询任务状态",
            description = "按 taskNo 返回任务视图（状态 / 当前阶段 / 错误信息 / 产物清单）；不存在返回 404 与中文说明")
    @GetMapping("/{taskNo}")
    public TaskResponse getTask(@PathVariable String taskNo) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        log.info("查询任务: taskNo={}", taskNo);
        return TaskResponse.from(taskService.findByTaskNo(taskNo));
    }

    /**
     * 订阅任务进度（SSE）：连接建立即推一条 DB 快照，此后随管线推进增量推送；
     * 任务到终态（SUCCEEDED / FAILED）推完最后一条即完成连接。事件名 {@code progress}，
     * data 为 {@code TaskProgressMessage} JSON。刷新页面重连 = 重新拿快照，进度无感恢复。
     *
     * @param taskNo 提交响应返回的任务号
     * @return 挂起中的 SSE 连接（text/event-stream）
     * @throws IllegalArgumentException taskNo 为空（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404，此时不留悬挂连接）
     */
    @Operation(summary = "订阅任务进度（SSE）",
            description = "text/event-stream；事件名 progress，data 为 TaskProgressMessage JSON。"
                    + "连接建立先推一条 DB 快照，之后增量推送，终态推完即关闭")
    @GetMapping(value = "/{taskNo}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeEvents(@PathVariable String taskNo) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        log.info("订阅任务进度: taskNo={}", taskNo);
        // 先读快照再建 emitter：未知 taskNo 在此 404，不给不存在的任务留悬挂连接
        taskService.snapshot(taskNo);
        SseEmitter emitter = sseHub.register(taskNo);
        // 挂「写 emitter」的 Consumer 进中立注册表：管线广播 → hub.send → emitter，HTTP 类型不越出 web/sse
        Subscription subscription = subscriptionRegistry.subscribe(taskNo, event -> sseHub.send(taskNo, event));
        // 挂上订阅后重读快照：任务可能恰在「读快照 → 挂订阅」的窗口里到达终态——终态广播与 complete
        // 都发生在订阅挂上之前，若只推窗口前读到的旧快照，这条订阅既等不到终态事件也永远不会退订
        // （注册表泄漏）。状态机只前进，重读结果只会更新；此时推的是终态快照，hub 推完即收口连接。
        TaskProgressMessage snapshot = taskService.snapshot(taskNo);
        sseHub.send(taskNo, snapshot);
        // 客户端断连 / 超时 / 终态 complete 都会走 onCompletion（onError、onTimeout 之后容器必补发
        // onCompletion）——连接没了就必须退订。否则泄漏的订阅者继续留在注册表里，同任务的后续广播
        // 会经它再写一遍给幸存连接（真实运行证据抓到的重复推送缺陷，见 research/acceptance-evidence.md）。
        // close 幂等，与上方终态分支的 close 并发安全。
        emitter.onCompletion(subscription::close);
        // 终态判定单源在 VideoTask.isEventSettled（主状态终态且翻译不在 RUNNING）
        if (VideoTask.isEventSettled(snapshot.taskStatus(), snapshot.translationStatus())) {
            // 订阅时任务已终态：快照即最后一条，注册表不会再有 complete 调用，主动退订防泄漏
            subscription.close();
        }
        return emitter;
    }

    /**
     * 按需触发字幕翻译（2026-10-02 两段式）：提交后的自动管线只跑到清洗 / 原文字幕为止，
     * 用户点「翻译」才触发——不看翻译的人不花 LLM 钱。幂等语义见服务层：
     * 已有翻译产物则幂等收敛（不发 LLM），翻译进行中重复触发返回当前视图不重入。
     *
     * @param taskNo 提交响应返回的任务号
     * @return 触发后的任务视图（translationStatus 反映翻译子状态；正常触发为 RUNNING）
     * @throws IllegalArgumentException taskNo 为空（400）或字幕尚未就绪（400，主任务未 SUCCEEDED）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     */
    @Operation(summary = "触发字幕翻译（按需）",
            description = "主任务成功（SUCCEEDED）后可触发；202 返回触发后的任务视图。幂等：已有翻译产物"
                    + "不重复调用 LLM，翻译进行中重复触发返回当前视图；主任务未成功返回 400（字幕尚未就绪）")
    @PostMapping("/{taskNo}/translate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskResponse translate(@PathVariable String taskNo) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        log.info("触发字幕翻译: taskNo={}", taskNo);
        return TaskResponse.from(taskService.translate(taskNo));
    }

    /**
     * 下载任务产物：流式返回工作区内的文件，文件名来自任务视图的 artifacts 清单。
     * 路径穿越防护（{@code ../} / 绝对路径 / 编码绕过）在服务层 resolve 后校验，越界一律 400。
     *
     * @param taskNo   提交响应返回的任务号
     * @param fileName 产物文件名（清单里的 name）
     * @return 产物文件流（application/octet-stream，附件下载）
     * @throws IllegalArgumentException taskNo / fileName 为空、越出工作区或产物不存在（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     */
    @Operation(summary = "下载任务产物",
            description = "流式返回产物文件；fileName 从任务视图 artifacts 清单里取，"
                    + "越出任务工作区的路径一律 400")
    @GetMapping("/{taskNo}/artifacts/{fileName}")
    public ResponseEntity<FileSystemResource> downloadArtifact(@PathVariable String taskNo,
                                                               @PathVariable String fileName) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        if (!StringUtils.hasText(fileName)) {
            throw new IllegalArgumentException("fileName 不能为空：请从任务视图 artifacts 清单里取产物名");
        }
        log.info("下载任务产物: taskNo={}", taskNo);
        TaskArtifactFile artifact = taskService.openArtifact(taskNo, fileName);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(artifact.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(artifact.name()).build().toString())
                .body(new FileSystemResource(artifact.path()));
    }
}
