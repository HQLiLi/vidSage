package com.vidsage.controller;

import com.vidsage.service.TranscriptionService;
import com.vidsage.model.asr.AudioSource;
import com.vidsage.model.asr.TranscriptionResult;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 语音识别 HTTP 入口：接收音频 URL 或本地文件路径发起同步转写并直接返回结果
 * （虚拟线程已全局开启，同步阻塞等待不占用平台线程）。
 */
@Slf4j
@RestController
@RequestMapping("/api/asr")
public class AsrController {

    private final TranscriptionService transcriptionService;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     */
    public AsrController(TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    /**
     * 发起一次转写。
     *
     * @param request 音频来源（audioUrl 与 audioFilePath 恰好传一个）与可选语种提示
     * @return 句级分段与识别出的语种
     * @throws IllegalArgumentException audioUrl 与 audioFilePath 都传、都不传或为空白（映射为 400）
     */
    @PostMapping("/transcriptions")
    public TranscriptionResult transcribe(@RequestBody TranscriptionRequest request) {
        boolean hasUrl = StringUtils.hasText(request.audioUrl());
        boolean hasPath = StringUtils.hasText(request.audioFilePath());
        if (hasUrl && hasPath) {
            throw new IllegalArgumentException(
                    "audioUrl 与 audioFilePath 不能同时传：请恰好传其中一个");
        }
        if (!hasUrl && !hasPath) {
            throw new IllegalArgumentException(
                    "缺少音频来源：请传 audioUrl（公网可访问的音频 URL）或 audioFilePath（服务端可读的本地音频文件路径）");
        }
        AudioSource source = hasUrl
                ? new AudioSource.AudioUrl(request.audioUrl())
                : new AudioSource.AudioFilePath(request.audioFilePath());
        // 入口日志只记「请求形态」：URL 可能带签名查询参数、路径指向用户文件，原值一律不落日志；
        // 实际使用的音频地址由 Provider 层脱敏后记录
        log.info("收到转写请求: sourceType={}, language={}",
                source.getClass().getSimpleName(), request.language());
        long start = System.currentTimeMillis();
        TranscriptionResult result = transcriptionService.transcribe(source, request.language());
        log.info("转写请求完成: segments={}, language={}, cost={}ms",
                result.segments().size(), result.language(), System.currentTimeMillis() - start);
        return result;
    }

    /**
     * 把入参不合法映射为 400，错误信息说明「缺什么、怎么补」。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("转写请求参数不合法: {}", e.getMessage());
        return Map.of("error", e.getMessage());
    }

    /**
     * 转写请求体。
     *
     * @param audioUrl      公网可访问的音频 URL；与 audioFilePath 恰好传一个
     * @param audioFilePath 服务端可读的本地音频文件路径（云端实现会先上传到临时存储）；与 audioUrl 恰好传一个
     * @param language      可选语种提示（如 "zh"）
     */
    record TranscriptionRequest(String audioUrl, String audioFilePath, String language) {
    }
}
