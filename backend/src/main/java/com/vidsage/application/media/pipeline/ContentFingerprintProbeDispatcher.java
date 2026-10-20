package com.vidsage.application.media.pipeline;

import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.workspace.model.ContentFingerprint;
import com.vidsage.domain.workspace.port.ContentFingerprintProbe;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 内容指纹探测的分派：按输入子类型挑出支持的 {@link ContentFingerprintProbe} 并委托。
 *
 * <p>与媒体获取的分派同形：实现类都驻留容器，选谁由输入类型决定，因此 {@code application/}
 * 层不出现任何实现类名。
 *
 * <p><b>「没有实现支持」不是错误</b>：只有远程路线拿得到绝对发布时间，上传 / 本地路线本来就没有
 * 这个维度。{@link #supports} 返回 {@code false} 时调用方应直接跳过探测，而不是当成取不到指纹。
 */
@Component
public class ContentFingerprintProbeDispatcher {

    private final List<ContentFingerprintProbe> probes;

    /**
     * @param probes 容器里的全部探测实现；各实现的 {@code supports} 互斥，故取到的总是唯一那个
     */
    public ContentFingerprintProbeDispatcher(List<ContentFingerprintProbe> probes) {
        this.probes = List.copyOf(probes);
    }

    /**
     * 这条输入有没有可用的探测实现。
     *
     * @param input 媒体输入；为 {@code null} 时返回 {@code false}
     * @return 有实现支持时返回 {@code true}
     */
    public boolean supports(MediaInput input) {
        return select(input).isPresent();
    }

    /**
     * 探测一条输入的内容指纹。
     *
     * <p>与 {@link #supports} 配合使用：输入为空或没有实现支持都返回空，<b>不抛异常</b>——上传 / 本地
     * 路线本来就没有这一维，那是正常形态而不是错误。
     *
     * @param input 媒体输入
     * @return 内容指纹；没有实现支持或实现取不到时为空
     * @throws IllegalArgumentException 底层实现拒绝该输入（例如 URL 为空白）
     * @throws IllegalStateException    环境问题（外部工具缺失等）——调用方按降级处理
     */
    public Optional<ContentFingerprint> probe(MediaInput input) {
        return select(input).flatMap(probe -> probe.probe(input));
    }

    /**
     * 按 {@link ContentFingerprintProbe#supports} 选中唯一的探测实现。
     */
    private Optional<ContentFingerprintProbe> select(MediaInput input) {
        if (input == null) {
            return Optional.empty();
        }
        return probes.stream().filter(probe -> probe.supports(input)).findFirst();
    }
}
