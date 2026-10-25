package com.vidsage.web.controller;

import com.vidsage.application.media.MediaSourceQueryService;
import com.vidsage.application.media.query.MediaSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务源媒体的字节出口：把工作区里的视频 / 音频原样发给浏览器播放。
 *
 * <p>与 {@link MediaController}（外部工具诊断）分两个类：那边返回诊断 DTO，这边返回文件字节与
 * Range 语义，两类职责不混。
 *
 * <p><b>为什么直接写 {@link HttpServletResponse} 而不是返回 {@code ResponseEntity<Resource>}</b>：
 * Spring 对「返回 Resource 且请求带 Range 头」会自动接管成 206 / 416。本端点要自己决定
 * 「多段与语法非法的 Range 一律当没有 Range 处理」，交给框架会把这两种请求改写成 206 或 416。
 * 直接写响应才能让每条分支都可预期。
 *
 * <p>本类只做「接收 → 卫语句 → 委托 → 组装响应」：文件在哪、多大、什么格式由
 * {@link MediaSourceQueryService} 给，这里只管 HTTP 语义（状态码、Range、响应头）。
 */
@Slf4j
@Tag(name = "媒体源", description = "任务源媒体的字节出口：全量、单段 Range 与 HEAD")
@RestController
@RequestMapping("/api/media/source")
public class MediaSourceController {

    private final MediaSourceQueryService queryService;

    public MediaSourceController(MediaSourceQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 取该任务的源媒体字节。{@code GET} 与 {@code HEAD} 共用这一个入口，{@code HEAD} 只回头不回体。
     *
     * <ul>
     *   <li>无 Range / Range 语法非法 / 多段：{@code 200} + 整个文件；</li>
     *   <li>单段 Range 可满足：{@code 206} + 区间字节，带 {@code Content-Range}；</li>
     *   <li>单段 Range 越界：{@code 416} + 只报总长的 {@code Content-Range}；</li>
     *   <li>{@code HEAD}：忽略 Range，按整文件回同样的头、不写体。</li>
     * </ul>
     *
     * @param taskNo      提交任务时响应里的任务号
     * @param rangeHeader 浏览器 / 播放器发来的 Range 头，可为空
     * @param request     只为判断是不是 HEAD
     * @param response    字节与响应头直接写在这里
     * @throws IllegalArgumentException                                     taskNo 为空（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     * @throws com.vidsage.application.media.exception.MediaSourceNotFoundException 没有可播放的媒体源（404）
     * @throws IOException                                                  读文件或写响应的 IO 失败
     */
    @Operation(summary = "播放任务的源媒体",
            description = "返回源媒体文件字节，支持单段 Range（拖动进度会走到 206）。"
                    + "多段或语法非法的 Range 一律按无 Range 处理返回 200；越界返回 416。"
                    + "HEAD 忽略 Range，按整文件回头、不返回体。任务不存在、任务还没有媒体源或文件已被回收返回 404。")
    @GetMapping("/{taskNo}")
    public void serveSource(@PathVariable String taskNo,
                            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        MediaSource source = queryService.locate(taskNo);
        Path path = source.path();
        long size = source.sizeBytes();
        boolean head = isHead(request);
        log.info("请求媒体源: taskNo={}, file={}, range={}", taskNo, source.fileName(),
                rangeHeader == null ? "全量" : rangeHeader);

        // HEAD 忽略 Range：RFC 9110 §14.2 只给 GET 定义了区间语义，HEAD 要的是「GET 会回的头」，
        // 直接按整文件回 200，别让 Content-Length 变成某个区间的长度
        HttpRange range = head ? null : singleRange(rangeHeader);
        if (range != null) {
            long start = range.getRangeStart(size);
            long end = range.getRangeEnd(size);
            if (start >= size || start > end) {
                response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
                response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                response.setContentLength(0);
                return;
            }
            // 先开流再写头：文件在 stat 之后被删 / 被占用时，异常发生在响应被改动之前，
            // 全局处理器还能回统一的 { "error": ... }，不会留下「200 + 文件长度 + 错误体」的半成品
            try (InputStream in = openSource(path)) {
                writePartialHeaders(response, source, size, start, end);
                StreamUtils.copyRange(in, response.getOutputStream(), start, end);
            }
            return;
        }

        if (head) {
            writeWholeFileHeaders(response, source, size);
            return;
        }
        try (InputStream in = openSource(path)) {
            writeWholeFileHeaders(response, source, size);
            StreamUtils.copy(in, response.getOutputStream());
        }
    }

    /**
     * 整文件响应的头：200 + 完整长度。
     */
    private static void writeWholeFileHeaders(HttpServletResponse response, MediaSource source, long size) {
        writeCommonHeaders(response, source);
        response.setStatus(HttpStatus.OK.value());
        response.setContentLengthLong(size);
    }

    /**
     * 单段区间响应的头：206 + 区间长度与 {@code Content-Range}。
     */
    private static void writePartialHeaders(HttpServletResponse response, MediaSource source,
                                            long size, long start, long end) {
        writeCommonHeaders(response, source);
        response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
        response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
        response.setContentLengthLong(end - start + 1);
    }

    /**
     * 打开媒体文件；打不开时转成应用层语义的异常，让全局处理器回统一的错误体。
     *
     * <p>只带文件名、不带绝对路径：目录名内嵌身份键，属脱敏红线。
     *
     * @param path 媒体文件路径
     * @return 可读的文件流
     * @throws IllegalStateException 文件读不到（被删、被占用等）
     */
    private static InputStream openSource(Path path) {
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new IllegalStateException("读取媒体文件失败：" + path.getFileName()
                    + "；文件可能刚被删除或被其它进程占用，请重试或重新生成后再试；原因："
                    + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 成功播放时两种状态码共用的响应头。
     *
     * <p>{@code no-cache} 而不是强缓存：地址只按 taskNo 拼，同一任务重跑可能换掉文件，
     * 让浏览器每次回源验证才不会播到旧缓存。本端点不提供 ETag / Last-Modified。
     */
    private static void writeCommonHeaders(HttpServletResponse response, MediaSource source) {
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setContentType(source.contentType());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(source.fileName()).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
    }

    /**
     * 取出唯一一段 Range；没带、语法非法、多段都返回 {@code null}（调用方按整个文件处理）。
     *
     * <p>多段按无 Range 处理是 RFC 允许的忽略方式，浏览器实际只发单段；语法非法的多半是探测工具，
     * 不值得打成 400。
     */
    private static HttpRange singleRange(String rangeHeader) {
        if (!StringUtils.hasText(rangeHeader)) {
            return null;
        }
        List<HttpRange> ranges;
        try {
            ranges = HttpRange.parseRanges(rangeHeader);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return ranges.size() == 1 ? ranges.getFirst() : null;
    }

    /**
     * 是不是 HEAD 请求：HEAD 只回头不回体。
     */
    private static boolean isHead(HttpServletRequest request) {
        return HttpMethod.HEAD.matches(request.getMethod());
    }
}
