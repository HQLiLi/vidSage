package com.vidsage.provider.download;

import com.vidsage.model.media.AcquiredMedia;
import com.vidsage.model.media.MediaInput;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 媒体输入获取抽象：把一条 {@link MediaInput} 变成工作目录里的一份本地媒体文件 + 元数据。
 *
 * <p><b>两条路线由输入子类型决定，不由配置决定</b>，因此两个实现同时驻留容器（区别于
 * provider-pattern.md 里「同能力、配置二选一」的 {@code @ConditionalOnProperty} 形态）；
 * 具体选哪个由上层服务按 {@link #supports} 分派，接口自身不含选择逻辑。
 *
 * <p><b>刻意不收进度监听参数</b>：阶段内进度（下载百分比）不在本阶段范围，
 * 阶段事件由上层服务在调用前后发出（RUNNING / SUCCEEDED / SKIPPED / FAILED）。
 * 将来确实需要阶段内进度时再加——那时监听契约位于 {@code model/} 下，
 * 不会构成 provider 反向依赖 service 的分层倒置。
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
     * <p><b>这个判断必须由适配器回答，而非上层服务自己判「文件在不在」</b>：产物文件名
     * （如 {@code source.<ext>}）与元数据（标题、时长）都藏在 info json 里，解析它们的知识属于
     * 适配器；上层若自己 glob 目录并重新解析 json，等于把适配器的知识复制一份到服务层。
     * 上层因此只知道「可能有已存在的产物」这个<b>概念</b>，不知道具体文件名。
     *
     * <p>判据统一为「<b>文件存在且大小 &gt; 0</b>」：0 字节（下载中断留下的空壳）一律视为不存在。
     * <b>本方法不触发任何外部命令</b>（本地路线没有可复用的下载产物，故恒返回空）。
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
     * 获取媒体文件与元数据。远程路线下载到 {@code workDir}，本地路线直接采用不拷贝。
     *
     * @param input   媒体输入，须为 {@link #supports} 认可的子类型
     * @param workDir 任务工作目录；远程路线用于落盘下载物与 info json
     * @return 媒体文件路径 + 标题 + 时长 + （远程路线的）info json 路径
     * @throws IllegalArgumentException 入参不合法（输入为空 / 本实现不支持 / 本地文件不存在、不可读 / 工作目录不可用）
     * @throws IllegalStateException    外部工具缺失，或外部命令未按预期完成、产物无法定位
     */
    AcquiredMedia acquire(MediaInput input, Path workDir);
}
