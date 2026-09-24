package com.vidsage.asr;

import com.vidsage.asr.model.AudioSource;
import com.vidsage.asr.model.TranscriptionCommand;
import com.vidsage.asr.model.TranscriptionResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 语音识别服务层：对上屏蔽 {@link ASRProvider} 的装配细节，
 * 把「音频 URL + 可选语种」组装为统一转写命令后委托当前配置的 Provider
 * （Spring 按 vidsage.asr.provider 自动装配唯一的实现 Bean）。
 */
@Component
public class TranscriptionService {

    private final ASRProvider asrProvider;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     */
    public TranscriptionService(ASRProvider asrProvider) {
        this.asrProvider = asrProvider;
    }

    /**
     * 转写一段公网可访问的音频，同步阻塞直到转写完成
     * （虚拟线程已全局开启，阻塞等待不占用平台线程）。
     *
     * @param audioUrl 公网可访问的音频 URL
     * @param language 可选语种提示（如 "zh"）；语种不确定或多语种混合时传 {@code null}
     * @return 句级毫秒时间戳的转写结果，segments 不为 {@code null}
     * @throws IllegalArgumentException audioUrl 为空白
     * @throws IllegalStateException 配置缺失或外部转写服务未按预期返回
     */
    public TranscriptionResult transcribe(String audioUrl, String language) {
        if (!StringUtils.hasText(audioUrl)) {
            throw new IllegalArgumentException(
                    "audioUrl must not be blank: pass a publicly accessible audio URL for cloud transcription");
        }
        return asrProvider.transcribe(new TranscriptionCommand(new AudioSource.AudioUrl(audioUrl), language));
    }
}
