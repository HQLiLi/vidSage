package com.vidsage.web.controller;

import com.vidsage.application.task.VideoTaskService;
import com.vidsage.application.task.query.TaskArtifactFile;
import com.vidsage.web.dto.*;
import com.vidsage.web.sse.TaskSseConnectionManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.io.InputStream;

/**
 * 任务提交、状态查询、按需翻译、进度订阅与产物下载 HTTP 入口。
 *
 * <p>本类只做「接收 → 卫语句 → 委托 → 返回」，不写业务逻辑；提交即返回 202 与任务视图，
 * <b>不等管线跑完</b>——长视频不会顶客户端超时。自动管线跑到清洗 / 原文字幕为止，
 * 翻译由用户对 SUCCEEDED 任务显式触发（{@code POST /{taskNo}/translate}）。
 */
@Slf4j
@Tag(name = "任务", description = "任务提交（URL / 文件上传）、状态查询、按需翻译、进度订阅（SSE）与" + "产物下载：异步管线入口，提交即返回 202；自动管线到原文字幕为止，翻译按需触发")
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final VideoTaskService taskService;

    private final TaskSseConnectionManager sseConnectionManager;

    public TaskController(VideoTaskService taskService, TaskSseConnectionManager sseConnectionManager) {
        this.taskService = taskService;
        this.sseConnectionManager = sseConnectionManager;
    }

    /**
     * 提交 URL 任务：异步执行 下载 → 抽音频 → 转写 → 清洗，到原文字幕就绪为止；
     * 翻译由任务 SUCCEEDED 后按 {@link #translate} 显式触发。
     * <p>
     * 支持的站点——本项目验证过 B 站视频页与 YouTube；未验证站点不提前拦截，
     * 交给 yt-dlp 提取，失败即任务 FAILED + SSE 推 FAILED 事件
     *
     * @param request 请求体（url 必填；keywords 可选）
     * @return 新任务的 PENDING 视图（拿 taskNo 去查询 / 订阅进度）
     * @throws IllegalArgumentException                                     url 为空白 / 关键词超限（400）
     * @throws com.vidsage.application.task.exception.TaskConflictException 同 URL 已有进行中任务（409，响应体附既有任务）
     */
    @Operation(summary = "提交 URL 任务", description = "异步执行到原文字幕就绪（下载 → 抽音频 → 转写 → 清洗），立即返回 202 与任务视图；" + "翻译需任务成功后另调 POST /api/tasks/{taskNo}/translate 触发。" + "同一 URL 已有排队 / 执行中任务时返回 409。" + "keywords 为 null（字段缺席）= 本次不带关键词、不动该工作区已有的关键词，" + "[]（显式空数组）= 写入空清单（该工作区此后按原样清洗）；" + "单条最多 50 字、最多 50 条，超限 400 且不落库")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskResponse submitUrl(@RequestBody SubmitUrlRequest request) {
        if (request == null || !StringUtils.hasText(request.url())) {
            throw new IllegalArgumentException("url 不能为空：请传公网媒体页面地址（如 B 站 / YouTube 链接），" + "上传本地文件请走 POST /api/tasks/upload");
        }
        log.info("收到任务提交: sourceType=URL");
        return TaskResponse.from(taskService.submitUrl(request.url(), request.keywords()));
    }

    /**
     * 上传本地视频 / 音频文件提交任务：流式落盘（按内容寻址），异步执行到原文字幕就绪为止
     * 翻译由任务 SUCCEEDED 后按 {@link #translate} 显式触发。
     *
     * @param file     上传的媒体文件（multipart 字段名 file）；不能为空
     * @param keywords 可选关键词，可重复携带（keywords=A&amp;keywords=B）；缺席 = 不带关键词
     * @return 新任务的 PENDING 视图
     * @throws IllegalArgumentException                                     文件缺失或为空 / 关键词超限（400）
     * @throws com.vidsage.application.task.exception.TaskConflictException 同内容文件已有进行中任务（409）
     * @throws IllegalStateException                                        读取上传流失败 / 落盘失败（500）
     */
    @Operation(summary = "上传本地文件提交任务", description = "multipart 字段名 file；流式落盘按内容寻址，同一文件重复上传命中同一工作区。" + "上限见 spring.servlet.multipart.max-file-size（默认 2GB）。" + "可选 keywords 字段可重复携带（keywords=A&keywords=B），语义与 POST /api/tasks 一致" + "（缺席 = 不带关键词、不动该工作区已有的关键词；[] = 写入空清单）；" + "单条最多 50 字、最多 50 条，超限 400 且不落库")
    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskResponse submitUpload(@RequestParam("file") MultipartFile file, @RequestParam(value = "keywords", required = false) List<String> keywords) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空：请选择本地视频 / 音频文件后再提交");
        }
        // 原始文件名是用户资源，不落日志；字节数是度量，可以记
        log.info("收到任务提交: sourceType=UPLOAD, sizeBytes={}", file.getSize());
        try (InputStream in = file.getInputStream()) {
            return TaskResponse.from(taskService.submitUpload(in, file.getOriginalFilename(), keywords));
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件流失败：" + e.getMessage() + "；请重试或换一个文件", e);
        }
    }

    /**
     * 查询任务状态：状态、当前阶段、错误信息与产物清单。
     *
     * @param taskNo 提交响应返回的任务号
     * @return 任务视图（artifacts 为工作区内可见产物）
     * @throws IllegalArgumentException                                     taskNo 为空（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     */
    @Operation(summary = "查询任务状态", description = "按 taskNo 返回任务视图（状态 / 当前阶段 / 错误信息 / 产物清单）；不存在返回 404 与中文说明")
    @GetMapping("/{taskNo}")
    public TaskResponse getTask(@PathVariable String taskNo) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        log.info("查询任务: taskNo={}", taskNo);
        return TaskResponse.from(taskService.findByTaskNo(taskNo));
    }

    /**
     * 订阅任务进度（SSE）
     *
     * @param taskNo 提交响应返回的任务号
     * @return 挂起中的 SSE 连接（text/event-stream）
     * @throws IllegalArgumentException                                     taskNo 为空（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404，此时不留悬挂连接）
     */
    @Operation(summary = "订阅任务进度（SSE）", description = "text/event-stream；事件名 progress，data 为 TaskProgressMessage JSON。" + "连接建立先推一条 DB 快照，之后增量推送，终态推完即关闭")
    @GetMapping(value = "/{taskNo}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeEvents(@PathVariable String taskNo) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        log.info("订阅任务进度: taskNo={}", taskNo);
        taskService.snapshot(taskNo);
        return sseConnectionManager.open(taskNo, () -> taskService.snapshot(taskNo));
    }

    /**
     * 按需触发字幕翻译：提交后的自动管线只跑到清洗 / 原文字幕为止，
     * 用户点「翻译」才触发——不看翻译的人不花 LLM 钱。幂等语义见服务层：
     * 已有该语种的翻译产物则幂等收敛（不发 LLM）。
     *
     * @param taskNo  提交响应返回的任务号
     * @param request 源语种与目标语种（两个都过白名单，白名单外 400）
     * @return 触发后的任务视图（主状态与产物清单；翻译进度走 SSE，不落库）
     * @throws IllegalArgumentException                                     taskNo 为空（400）、语种不在白名单（400）或字幕尚未就绪（400，主任务未 SUCCEEDED）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     * @throws com.vidsage.application.task.exception.TaskConflictException 同工作区已有后置会话在跑（409）
     */
    @Operation(summary = "触发字幕翻译（按需）", description = "主任务成功（SUCCEEDED）后可触发；body 带源语种与目标语种（都过白名单，白名单外 400）；202 返回触发后的任务视图。"
            + "幂等：该语种的翻译产物已在则不重复调用 LLM；同工作区已有后置会话（翻译 / 重新生成）在跑返回 409；主任务未成功返回 400（字幕尚未就绪）")
    @PostMapping("/{taskNo}/translate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskResponse translate(@PathVariable String taskNo,
                                  @RequestBody TranslationRequest request) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        log.info("触发字幕翻译: taskNo={}", taskNo);
        return TaskResponse.from(taskService.translate(taskNo, request.sourceLanguage(), request.targetLanguage()));
    }

    /**
     * 重新生成字幕（后置会话）：按请求里的关键词重跑清洗，成功后旧的译文与术语产物一并清掉
     * （译文对不上新字幕了）。同步判定（404 / 400 未就绪 / 409 互斥 / 400 关键词超限）在服务层完成，
     * 202 返回当前任务视图，进度另走 SSE。
     *
     * @param taskNo  提交响应返回的任务号
     * @param request 关键词清单；<b>{@code null}（字段缺席）= 不改变已有列表，按现有关键词重跑；
     *                {@code []}（显式空数组）= 清空后重跑</b>
     * @return 触发后的任务视图
     * @throws IllegalArgumentException                                     taskNo / 关键词超限（400）、字幕尚未就绪（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     * @throws com.vidsage.application.task.exception.TaskConflictException 同一工作区已有重新生成会话在跑（409）
     */
    @Operation(summary = "重新生成字幕（改关键词后重跑清洗）", description = "主任务 SUCCEEDED 后可触发；202 返回任务视图，进度走 SSE。" + "keywords 为 null（字段缺席）= 不改变已有列表，[]（显式空数组）= 清空，两者语义不同。" + "单条最多 50 字、最多 50 条，超限 400 且不落库不重跑；" + "同一工作区已有重新生成在跑时 409；主任务未成功时 400（字幕尚未就绪）")
    @PostMapping("/{taskNo}/subtitles/regenerate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskResponse regenerate(@PathVariable String taskNo, @RequestBody RegenerateSubtitlesRequest request) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        log.info("重新生成字幕: taskNo={}", taskNo);
        return TaskResponse.from(taskService.regenerate(taskNo, request == null ? null : request.keywords()));
    }

    /**
     * 读回该任务的关键词清单与指纹：前端拿指纹与 {@code segments-*-clean.json} 里的
     * {@code keywordsFingerprint} 比对，就知道「这份字幕对不对得上当前关键词」。
     *
     * @param taskNo 提交响应返回的任务号
     * @return 关键词清单（用户填写顺序）与指纹；从没填过时为空清单
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     */
    @Operation(summary = "查询关键词清单与指纹", description = "按 taskNo 返回该工作区的关键词清单（用户填写顺序）与指纹；不存在的任务返回 404")
    @GetMapping("/{taskNo}/keywords")
    public SubtitleKeywordsResponse findKeywords(@PathVariable String taskNo) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        return SubtitleKeywordsResponse.from(taskService.findKeywords(taskNo));
    }

    /**
     * 下载任务产物：流式返回工作区内的文件，文件名来自任务视图的 artifacts 清单。
     * 路径穿越防护（{@code ../} / 绝对路径 / 编码绕过）在服务层 resolve 后校验，越界一律 400。
     *
     * @param taskNo   提交响应返回的任务号
     * @param fileName 产物文件名（清单里的 name）
     * @return 产物文件流（application/octet-stream，附件下载）
     * @throws IllegalArgumentException                                     taskNo / fileName 为空、越出工作区或产物不存在（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     */
    @Operation(summary = "下载任务产物", description = "流式返回产物文件；fileName 从任务视图 artifacts 清单里取，" + "越出任务工作区的路径一律 400")
    @GetMapping("/{taskNo}/artifacts/{fileName}")
    public ResponseEntity<FileSystemResource> downloadArtifact(@PathVariable String taskNo, @PathVariable String fileName) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        if (!StringUtils.hasText(fileName)) {
            throw new IllegalArgumentException("fileName 不能为空：请从任务视图 artifacts 清单里取产物名");
        }
        log.info("下载任务产物: taskNo={}", taskNo);
        TaskArtifactFile artifact = taskService.openArtifact(taskNo, fileName);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(artifact.sizeBytes()).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(artifact.name()).build().toString()).body(new FileSystemResource(artifact.path()));
    }
}
