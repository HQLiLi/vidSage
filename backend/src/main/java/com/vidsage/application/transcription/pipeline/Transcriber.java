package com.vidsage.application.transcription.pipeline;

import com.vidsage.domain.transcription.model.AudioSource;
import com.vidsage.domain.transcription.model.TranscriptionCommand;
import com.vidsage.domain.transcription.model.TranscriptionResult;
import com.vidsage.domain.transcription.port.ASRProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 语音识别阶段：对上屏蔽 {@link ASRProvider} 的装配细节，
 * 把「音频来源 + 可选语种」组装为统一转写命令后委托给当前装配的 Provider。
 */
@Slf4j
@Component
public class Transcriber {

    private final ASRProvider asrProvider;

    public Transcriber(ASRProvider asrProvider) {
        this.asrProvider = asrProvider;
    }

    /**
     * 转写一段音频，同步阻塞直到转写完成
     * （虚拟线程已全局开启，阻塞等待不占用平台线程）。
     *
     * <p>音频来源可以是公网 URL，也可以是本进程可读的本地文件路径。
     *
     * @param source   音频来源（{@link AudioSource.AudioUrl} 或 {@link AudioSource.AudioFilePath}）
     * @param language 可选语种提示（如 "zh"）；语种不确定或多语种混合时传 {@code null}
     * @return 句级毫秒时间戳的转写结果，segments 不为 {@code null}
     * @throws IllegalArgumentException 音频来源为空或来源值不合法
     * @throws IllegalStateException    配置缺失、上传失败或外部转写服务未按预期返回
     */
    public TranscriptionResult transcribe(AudioSource source, String language) {
        if (source == null) {
            throw new IllegalArgumentException(
                    "音频来源不能为空：请传 audioUrl 或 audioFilePath");
        }
        // 阶段节点用 DEBUG：与 Controller 的 INFO 分层，用于区分「框架/编排耗时」与「Provider 实际耗时」
        log.debug("委托转写: provider={}, language={}", asrProvider.getClass().getSimpleName(), language);
        long start = System.currentTimeMillis();
        TranscriptionResult result = asrProvider.transcribe(new TranscriptionCommand(source, language));
        log.debug("转写返回: provider={}, segments={}, cost={}ms",
                asrProvider.getClass().getSimpleName(), result.segments().size(),
                System.currentTimeMillis() - start);
        return result;
    }
}
