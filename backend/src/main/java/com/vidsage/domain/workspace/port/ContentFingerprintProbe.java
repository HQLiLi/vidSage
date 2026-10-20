package com.vidsage.domain.workspace.port;

import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.workspace.model.ContentFingerprint;
import java.util.Optional;

/**
 * 内容指纹探测：只读媒体元数据，算出它的时长与发布时间。
 *
 * <p>只回答「这条输入的元数据是什么」，<b>不下载媒体、不落任何文件</b>；产出媒体文件是
 * {@code MediaAcquirer} 的事。哪些输入支持探测由各实现的 {@link #supports} 回答：
 * 只有远程路线拿得到绝对发布时间，上传 / 本地路线没有这个维度。
 */
public interface ContentFingerprintProbe {

    /**
     * 本实现是否能探测该输入。
     *
     * @param input 媒体输入；为 {@code null} 时返回 {@code false}
     * @return 支持探测返回 {@code true}
     */
    boolean supports(MediaInput input);

    /**
     * 探测一条输入的内容指纹。
     *
     * <p><b>取不到不是失败</b>：超时、非零退出、元数据缺字段、输出解析不了，一律返回
     * {@link Optional#empty()}，由调用方回落「有产物就复用」并记 WARN。不能把取不到当异常抛出去，
     * 否则一次元数据风控就会把整条主管线判死。
     *
     * @param input 媒体输入，须为 {@link #supports} 认可的类型
     * @return 时长 + 发布时间；取不到时为 {@link Optional#empty()}
     * @throws IllegalArgumentException 输入为空或不是本实现支持的类型
     * @throws IllegalStateException    环境问题（外部工具缺失等）——调用方按降级处理
     */
    Optional<ContentFingerprint> probe(MediaInput input);
}
