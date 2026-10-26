package com.vidsage.application.subtitle;

import com.vidsage.application.subtitle.query.CorrectionView;
import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrection;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrectionField;
import com.vidsage.domain.subtitle.model.translation.TranslatedSegment;
import com.vidsage.domain.subtitle.model.translation.TranslatedTrack;
import com.vidsage.domain.subtitle.port.SubtitleCorrectionRepository;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 纠错覆盖层：把用户手改的文本叠到机器字幕上，纯内存、不碰文件。
 *
 * <p><b>所有「用户看到的字幕」都要经过这里</b>——页面、下载、翻译取源文本三处共用同一个入口，
 * 免得出现「页面改了、导出没改」。反过来，看机器产物的地方（清洗复用判定、指纹、对账）<b>不许</b>用它。
 *
 * <p>入参的 track 一律不改：覆盖结果是新对象，磁盘上那份永远是机器输出。
 */
@Slf4j
@Component
public class SubtitleCorrectionOverlay {

    private final SubtitleCorrectionRepository repository;

    /**
     * @param repository 纠错存取出口
     */
    public SubtitleCorrectionOverlay(SubtitleCorrectionRepository repository) {
        this.repository = repository;
    }

    /**
     * 把原文纠错叠到清洗字幕上：按 {@code segmentId + field=TEXT} 换掉 {@code text}，时间戳与 id 原样保留。
     *
     * @param taskId 字幕任务的内部行 id
     * @param track  磁盘上的清洗字幕（机器输出）
     * @return 叠好纠错的新 track；没有任何原文纠错时原样返回入参
     */
    public SubtitleTrack applyToCleanTrack(long taskId, SubtitleTrack track) {
        Map<String, String> overrides = overridesOf(taskId, SubtitleCorrectionField.TEXT, "");
        if (overrides.isEmpty()) {
            return track;
        }
        warnDuplicateIds(taskId, track.segments().stream().map(TranscriptionSegment::id).toList());
        List<TranscriptionSegment> segments = track.segments().stream()
                .map(segment -> {
                    String corrected = overrides.get(segment.id());
                    return corrected == null ? segment
                            : new TranscriptionSegment(segment.beginTimeMs(), segment.endTimeMs(),
                                    corrected, segment.id());
                })
                .toList();
        return new SubtitleTrack(track.language(), segments, track.keywordsFingerprint());
    }

    /**
     * 把某个语种的译文纠错叠到译文上：按 {@code segmentId + field=TRANSLATION + 同 lang} 换掉
     * {@code translatedText}，其它字段原样保留。
     *
     * @param taskId 字幕任务的内部行 id
     * @param lang   目标语种短码（与产物同一套归一值，如 {@code en}）
     * @param track  磁盘上的译文（机器输出）
     * @return 叠好纠错的新 track；该语种没有纠错时原样返回入参
     */
    public TranslatedTrack applyToTranslatedTrack(long taskId, String lang, TranslatedTrack track) {
        Map<String, String> overrides = overridesOf(taskId, SubtitleCorrectionField.TRANSLATION, lang);
        if (overrides.isEmpty()) {
            return track;
        }
        warnDuplicateIds(taskId, track.segments().stream().map(TranslatedSegment::id).toList());
        List<TranslatedSegment> segments = track.segments().stream()
                .map(segment -> {
                    String corrected = overrides.get(segment.id());
                    return corrected == null ? segment
                            : new TranslatedSegment(segment.beginTimeMs(), segment.endTimeMs(),
                                    segment.sourceText(), corrected, segment.id());
                })
                .toList();
        return new TranslatedTrack(track.sourceLanguage(), track.targetLanguage(), track.skipped(), segments);
    }

