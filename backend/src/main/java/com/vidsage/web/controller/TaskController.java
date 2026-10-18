package com.vidsage.web.controller;

import com.vidsage.application.task.SubtitleTaskQueryService;
import com.vidsage.application.task.SubtitleTaskService;
import com.vidsage.application.task.TaskRunRef;
import com.vidsage.web.dto.*;
import com.vidsage.web.sse.SseTaskProgressBroadcaster;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 任务提交、状态查询、按需翻译、进度订阅的 HTTP 入口。
 *
 * <p>本类只做「接收 → 卫语句 → 委托 → 返回」，不写业务逻辑；提交即返回 202 与
 * {@code {taskNo, runNo}}，<b>不等管线跑完</b>。自动管线跑到清洗 / 原文字幕为止，
 * 翻译由用户对已完成的任务显式触发（{@code POST /{taskNo}/translate}）。
 *
 * <p>进度按「一条 run 一条流」订阅：路径里带 {@code runNo}，同一条 run 的进度一条流看全。
 */
@Slf4j
@Tag(name = "任务", description = "任务提交（URL / 文件上传）、状态查询、按需翻译与进度订阅（SSE）："
        + "异步管线入口，提交即返回 202；自动管线到原文字幕为止，翻译按需触发")
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final SubtitleTaskService taskService;

    private final SubtitleTaskQueryService queryService;

    private final SseTaskProgressBroadcaster broadcaster;

    public TaskController(SubtitleTaskService taskService, SubtitleTaskQueryService queryService,
                          SseTaskProgressBroadcaster broadcaster) {
        this.taskService = taskService;
        this.queryService = queryService;
        this.broadcaster = broadcaster;
    }

    /**
     * 提交 URL 任务：异步执行 下载 → 抽音频 → 转写 → 清洗，到原文字幕就绪为止；
     * 翻译由任务成功后按 {@link #translate} 显式触发。
     * <p>
     * 支持的站点——本项目验证过 B 站视频页与 YouTube；未验证站点不提前拦截，
     * 交给 yt-dlp 提取，失败即该次执行 FAILED + SSE 推 FAILED 事件
     *
     * @param request 请求体（url 必填；keywords 可选）
     * @return 202 与本次触发的 {@code {taskNo, runNo}}（拿它去订阅这条 run 的进度）
     * @throws IllegalArgumentException                                     url 为空白 / 关键词超限（400）
     * @throws com.vidsage.application.task.exception.TaskConflictException 该任务已有一次执行在跑（409）
     */
    @Operation(summary = "提交 URL 任务", description = "异步执行到原文字幕就绪（下载 → 抽音频 → 转写 → 清洗），立即返回 202 与 {taskNo, runNo}；"
            + "翻译需任务成功后另调 POST /api/tasks/{taskNo}/translate 触发。"
            + "同一任务已有一次执行在跑时返回 409（同形状 {taskNo, runNo}）。"
            + "keywords 为 null（字段缺席）= 本次不带关键词、不动该任务已有的关键词，"
            + "[]（显式空数组）= 写入空清单（该任务此后按原样清洗）；"
            + "单条最多 50 字、最多 50 条，超限 400 且不落库")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskRunRefResponse submitUrl(@RequestBody SubmitUrlRequest request) {
        if (request == null || !StringUtils.hasText(request.url())) {
            throw new IllegalArgumentException("url 不能为空：请传公网媒体页面地址（如 B 站 / YouTube 链接），"
                    + "上传本地文件请走 POST /api/tasks/upload");
        }
        log.info("收到任务提交: sourceType=URL");
        return TaskRunRefResponse.from(taskService.submitUrl(request.url(), request.keywords()));
    }

    /**
     * 上传本地视频 / 音频文件提交任务：流式落盘（按内容寻址），异步执行到原文字幕就绪为止
     * 翻译由任务成功后按 {@link #translate} 显式触发。
     *
     * @param file     上传的媒体文件（multipart 字段名 file）；不能为空
     * @param keywords 可选关键词，可重复携带（keywords=A&amp;keywords=B）；缺席 = 不带关键词
     * @return 202 与本次触发的 {@code {taskNo, runNo}}
     * @throws IllegalArgumentException                                     文件缺失或为空 / 关键词超限（400）
     * @throws com.vidsage.application.task.exception.TaskConflictException 该任务已有一次执行在跑（409）
     * @throws IllegalStateException                                        读取上传流失败 / 落盘失败（500）
     */
    @Operation(summary = "上传本地文件提交任务", description = "multipart 字段名 file；流式落盘按内容寻址，同一文件重复上传命中同一工作区。"
            + "上限见 spring.servlet.multipart.max-file-size（默认 2GB）。"
            + "可选 keywords 字段可重复携带（keywords=A&keywords=B），语义与 POST /api/tasks 一致"
            + "（缺席 = 不带关键词、不动该任务已有的关键词；[] = 写入空清单）；"
            + "单条最多 50 字、最多 50 条，超限 400 且不落库")
    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskRunRefResponse submitUpload(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "keywords", required = false) List<String> keywords) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空：请选择本地视频 / 音频文件后再提交");
        }
        // 原始文件名是用户资源，不落日志；字节数是度量，可以记
        log.info("收到任务提交: sourceType=UPLOAD, sizeBytes={}", file.getSize());
        try (InputStream in = file.getInputStream()) {
            return TaskRunRefResponse.from(taskService.submitUpload(in, file.getOriginalFilename(), keywords));
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件流失败：" + e.getMessage() + "；请重试或换一个文件", e);
        }
    }

    /**
     * 查询任务状态：任务身份与最近一次执行。
     *
     * @param taskNo 提交响应返回的任务号
     * @return 任务视图（身份 + lastRun；从未执行时 lastRun 为 null）
     * @throws IllegalArgumentException                                     taskNo 为空（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     */
    @Operation(summary = "查询任务状态", description = "按 taskNo 返回任务身份与最近一次执行（lastRun）；"
            + "扁平状态、当前阶段、错误信息、起止时间都在 lastRun 里；不存在返回 404 与中文说明")
    @GetMapping("/{taskNo}")
    public TaskResponse getTask(@PathVariable String taskNo) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        log.info("查询任务: taskNo={}", taskNo);
        return TaskResponse.from(queryService.findByTaskNo(taskNo));
    }

    /**
     * 订阅某一次执行的进度（SSE）：连接建立即推一条 DB 快照，之后增量推送，终态推完即关闭。
     *
     * <p>返回类型写成 {@link Object}：同一条路径要能吐两种返回值——仍在跑的 run 返回
     * {@code SseEmitter}（开流），已到终态的 run 返回 {@code 204 No Content}（告诉客户端
     * 别再重连，去看 {@code GET /{taskNo}} 的 lastRun 即可）。
     *
     * @param taskNo 提交响应返回的任务号
     * @param runNo  要订阅的那一次执行的序号
     * @return 仍在跑：{@code SseEmitter}（text/event-stream）；已终态：204 No Content
     * @throws IllegalArgumentException                                     taskNo 为空（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在或该 runNo 不存在（404）
     */
    @Operation(summary = "订阅某次执行的进度（SSE）", description = "text/event-stream；事件名 progress，data 为 TaskRunProgress JSON。"
            + "连接建立先推一条 DB 快照，之后增量推送，终态推完即关闭。"
            + "runNo 不存在返回 404；该 run 已到终态返回 204（不推终态快照，客户端改查 GET /api/tasks/{taskNo} 的 lastRun）")
    @GetMapping(value = "/{taskNo}/runs/{runNo}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object subscribeRunEvents(@PathVariable String taskNo, @PathVariable int runNo) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        log.info("订阅任务进度: taskNo={}, runNo={}", taskNo, runNo);
        // 仍在跑才开流；已到终态回 204（不推终态快照，客户端改查 GET /{taskNo} 的 lastRun）
        if (queryService.streamSnapshot(taskNo, runNo).isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        // 快照由 open 在「登记完连接」之后才取：判定可订阅到登记完成之间若恰好收口，也不会让这条连接空等
        return broadcaster.open(new TaskRunRef(taskNo, runNo),
                () -> queryService.renderSnapshot(taskNo, runNo));
    }

    /**
     * 按需触发字幕翻译：提交后的自动管线只跑到清洗 / 原文字幕为止，
     * 用户点「翻译」才触发——不看翻译的人不花 LLM 钱。
     *
     * @param taskNo  提交响应返回的任务号
     * @param request 源语种与目标语种（两个都过白名单，白名单外 400）
     * @return 202 与本次触发的 {@code {taskNo, runNo}}
     * @throws IllegalArgumentException                                     taskNo 为空（400）、语种不在白名单（400）或字幕尚未就绪（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     * @throws com.vidsage.application.task.exception.TaskConflictException 该任务已有一次执行在跑（409）
     */
    @Operation(summary = "触发字幕翻译（按需）", description = "清洗产物就绪后可触发；body 带源语种与目标语种（都过白名单，白名单外 400）；202 返回 {taskNo, runNo}。"
            + "该任务已有一次执行在跑返回 409（同形状）；清洗字幕尚未就绪返回 400")
    @PostMapping("/{taskNo}/translate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskRunRefResponse translate(@PathVariable String taskNo,
                                        @RequestBody TranslationRequest request) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        log.info("触发字幕翻译: taskNo={}", taskNo);
        return TaskRunRefResponse.from(
                taskService.translate(taskNo, request.sourceLanguage(), request.targetLanguage()));
    }

    /**
     * 重新生成字幕：按请求里的关键词重跑清洗。
     * 同步判定（404 / 400 未就绪 / 409 互斥 / 400 关键词超限）在服务层完成，
     * 202 返回 {@code {taskNo, runNo}}，进度另走 SSE。
     *
     * @param taskNo  提交响应返回的任务号
     * @param request 关键词清单；<b>{@code null}（字段缺席）= 不改变已有列表，按现有关键词重跑；
     *                {@code []}（显式空数组）= 清空后重跑</b>
     * @return 202 与本次触发的 {@code {taskNo, runNo}}
     * @throws IllegalArgumentException                                     taskNo / 关键词超限（400）、字幕尚未就绪（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     * @throws com.vidsage.application.task.exception.TaskConflictException 该任务已有一次执行在跑（409）
     */
    @Operation(summary = "重新生成字幕（改关键词后重跑清洗）", description = "清洗产物就绪后可触发；202 返回 {taskNo, runNo}，进度走 SSE。"
            + "keywords 为 null（字段缺席）= 不改变已有列表，[]（显式空数组）= 清空，两者语义不同。"
            + "单条最多 50 字、最多 50 条，超限 400 且不落库不重跑；"
            + "该任务已有一次执行在跑时 409；字幕尚未就绪时 400")
    @PostMapping("/{taskNo}/subtitles/regenerate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskRunRefResponse regenerate(@PathVariable String taskNo,
                                         @RequestBody RegenerateSubtitlesRequest request) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        log.info("重新生成字幕: taskNo={}", taskNo);
        return TaskRunRefResponse.from(
                taskService.regenerate(taskNo, request == null ? null : request.keywords()));
    }

    /**
     * 读回该任务的关键词清单与指纹：前端拿指纹与产物里的 {@code keywordsFingerprint} 比对，
     * 就知道「这份字幕对不对得上当前关键词」。
     *
     * @param taskNo 提交响应返回的任务号
     * @return 关键词清单（用户填写顺序）与指纹；从没填过时为空清单
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     */
    @Operation(summary = "查询关键词清单与指纹", description = "按 taskNo 返回该任务的关键词清单（用户填写顺序）与指纹；不存在的任务返回 404")
    @GetMapping("/{taskNo}/keywords")
    public SubtitleKeywordsResponse findKeywords(@PathVariable String taskNo) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        return SubtitleKeywordsResponse.from(queryService.findKeywords(taskNo));
    }
}
