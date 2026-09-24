package com.vidsage.service;

import com.vidsage.model.asr.AudioSource;
import com.vidsage.model.asr.TranscriptionCommand;
import com.vidsage.model.asr.TranscriptionResult;
import com.vidsage.provider.asr.ASRProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 语音识别服务层：对上屏蔽 {@link ASRProvider} 的装配细节，
 * 把「音频来源 + 可选语种」组装为统一转写命令后委托当前配置的 Provider
 * （Spring 按 vidsage.asr.provider 自动装配唯一的实现 Bean）。
 */
@Service
public class TranscriptionService {

    private final ASRProvider asrProvider;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     */
    public TranscriptionService(ASRProvider asrProvider) {
        this.asrProvider = asrProvider;
    }

    /**
     * 转写一段音频，同步阻塞直到转写完成
     * （虚拟线程已全局开启，阻塞等待不占用平台线程）。
     *
     * <p>音频来源可以是公网 URL，也可以是本服务可读的本地文件路径
     * （本地文件由云端实现先上传到临时存储再转写）。
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
                    "audio source must not be null: pass either audioUrl or audioFilePath");
        }
        return asrProvider.transcribe(new TranscriptionCommand(source, language));
    }
}