    /**
     * 给该任务每条纠错算 {@code matched} 与 {@code sourceChanged}。
     *
     * <p>{@code matched} 看这个 segment id 还在不在当前 CLEANED 里。{@code sourceChanged} 按字段分派，
     * 因为两类纠错「依据的源文本」不是同一份：
     * <ul>
     *   <li>原文纠错比<b>机器文本</b>——它要识别的是机器产物被重新生成（重新转写 / 重新切分）；</li>
     *   <li>译文纠错比<b>生效原文</b>（叠过原文纠错）——它要识别的是用户当初照着写译文的那句话
     *       后来被改过，也就是父 PRD 里「译文待复核」的判据。</li>
     * </ul>
     * 没命中的条目谈不上「源文本变了」，{@code sourceChanged} 一律 {@code false}。
     *
     * @param taskId     字幕任务的内部行 id
     * @param cleanTrack 当前 CLEANED 字幕（机器输出）；没有清洗产物时传空 track，结果全是未命中
     * @return 每条纠错一行，顺序与仓储返回一致
     */
    public List<CorrectionView> matchCorrections(long taskId, SubtitleTrack cleanTrack) {
        List<SubtitleCorrection> corrections = repository.findByTaskId(taskId);
        if (corrections.isEmpty()) {
            return List.of();
        }
        warnDuplicateIds(taskId, cleanTrack.segments().stream().map(TranscriptionSegment::id).toList());
        Map<String, String> machineTextById = new HashMap<>();
        for (TranscriptionSegment segment : cleanTrack.segments()) {
            machineTextById.put(segment.id(), textOf(segment.text()));
        }
        // 原文纠错本身就是「生效原文」的来源：译文纠错的依据文本要跟它比
        Map<String, String> textOverridesById = new HashMap<>();
        for (SubtitleCorrection correction : corrections) {
            if (correction.field() == SubtitleCorrectionField.TEXT) {
                textOverridesById.put(correction.segmentId(), correction.correctedValue());
            }
        }
        return corrections.stream()
                .map(correction -> {
                    String machineText = machineTextById.get(correction.segmentId());
                    boolean matched = machineText != null;
                    String currentText = correction.field() == SubtitleCorrectionField.TEXT
                            ? machineText
                            : textOverridesById.getOrDefault(correction.segmentId(), machineText);
                    boolean sourceChanged = matched
                            && !textOf(correction.sourceText()).equals(textOf(currentText));
                    return new CorrectionView(correction.id(), correction.segmentId(),
                            correction.field().token(), correction.lang(), correction.sourceText(),
                            correction.correctedValue(), matched, sourceChanged, correction.updatedAt());
                })
                .toList();
    }

    /**
     * 读不到当前 CLEANED 产物时的列表结果：<b>一律按「还命得上」返回，不做失效判定</b>。
     *
     * <p>为什么不在这种情况下报「全部失效」：清理失效是不可逆的删除，而 CLEANED 读不到
     * 可能只是文件被挪走 / 正在换版，并不代表字幕真的变了。判不了就不判，宁可晚一点报失效，
     * 也不能让用户点一次「清除」把还能用的纠错全删掉。
     *
     * @param taskId 字幕任务的内部行 id
     * @return 每条纠错一行，{@code matched=true}、{@code sourceChanged=false}
     */
    public List<CorrectionView> matchWithoutCleanTrack(long taskId) {
        List<SubtitleCorrection> corrections = repository.findByTaskId(taskId);
        if (corrections.isEmpty()) {
            return List.of();
        }
        log.warn("读不到当前清洗字幕，本次不判纠错是否失效（按全部命中处理）: taskId={}, count={}",
                taskId, corrections.size());
        return corrections.stream()
                .map(correction -> new CorrectionView(correction.id(), correction.segmentId(),
                        correction.field().token(), correction.lang(), correction.sourceText(),
                        correction.correctedValue(), true, false, correction.updatedAt()))
                .toList();
    }

    /**
     * 取要叠上去的「segment id → 改后文本」。
     *
     * <p>类型或语种对不上的纠错直接跳过、不报错：表里本来就混着原文与各语种译文，
     * 一次只叠其中一类。原文纠错没有语种维度，不按 lang 过滤。
     */
    private Map<String, String> overridesOf(long taskId, SubtitleCorrectionField field, String lang) {
        Map<String, String> overrides = new HashMap<>();
        for (SubtitleCorrection correction : repository.findByTaskId(taskId)) {
            if (correction.field() != field) {
                continue;
            }
            if (field == SubtitleCorrectionField.TRANSLATION && !sameLanguage(correction.lang(), lang)) {
                continue;
            }
            overrides.put(correction.segmentId(), correction.correctedValue());
        }
        return overrides;
    }

    /** 语种短码按大小写不敏感比较：BCP-47 的语种 / 地区段本来就不区分大小写。 */
    private static boolean sameLanguage(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    /**
     * 同一份字幕里 id 重复时纠错会同时命中这几条，记一条 WARN 便于发现。
     *
     * <p><b>不给 id 加后缀</b>：后缀会依赖出现顺序，跨重跑就不稳定了；按本设计的定义，
     * 同一个 id 本来就是同一句，命中多条是自洽的结果，只是值得提醒。
     */
    private static void warnDuplicateIds(long taskId, List<String> ids) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicated = new HashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                duplicated.add(id);
            }
        }
        for (String id : duplicated) {
            log.warn("同一份字幕里出现重复的 segment id，纠错会同时命中这几条: taskId={}, segmentId={}",
                    taskId, id);
        }
    }

    /** 文本判等前统一空值口径：{@code null} 与空串视为同一个「没有文本」。 */
    private static String textOf(String text) {
        return Objects.toString(text, "");
    }
}
