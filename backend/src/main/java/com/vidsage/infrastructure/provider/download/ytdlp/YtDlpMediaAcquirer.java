package com.vidsage.infrastructure.provider.download.ytdlp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vidsage.domain.media.model.AcquiredMedia;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.infrastructure.process.ProcessOutcome;
import com.vidsage.domain.media.port.MediaAcquirer;
import com.vidsage.shared.fs.ArtifactFiles;
import com.vidsage.shared.text.TextTail;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 远程 URL 路线的输入获取：用 yt-dlp 把站点页面下载成工作目录里的媒体文件 + info json 元数据。
 *
 * <p><b>不自动更新 yt-dlp</b>；下载配方与「产物定位」都按实测结论固定（研究笔记
 * external-process-integration.md §8.6）：
 * <ul>
 *   <li>输出模板固定为 {@code source.%(ext)s} —— 派生出 {@code source.<ext>} 与 {@code source.info.json}，
 *       合并用的中间分片由 yt-dlp 自行删除；</li>
 *   <li>info json <b>不含</b> {@code filepath} / {@code _filename} / {@code requested_downloads}
 *       等路径字段（实测三项全缺），所以产物定位靠<b>我们自己的固定模板</b> + info json 的 {@code ext}，
 *       再留一层目录扫描兜底；</li>
 *   <li>不按退出码分类（`1` 是万能错误码）：非零一律 {@link IllegalStateException} 并附 stderr 尾部，
 *       只对<b>实测过文案</b>的两种情况（bot 检测、非法 URL）做保守匹配，命不中即回退「未知失败」；</li>
 *   <li>stderr 里的 {@code WARNING:} 行转记 WARN 日志 —— B站 的会员画质限制正是 WARNING + exit 0，
 *       不算失败但必须可见。</li>
 * </ul>
 *
 * <p>两个下载器同时驻留容器（由输入子类型决定用哪个，不由配置决定），故<b>不加</b>
 * {@code @ConditionalOnProperty}（见 design §三 决策 2）。
 */
@Slf4j
@Component
public class YtDlpMediaAcquirer implements MediaAcquirer {

    /** 固定输出模板：派生 {@code source.<ext>} 与 {@code source.info.json}（实测，研究 §8.6-3/4）。 */
    static final String OUTPUT_TEMPLATE = "source.%(ext)s";

    /** info json 的固定文件名，由输出模板派生而来（实测）。 */
    static final String INFO_JSON_NAME = "source.info.json";

    /** 产物文件名前缀，用于目录扫描兜底。 */
    static final String PRODUCT_PREFIX = "source.";

    /** 失败消息里附带的 stderr 行数上限（与执行器一致）。 */
    private static final int STDERR_TAIL_LINES = 20;

    /** 扫描兜底时要排除的非产物后缀（info json / 未完成的 .part / yt-dlp 的 .ytdl 标记）。 */
    private static final Set<String> NON_PRODUCT_SUFFIXES = Set.of(".info.json", ".part", ".ytdl");

    /**
     * bot 检测拦截的稳定子串（实测原文含 {@code Sign in to confirm you're not a bot}，研究 §8.1）。
     * <b>实测过的文案才做保守匹配</b>，其余一律回退「未知失败 + stderr 尾部」。
     */
    private static final String BOT_DETECTION_MARKER = "not a bot";

    /** 非法 URL 的稳定子串（实测原文形如 {@code '[x]' is not a valid URL}，研究 §三）。 */
    private static final String INVALID_URL_MARKER = "is not a valid URL";

    /**
     * 「部分格式不可用」提示的稳定子串（实测原文
     * {@code [BiliBili] Format(s) 1080P 高码率 are missing; you have to become a premium member ...}）。
     *
     * <p><b>实现期实测修正</b>：该提示由 yt-dlp 写在 <b>stdout</b> 的 {@code [站点]} 行上、<b>没有
     * {@code WARNING:} 前缀</b>（与 implement.md 的假设不同），因此只扫 stderr 的 WARNING 行会漏掉它。
     * 它是 exit 0 的「画质降级」通知，不是失败，但「怎么画质降了」必须可查，故额外按该实测子串匹配。
     */
    private static final String FORMATS_MISSING_MARKER = "are missing; you have to become a";

