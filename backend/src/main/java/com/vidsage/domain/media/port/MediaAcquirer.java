package com.vidsage.domain.media.port;

import com.vidsage.domain.media.model.AcquiredMedia;
import com.vidsage.domain.media.model.MediaInput;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 媒体文件获取器：把一条 {@link MediaInput} 变成工作目录里的一份可读媒体文件，外加标题和时长。
 * 产出的是<b>完整媒体文件（带视频轨）</b>，不是音频——抽音频是 {@code AudioExtractor} 的事。
 *
 * <p>走哪条路线由<b>输入子类型</b>定，不由配置定：各实现的 {@link #supports} <b>互斥</b>，
 * 同一输入只有一个返回 true，选谁由上层服务按它分派。<b>实现不得用配置开关决定装配</b>
 * ——那会造出「没有一个实现能接」的空档，而分派逻辑假定总有一个能接。
 *
 * <p>本接口<b>不收进度监听</b>：阶段内进度不在本阶段范围，阶段事件由上层服务在调用前后发出。
 */
public interface MediaAcquirer {

    /**
     * 本实现是否处理该输入。
     *
     * @param input 媒体输入；为 {@code null} 时返回 {@code false}
     * @return 支持返回 {@code true}
     */
    boolean supports(MediaInput input);

    /**
     * 判断 {@code workDir} 里是否已有本实现上一轮留下的<b>完整</b>产物，有则直接复用、不重复下载。
     *
     * <p><b>这个判断必须由适配器回答，而非上层服务自己判「文件在不在」</b>：产物文件名与元数据藏在
     * info json 里，解析它们的知识属于适配器——上层若自己 glob 目录再解析一遍，等于把它复制一份到
     * 服务层。判据统一为「<b>文件存在且大小 &gt; 0</b>」，0 字节（下载中断的空壳）视为不存在；
     * <b>本方法不触发任何外部命令</b>，本地路线没有可复用的下载产物，故恒返回空。
     *
     * @param input   媒体输入，须为 {@link #supports} 认可的子类型
     * @param workDir 任务工作目录
     * @return 可复用的媒体元数据；产物不存在或不可用时返回 {@link Optional#empty()}（此时调用方应改调
     *         {@link #acquire}）
     * @throws IllegalArgumentException 入参不合法（输入为空 / 本实现不支持 / 工作目录不可用）
     * @throws IllegalStateException    工作目录无法读取等环境问题
     */
    Optional<AcquiredMedia> reuseExisting(MediaInput input, Path workDir);

    /**
     * 获取媒体文件与元数据。返回的路径<b>不一定在 {@code workDir} 里</b>——
     * 本地路线的输入文件是直接采用、不拷贝的。
     *
     * @param input   媒体输入，须为 {@link #supports} 认可的子类型
     * @param workDir 任务工作目录；远程路线用于落盘下载物与 info json
     * @return 媒体文件路径 + 标题 + 时长 + （远程路线的）info json 路径
     * @throws IllegalArgumentException 入参不合法（输入为空 / 本实现不支持 / 本地文件不存在、不可读 / 工作目录不可用）
     * @throws IllegalStateException    外部工具缺失，或外部命令未按预期完成、产物无法定位
     */
    AcquiredMedia acquire(MediaInput input, Path workDir);
}
