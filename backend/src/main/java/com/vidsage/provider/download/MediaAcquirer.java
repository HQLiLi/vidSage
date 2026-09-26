package com.vidsage.provider.download;

import com.vidsage.model.media.AcquiredMedia;
import com.vidsage.model.media.MediaInput;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 媒体文件获取器接口（目前分为本地和链接【走yt-dlp插件获取】）。
 *
 * <p>干的就一件事：把一条 {@link MediaInput} 变成工作目录里的一份可读媒体文件，外加标题和时长。
 * 产出的是<b>完整媒体文件（带视频轨）</b>，不是音频——抽音频是下一阶段 {@code AudioExtractor} 的事。
 *
 * <p>走哪条路线由<b>输入子类型</b>定，不由配置定：远程 URL 走 yt-dlp 下载，本地路径直接采用。
 * 所以两个实现同时待在容器里、都不要加 {@code @ConditionalOnProperty}，选谁由上层服务按
 * {@link #supports} 分派；两个实现的 {@code supports} <b>互斥</b>，同一输入只有一个返回 true。
 *
 * <p>本接口<b>不收进度监听</b>：下载百分比这类阶段内进度不在本阶段范围，阶段事件由上层服务
 * 在调用前后发出（RUNNING / SUCCEEDED / SKIPPED / FAILED）。
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
