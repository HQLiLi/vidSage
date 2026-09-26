package com.vidsage.service;

import com.vidsage.model.media.AcquiredMedia;
import com.vidsage.model.media.MediaInput;
import com.vidsage.provider.download.MediaAcquirer;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 输入获取的分派服务：按 {@link MediaInput} 子类型挑出支持的 {@link MediaAcquirer} 并委托。
 *
 * <p><b>两个适配器同时驻留容器</b>（由输入类型决定用哪个，不由配置决定），选择逻辑收敛在这里，
 * 因此 {@code service/} 层不出现任何实现类名（{@code YtDlpMediaAcquirer} / {@code LocalFileMediaAcquirer}
 * 都不许 import）。
 *
 * <p>{@link #reuseExisting} 与 {@link #acquire} 一一对应适配器接口的两个方法：前者只回答
 * 「产物是否已存在且可复用」，不会触发下载。
 */
@Slf4j
@Service
public class MediaAcquisitionService {

    private final List<MediaAcquirer> acquirers;

    /**
     * 构造函数注入全部适配器实现，保证可测试性（见 quality-guidelines）。
     *
     * @param acquirers 容器里的全部媒体获取适配器；两个实现的 {@code supports} 互斥
     */
    public MediaAcquisitionService(List<MediaAcquirer> acquirers) {
        this.acquirers = List.copyOf(acquirers);
    }

    /**
     * 判断该输入在工作目录里是否已有可复用的媒体产物。
     *
     * @param input   媒体输入
     * @param workDir 任务工作目录
     * @return 已存在且可复用的媒体元数据；没有则返回 {@link Optional#empty()}
     * @throws IllegalArgumentException 输入为空或工作目录不可用（由选中的适配器判定）
     * @throws IllegalStateException    没有适配器支持该输入，或适配器无法读取产物
     */
    public Optional<AcquiredMedia> reuseExisting(MediaInput input, Path workDir) {
        MediaAcquirer acquirer = select(input);
        Optional<AcquiredMedia> existing = acquirer.reuseExisting(input, workDir);
        log.debug("媒体复用检查: acquirer={}, reused={}",
                acquirer.getClass().getSimpleName(), existing.isPresent());
        return existing;
    }

    /**
     * 真正获取一次媒体文件与元数据（远程路线会下载）。
     *
     * @param input   媒体输入，须为某个适配器支持的类型
     * @param workDir 任务工作目录
     * @return 媒体文件路径 + 标题 + 时长 + 可选的 info json 路径
     * @throws IllegalArgumentException 输入为空或输入内容不合法
     * @throws IllegalStateException    没有适配器支持该输入，或外部工具 / 命令未按预期完成
     */
    public AcquiredMedia acquire(MediaInput input, Path workDir) {
        MediaAcquirer acquirer = select(input);
        log.debug("委托媒体获取: acquirer={}", acquirer.getClass().getSimpleName());
        return acquirer.acquire(input, workDir);
    }

    /**
     * 按 {@link MediaAcquirer#supports} 选中唯一的适配器。
     *
     * @throws IllegalArgumentException 输入为 {@code null}
     * @throws IllegalStateException    没有任何适配器支持该输入类型
     */
    private MediaAcquirer select(MediaInput input) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }
        return acquirers.stream()
                .filter(acquirer -> acquirer.supports(input))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有可处理该输入的媒体获取适配器：输入类型 "
                        + input.getClass().getSimpleName()
                        + "；请确认已注册对应的 MediaAcquirer 实现（远程 URL 走 yt-dlp 路线，本地文件走本地路线）"));
    }
}