    private final YtDlpRunner ytDlp;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param ytDlp        yt-dlp 调用收口（二进制解析、公共参数注入、超时都在它里面）
     * @param objectMapper Spring 提供的 JSON 映射器（解析 info json）
     */
    public YtDlpMediaAcquirer(YtDlpRunner ytDlp, ObjectMapper objectMapper) {
        this.ytDlp = ytDlp;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MediaInput input) {
        return input instanceof MediaInput.RemoteUrl;
    }

    /**
     * 检查工作目录里是否已有上一轮下载的<b>完整</b>产物与可直接解析的元数据，有则直接复用。
     *
     * <p>判据（缺一不可，任一不满足即返回空并交给 {@link #acquire} 重新下载）：
     * <ol>
     *   <li>{@code source.info.json} 存在且大小 &gt; 0（否则解析不出标题 / 时长 / ext）；</li>
     *   <li>info json 可解析，且 {@code title} / {@code duration} 可用；</li>
     *   <li>能定位到<b>唯一</b>且大小 &gt; 0 的 {@code source.<ext>}（先按 ext 推导，再目录扫描兜底）。</li>
     * </ol>
     *
     * <p><b>本方法不执行任何外部命令</b>，只是读盘；因此失败一律降级为「不可复用」而不是抛异常
     * ——重新下载才是恢复路径。唯一抛出的是入参本身不合法（输入类型 / URL / 工作目录）。
     */
    @Override
    public Optional<AcquiredMedia> reuseExisting(MediaInput input, Path workDir) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }
        if (!(input instanceof MediaInput.RemoteUrl remote)) {
            throw new IllegalArgumentException("YtDlpMediaAcquirer 只支持 MediaInput.RemoteUrl：当前为 "
                    + input.getClass().getSimpleName() + "，本地文件请交给本地路线");
        }
        if (!StringUtils.hasText(remote.value())) {
            throw new IllegalArgumentException("url 取值不能为空白：请传公网可访问的媒体页面地址，或改用 localPath");
        }
        if (workDir == null || !Files.isDirectory(workDir)) {
            throw new IllegalArgumentException("工作目录不存在或不是目录：" + workDir
                    + "；请先创建该目录再检查复用");
        }

        Path infoJson = workDir.resolve(INFO_JSON_NAME);
        if (!isNonEmptyRegularFile(infoJson)) {
            return Optional.empty();
        }
        YtDlpInfoJson info;
        try {
            info = parseInfoJson(infoJson);
        } catch (IllegalStateException e) {
            // info json 损坏（写盘中断等）：据此无法定位产物与标题。降级为「不可复用」，
            // 交给 acquire 重新下载（yt-dlp 会覆盖同名 info json），而不是在这里抛错挡住恢复路径
            log.debug("info json 不可解析，按未复用处理: file={}, reason={}", INFO_JSON_NAME, e.getMessage());
            return Optional.empty();
        }
        if (!hasUsableMetadata(info)) {
            log.debug("info json 元数据不完整，按未复用处理: file={}", INFO_JSON_NAME);
            return Optional.empty();
        }
        Optional<Path> product = findExistingProduct(workDir, info.ext());
        if (product.isEmpty()) {
            log.debug("未找到完整的已下载产物，按未复用处理: workDir={}", workDir.getFileName());
            return Optional.empty();
        }

        Path media = product.get();
        long durationMs = Math.round(info.duration() * 1000);
        // 脱敏：只记产物文件名与度量，不记标题（用户内容）
        log.info("复用已下载产物（断点复用）: media={}, durationMs={}, sizeBytes={}",
                media.getFileName(), durationMs, ArtifactFiles.sizeOrZero(media));
        return Optional.of(new AcquiredMedia(media, info.title(), durationMs, Optional.of(infoJson)));
    }

    /**
     * info json 的元数据是否足以构造 {@link AcquiredMedia}（标题非空、时长为有限非负数）。
     *
     * <p>包级 static：可直测。
     */
    static boolean hasUsableMetadata(YtDlpInfoJson info) {
        return info != null
                && StringUtils.hasText(info.title())
                && info.duration() != null
                && Double.isFinite(info.duration())
                && info.duration() >= 0;
    }

    /**
     * 定位已存在的完整产物：先按 ext 推导 {@code source.<ext>}，不满足则扫描兜底。
     *
     * <p>与 {@link #locateMedia} 的区别是<b>不抛异常</b>——扫码结果不是恰好一个（0 个或多个）时
     * 返回空，让调用方走「重新下载」；真正的产品定位失败由 {@link #acquire} 抛错。
     *
     * <p>包级 static：可直测。
     *
     * @param workDir 下载工作目录
     * @param ext     info json 里的最终扩展名；空白时跳过推导直接扫描
     * @return 唯一且非空的产物路径；不存在或不止一个时返回空
     */
    static Optional<Path> findExistingProduct(Path workDir, String ext) {
        if (StringUtils.hasText(ext)) {
            Path derived = workDir.resolve(PRODUCT_PREFIX + ext);
            if (isNonEmptyRegularFile(derived)) {
                return Optional.of(derived);
            }
        }
        List<Path> candidates = scanProducts(workDir).stream()
                .filter(YtDlpMediaAcquirer::isNonEmptyRegularFile)
                .toList();
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    /**
     * 是否为「存在且大小 &gt; 0」的普通文件（断点复用与产物完整性的统一判据）。
     */
    private static boolean isNonEmptyRegularFile(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public AcquiredMedia acquire(MediaInput input, Path workDir) {
        // 卫语句前置：先失败再干活
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }
        if (!(input instanceof MediaInput.RemoteUrl remote)) {
            throw new IllegalArgumentException("YtDlpMediaAcquirer 只支持 MediaInput.RemoteUrl：当前为 "
                    + input.getClass().getSimpleName() + "，本地文件请交给本地路线");
        }
        if (!StringUtils.hasText(remote.value())) {
            throw new IllegalArgumentException("url 取值不能为空白：请传公网可访问的媒体页面地址，或改用 localPath");
        }
        if (workDir == null || !Files.isDirectory(workDir)) {
            throw new IllegalArgumentException("工作目录不存在或不是目录：" + workDir
                    + "；请先创建该目录再下载");
        }

        List<String> args = List.of(
                "--no-playlist",
                // 阶段内进度不在范围内，且进度行会让 stdout 膨胀；将来做 SSE 进度时改用 --progress-template
                "--no-progress",
                "--write-info-json",
                "-o", OUTPUT_TEMPLATE,
                "-P", workDir.toString(),
                remote.value());
        ProcessOutcome outcome = ytDlp.run(args, workDir);
        logNotices(outcome);

        if (outcome.timedOut()) {
            throw new IllegalStateException("外部命令执行超时（" + ytDlp.timeoutSeconds()
                    + " 秒）：yt-dlp；可用 vidsage.tools.default-timeout-seconds 调整");
        }
        if (outcome.exitCode() != 0) {
            // 失败信息分层：异常消息只带 stderr 尾部（有界、可操作），完整 stderr 落 DEBUG 供深挖
            log.debug("yt-dlp 下载失败，完整 stderr: exit={}, stderr={}", outcome.exitCode(), outcome.stderr());
            throw new IllegalStateException(failureMessage(outcome.exitCode(), outcome.stderr()));
        }

        Path infoJson = workDir.resolve(INFO_JSON_NAME);
        if (!Files.isRegularFile(infoJson)) {
            // exit 0 却没落 info json：契约被破坏，静默返回会让标题/时长缺失
            throw new IllegalStateException("yt-dlp 声称成功但未落盘 info json：" + infoJson
                    + "；请确认 --write-info-json 生效，或该站点是否被跳过");
        }
        YtDlpInfoJson info = parseInfoJson(infoJson);
        Path media = locateMedia(workDir, info.ext());
        // 先校验必填元数据、再记「下载完成」：否则 title/duration 缺失时会先打成功锚点再抛异常，
        // 日志里出现一条假成功记录
        long durationMs = durationMsOf(info);
        String title = requireTitle(info);
        log.info("yt-dlp 下载完成: exit=0, media={}, durationMs={}, sizeBytes={}, elapsedMs={}",
                media.getFileName(), durationMs, ArtifactFiles.sizeOrZero(media), outcome.elapsedMs());
        return new AcquiredMedia(media, title, durationMs, Optional.of(infoJson));
    }

    /**
     * 解析 info json 为类内嵌套 record。
     *
     * <p>包级可见：单元测试可直接喂一个真实的 info json 文件，不必构造完整流程（见 quality-guidelines）。
     *
     * @param infoJson info json 文件路径
     * @return 解析结果
     * @throws IllegalStateException 读取或反序列化失败
     */
    YtDlpInfoJson parseInfoJson(Path infoJson) {
        try {
            return objectMapper.readValue(infoJson.toFile(), YtDlpInfoJson.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("解析 yt-dlp info json 失败：" + infoJson.getFileName()
                    + "；" + e.getMessage(), e);
        }
    }

    /**
     * 定位下载产物：先按固定模板推导 {@code source.<ext>}，不存在则在目录里扫描兜底。
     *
     * <p>包级 static：单元测试可直接断言「推导优先」与「扫描兜底」，无需真实下载。
     *
     * @param workDir 下载工作目录
     * @param ext     info json 里的最终扩展名（如 mp4）；空白时跳过推导直接扫描
     * @return 唯一的产物路径
     * @throws IllegalStateException 扫描结果不是恰好一个；消息附目录清单，<b>不猜</b>
     */
    static Path locateMedia(Path workDir, String ext) {
        if (StringUtils.hasText(ext)) {
            Path derived = workDir.resolve(PRODUCT_PREFIX + ext);
            if (Files.isRegularFile(derived)) {
                return derived;
            }
        }
        List<Path> candidates = scanProducts(workDir);
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        throw new IllegalStateException("无法定位 yt-dlp 下载产物（应恰有 1 个 source.*，实际 "
                + candidates.size() + " 个）；info json 的 ext=" + ext + "，目录清单：" + describeDir(workDir));
    }

    /**
     * 扫描目录里匹配 {@code source.*} 且不属于 info json / 未完成标记的普通文件。
     *
     * <p>包级 static：可直测。
     */
    static List<Path> scanProducts(Path workDir) {
        try (Stream<Path> stream = Files.list(workDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(PRODUCT_PREFIX))
                    .filter(path -> !isNonProduct(path.getFileName().toString()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("扫描下载工作目录失败：" + workDir + "；" + e.getMessage(), e);
        }
    }

    /**
     * 标题为必填契约字段：info json 没有 title 时不能静默给空串。
     *
     * <p>包级 static：可直测。
     *
     * @throws IllegalStateException title 缺失或为空白
     */
    static String requireTitle(YtDlpInfoJson info) {
        if (info == null || !StringUtils.hasText(info.title())) {
            throw new IllegalStateException("yt-dlp info json 缺少 title：请确认该站点在元数据里提供了标题");
        }
        return info.title();
    }

    /**
     * 时长（秒，浮点）→ 毫秒（四舍五入取整）。时长为必填契约字段。
     *
     * <p>包级 static：可直测。
     *
     * @throws IllegalStateException duration 缺失或非法
     */
    static long durationMsOf(YtDlpInfoJson info) {
        if (info == null || info.duration() == null || !Double.isFinite(info.duration()) || info.duration() < 0) {
            throw new IllegalStateException("yt-dlp info json 缺少可用的 duration（秒）：无法确定媒体时长");
        }
        return Math.round(info.duration() * 1000);
    }

    /**
     * 生成失败消息：命中实测文案给专门指引，否则「未知失败 + stderr 尾部」。
     *
     * <p>包级 static：可直测。文案匹配<b>保守</b>——只匹配两条实测过的子串，命不中不影响正常路径。
     */
    static String failureMessage(int exitCode, String stderr) {
        String tail = TextTail.tailLines(stderr, STDERR_TAIL_LINES);
        String raw = stderr == null ? "" : stderr;
        if (raw.contains(BOT_DETECTION_MARKER)) {
            return "yt-dlp 下载失败（exit=" + exitCode + "）：站点要求登录验证（bot 检测）；请把 "
                    + "vidsage.download.yt-dlp.cookies-file 指向导出的 cookies 文件，或换用无需登录的来源；"
                    + "yt-dlp 原文：" + tail;
        }
        if (raw.contains(INVALID_URL_MARKER)) {
            return "yt-dlp 下载失败（exit=" + exitCode
                    + "）：该地址不是 yt-dlp 支持的 URL；请检查 url（yt-dlp 只接受站点页面地址，不接受本地文件路径，"
                    + "本地文件请改传 localPath）；yt-dlp 原文：" + tail;
        }
        return "yt-dlp 下载失败（exit=" + exitCode + "）：" + tail;
    }

    /**
     * 提取 stderr 里的 {@code WARNING:} 行。
     *
     * <p>包级 static：可直测。yt-dlp 把 warning 写 stderr；这些是 exit 0 也可能出现的告警，
     * 不算失败但必须可见。
     */
    static List<String> warningLines(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return List.of();
        }
        return stderr.lines().map(String::trim).filter(line -> line.startsWith("WARNING")).toList();
    }

    /**
     * 提取 stdout 里的「部分格式不可用」提示行。
     *
     * <p>包级 static：可直测。实测该提示写在 stdout、无 {@code WARNING:} 前缀（见
     * {@link #FORMATS_MISSING_MARKER}），只扫 stderr 会漏掉它。
     */
    static List<String> formatAvailabilityNotices(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return List.of();
        }
        return stdout.lines().map(String::trim)
                .filter(line -> line.contains(FORMATS_MISSING_MARKER))
                .toList();
    }

    /**
     * 把 yt-dlp 的告警与「格式不可用」提示转记 WARN 日志：失败与成功路径都要调用，
     * 否则「怎么画质降了」查不出来。只逐行记已识别的行，不整条透传输出（可能含用户内容与 URL）。
     */
    private void logNotices(ProcessOutcome outcome) {
        for (String line : warningLines(outcome.stderr())) {
            log.warn("yt-dlp 告警: {}", line);
        }
        for (String line : formatAvailabilityNotices(outcome.stdout())) {
            log.warn("yt-dlp 提示: {}", line);
        }
    }

    /**
     * 目录清单（文件名，按字典序），用于「产物无法定位」时附证据。
     */
    static String describeDir(Path workDir) {
        try (Stream<Path> stream = Files.list(workDir)) {
            return stream.map(path -> path.getFileName().toString()).sorted()
                    .collect(Collectors.joining(", "));
        } catch (IOException e) {
            return "<无法读取目录：" + e.getMessage() + ">";
        }
    }

    /**
     * 是否非产物文件（info json / {@code .part} / {@code .ytdl}）。
     */
    private static boolean isNonProduct(String fileName) {
        return NON_PRODUCT_SUFFIXES.stream().anyMatch(fileName::endsWith);
    }

    /**
     * yt-dlp info json 里本实现用到的字段（类内嵌套 record + ignoreUnknown，防止上游加字段
     * 导致反序列化失败；见 provider-pattern.md「外部响应映射」）。
     *
     * <p>刻意<b>不含</b>路径类字段（filepath / _filename / requested_downloads）——实测它们不存在，
     * 建模它们只会误导后来者以为可以靠 info json 定位产物。
     *
     * @param ext      合并后的最终扩展名，如 "mp4"；用于推导产物文件名。可能缺失
     * @param title    媒体标题；必填，缺失时抛异常
     * @param duration 媒体时长（秒，浮点）；必填，缺失时抛异常
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record YtDlpInfoJson(String ext, String title, Double duration) {
    }
}
