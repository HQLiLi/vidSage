package com.vidsage.web.controller;

import com.vidsage.model.media.MediaInput;
import com.vidsage.model.subtitle.CleanedSubtitle;
import com.vidsage.model.subtitle.SubtitleArtifact;
import com.vidsage.service.SubtitleCleanupService;
import com.vidsage.service.SubtitleService;
import com.vidsage.web.dto.CleanedSubtitleResponse;
import com.vidsage.web.dto.PrepareSubtitleRequest;
import com.vidsage.web.dto.PreparedSubtitleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 字幕入口：把「一条 URL 或本地媒体文件」变成「一份 SRT 字幕 + 一份 segments JSON」（同步）。
 *
 * <p><b>临时面（design §七）</b>：{@code POST /api/subtitles} 是<b>同步</b>端点，它存在的唯一
 * 理由是在 MVP-1（任务提交 / 落库 / SSE）尚未落地时，提供一个能被真实调用的端到端入口。同步端点
 * 在「本地文件 + 短视频」下可用，<b>长视频会顶到 HTTP 客户端超时</b>；真正的形态是 MVP-1 的
 * 异步管线——<b>该管线落地后本端点应改为它的一个触发入口或直接删除</b>。
 *
 * <p>因此本控制器<b>不引入任务 ID、不落库、不排队</b>：那些全是 MVP-1 的范围。
 *
 * <p>本类只做「接收 → 校验 → 委托 → 返回」，不写业务逻辑；依赖 {@code service/}。
 */
@Slf4j
@Tag(name = "字幕", description = "URL 或本地文件 → SRT 字幕 + segments JSON（同步，临时面）")
@RestController
@RequestMapping("/api/subtitles")
public class SubtitleController {

    private final SubtitleService subtitleService;
    private final SubtitleCleanupService subtitleCleanupService;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param subtitleService        字幕编排（产字幕）
     * @param subtitleCleanupService 字幕清洗（切分 + 纠错）
     */
    public SubtitleController(SubtitleService subtitleService,
                              SubtitleCleanupService subtitleCleanupService) {
        this.subtitleService = subtitleService;
        this.subtitleCleanupService = subtitleCleanupService;
    }

    /**
     * 由 URL 或本地文件同步产出一份字幕。
     *
     * @param request 媒体来源（url 与 localPath 只能传一个）
     * @return 两个产物路径 + 路线 + 语种 + 句数 + SRT 字节数
     * @throws IllegalArgumentException url 与 localPath 都传 / 都不传 / 为空白、localPath 非法路径
     *                                  （均映射为 400）
     * @throws IllegalStateException    媒体获取 / 抽音频 / 转写 / 产物写入失败（映射为 500）
     */
    @Operation(summary = "由 URL 或本地文件产出一份字幕",
            description = "同步执行：url 与 localPath 只能传一个；产出 SRT + segments JSON。"
                    + "长视频可能顶到客户端超时（临时面，见类注释）")
    @PostMapping
    public PreparedSubtitleResponse prepare(@RequestBody PrepareSubtitleRequest request) {
        MediaInput input = toMediaInput(request);
        // 入口日志只记「请求形态」：URL 可能带签名查询参数、路径指向用户文件，原值一律不落日志
        log.info("收到产字幕请求: sourceType={}", input.getClass().getSimpleName());
        long start = System.currentTimeMillis();
        SubtitleArtifact artifact = subtitleService.prepare(input);
        log.info("产字幕请求完成: route={}, segments={}, srtSizeBytes={}, cost={}ms",
                artifact.route().name(), artifact.segmentCount(), artifact.srtSizeBytes(),
                System.currentTimeMillis() - start);
        return PreparedSubtitleResponse.from(artifact);
    }

    /**
     * 清洗一份已有字幕：读 2b 产出的原始 segments，切分超长段 + LLM 纠错，产出 {@code -clean} 产物。
     *
     * <p>前提：必须已对同一输入跑过 {@code POST /api/subtitles}，否则报「缺原始字幕」。
     * 同步端点（临时面，与 {@code prepare} 同源：MVP-1 异步管线落地后一并改或删）。
     *
     * @param request   媒体来源（url 与 localPath 只能传一个，与产字幕同源）
     * @param reference 可选参考 / 术语表（纠正误识别术语）
     * @return 清洗产物（-clean 路径 + 语种 + 句数 + 修正/重试统计）
     * @throws IllegalArgumentException 媒体来源歧义 / 为空
     * @throws IllegalStateException    原始字幕缺失（先跑产字幕）、解析或写入失败
     */
    @Operation(summary = "清洗一份已有字幕（切分超长段 + LLM 纠错）",
            description = "同步执行：需先对同一输入调用 POST /api/subtitles 产出原始字幕；"
                    + "产出 segments-<route>-clean.json 与 subtitles-<route>-clean.srt 两条 -clean 产物。"
                    + "不再覆盖原始产物。reference 为可选术语表。")
    @PostMapping("/clean")
    public CleanedSubtitleResponse clean(@RequestBody PrepareSubtitleRequest request,
                                         @RequestParam(required = false) String reference) {
        MediaInput input = toMediaInput(request);
        log.info("收到字幕清洗请求: sourceType={}", input.getClass().getSimpleName());
        long start = System.currentTimeMillis();
        CleanedSubtitle cleaned = subtitleCleanupService.clean(input, reference);
        log.info("字幕清洗请求完成: route={}, segments={}, corrected={}, retry={}, cost={}ms",
                cleaned.route().name(), cleaned.segmentCount(),
                cleaned.correctedCount(), cleaned.retrySteps(),
                System.currentTimeMillis() - start);
        return CleanedSubtitleResponse.from(cleaned);
    }

    /**
     * 请求体 → 媒体输入：{@code url} 与 {@code localPath} <b>必须且只能有一个</b>。
     *
     * <p>照 {@code MediaController.toMediaInput} 的写法，卫语句全部排在本边界、早于一切服务调用
     * （error-handling.md 的硬规则）：否则参数错误会被前面的下载 / 抽音频失败掩盖成 500。
     *
     * @param request 产字幕请求体
     * @return 远程 URL 或本地路径输入
     * @throws IllegalArgumentException 两个都传、都不传或为空白；localPath 不是合法路径
     */
    private static MediaInput toMediaInput(PrepareSubtitleRequest request) {
        boolean hasUrl = StringUtils.hasText(request.url());
        boolean hasPath = StringUtils.hasText(request.localPath());
        if (hasUrl && hasPath) {
            throw new IllegalArgumentException("url 与 localPath 不能同时传：请恰好传其中一个");
        }
        if (!hasUrl && !hasPath) {
            throw new IllegalArgumentException(
                    "缺少媒体来源：请传 url（公网媒体页面地址）或 localPath（服务端可读的本地媒体文件路径）");
        }
        if (hasUrl) {
            return new MediaInput.RemoteUrl(request.url());
        }
        try {
            return new MediaInput.LocalPath(Path.of(request.localPath()));
        } catch (InvalidPathException e) {
            // Path.of 抛的是携带英文原因的 JDK 异常；这里换成「缺什么 + 怎么补」的中文消息
            throw new IllegalArgumentException("localPath 不是合法路径：" + request.localPath()
                    + "；请检查路径写法（Windows 下 < > : \" | ? * 为非法字符），或改传 url");
        }
    }
}