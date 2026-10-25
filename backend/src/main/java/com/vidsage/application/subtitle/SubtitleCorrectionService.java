package com.vidsage.application.subtitle;

import com.vidsage.application.subtitle.exception.CorrectionSegmentOutdatedException;
import com.vidsage.application.subtitle.query.CorrectionView;
import com.vidsage.application.task.SubtitleTaskFinder;
import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrection;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrectionField;
import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;
import com.vidsage.domain.subtitle.model.translation.TranslatedSegment;
import com.vidsage.domain.subtitle.model.translation.TranslatedTrack;
import com.vidsage.domain.subtitle.port.SubtitleCorrectionRepository;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.TaskRun;
import com.vidsage.domain.task.model.TaskRunStatus;
import com.vidsage.domain.task.port.TaskRunRepository;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 字幕纠错的用例入口：保存、删除、查询、清理失效。
 *
 * <p>纠错只是覆盖层，<b>不动磁盘上的任何产物</b>；取值、语种与任务状态的校验都在这里，
 * 顺序是「先看调用方传的对不对，再看任务状态，最后才读文件」——参数错误不该被
 * 「没装工具 / 文件没了」这类环境问题盖掉。（最外层的空白 / 缺字段卫语句仍在 HTTP 边界。）
 */
@Slf4j
@Service
public class SubtitleCorrectionService {

    /** 单条纠错文本的长度上限（按 trim 后的字符数）。 */
    private static final int MAX_VALUE_LENGTH = 2000;

    private final SubtitleTaskFinder finder;
    private final SubtitleCorrectionRepository repository;
    private final SubtitleCorrectionOverlay overlay;
    private final SubtitleTrackReader trackReader;
    private final TaskRunRepository taskRunRepository;

    /**
     * @param finder            按任务号找任务、媒体身份与工作目录
     * @param repository        纠错存取出口
     * @param overlay           纠错合并入口（返回保存后的视图与列表判据）
     * @param trackReader       读磁盘上的 CLEANED / TRANSLATED 机器产物
     * @param taskRunRepository 查最近一条 run 以拦「运行中不许改」
     */
    public SubtitleCorrectionService(SubtitleTaskFinder finder, SubtitleCorrectionRepository repository,
                                     SubtitleCorrectionOverlay overlay, SubtitleTrackReader trackReader,
                                     TaskRunRepository taskRunRepository) {
        this.finder = finder;
        this.repository = repository;
        this.overlay = overlay;
        this.trackReader = trackReader;
        this.taskRunRepository = taskRunRepository;
    }

    /**
     * 保存一条纠错（同一段同一项重复保存就是覆盖）。
     *
     * <p>{@code sourceText} 记的是「这条纠错所依据的源文本」：原文纠错记改前的机器原文，
     * 译文纠错记 CLEANED 里该段的原文。两边都取<b>未经覆盖</b>的机器文本，事后才能判断改动还对不对得上。
     *
     * @param taskNo    对外任务号
     * @param segmentId 字幕段 id
     * @param field     改原文还是某个语种的译文，收对外词形 {@code text} / {@code translation}
     *                  （大小写不敏感，由 {@link SubtitleCorrectionField#parse} 解析）
     * @param lang      目标语种；{@code field=text} 时忽略、落库为空串
     * @param value     用户改后的文本（会去掉首尾空白）
     * @return 保存后的纠错（{@code matched=true}、{@code sourceChanged=false}，刚校验并刷新过）
     * @throws IllegalArgumentException                          参数不合法 / 该语种没有可用译文
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在
     * @throws TaskConflictException                             该任务正在跑
     * @throws CorrectionSegmentOutdatedException                当前字幕里没有这个段，或译文版本比原文旧
     */
    public CorrectionView save(String taskNo, String segmentId, String field,
                               String lang, String value) {
        validateSegmentId(segmentId);
        SubtitleCorrectionField resolvedField = SubtitleCorrectionField.parse(field);
        String resolvedLang = resolveLang(resolvedField, lang);
        String trimmedValue = validateValue(value);

        SubtitleTask task = finder.require(taskNo);
        validateNotRunning(task);

        Path workDir = workDirOf(task);
        SubtitleTrack cleanTrack = trackReader.readCleanTrack(task.getId(), workDir)
                .orElseThrow(() -> new CorrectionSegmentOutdatedException(taskNo, segmentId));
        TranscriptionSegment segment = findSegment(cleanTrack, segmentId)
                .orElseThrow(() -> new CorrectionSegmentOutdatedException(taskNo, segmentId));

        if (resolvedField == SubtitleCorrectionField.TRANSLATION) {
            TranslatedTrack translatedTrack = trackReader.readTranslatedTrack(task.getId(), resolvedLang, workDir)
                    .orElseThrow(() -> new IllegalArgumentException("该语种还没有可用的译文产物：lang="
                            + resolvedLang + "；请先触发翻译（POST /api/tasks/" + taskNo
                            + "/translate）后再改译文，或改传已有译文的语种"));
            // 原文里有、译文里没有：这份译文是照更旧的原文翻的。不拦的话会存下一条永远叠不上去的纠错
            if (findTranslatedSegment(translatedTrack, segmentId).isEmpty()) {
                throw CorrectionSegmentOutdatedException.forOutdatedTranslation(taskNo, segmentId, resolvedLang);
            }
        }

        SubtitleCorrection saved = repository.upsert(new SubtitleCorrection(null, task.getId(), segmentId,
                resolvedField, resolvedLang, segment.text(), trimmedValue, null, null));
        log.info("字幕纠错已保存: taskNo={}, segmentId={}, field={}, lang={}",
                taskNo, segmentId, resolvedField, resolvedLang);
        return new CorrectionView(saved.id(), saved.segmentId(), saved.field().token(), saved.lang(),
                saved.sourceText(), saved.correctedValue(), true, false, saved.updatedAt());
    }

    /**
     * 删掉一条纠错（恢复机器文本）。删不存在的条目也不报错。
     *
     * <p>运行中同样拒绝：重新生成时字幕正在换版，此刻删改容易和用户的预期错位。
     *
     * @param taskNo    对外任务号
     * @param segmentId 字幕段 id
     * @param field     原文还是译文，收对外词形 {@code text} / {@code translation}
     * @param lang      目标语种；{@code field=text} 时忽略
     * @throws IllegalArgumentException                          参数不合法
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在
     * @throws TaskConflictException                             该任务正在跑
     */
    public void delete(String taskNo, String segmentId, String field, String lang) {
        validateSegmentId(segmentId);
        SubtitleCorrectionField resolvedField = SubtitleCorrectionField.parse(field);
        String resolvedLang = resolveLang(resolvedField, lang);

        SubtitleTask task = finder.require(taskNo);
        validateNotRunning(task);

        int removed = repository.deleteByTaskIdAndSegmentIdAndFieldAndLang(task.getId(), segmentId,
                resolvedField, resolvedLang);
        log.info("字幕纠错删除完成: taskNo={}, segmentId={}, field={}, lang={}, removed={}",
                taskNo, segmentId, resolvedField, resolvedLang, removed);
    }

    /**
     * 列该任务的全部纠错，并给每条算「还对不对得上当前字幕」。
     *
     * <p>没有清洗产物时也照常返回列表，但<b>不做失效判定</b>（见
     * {@link SubtitleCorrectionOverlay#matchWithoutCleanTrack}）：刚提交还没跑出字幕的任务，
     * 前端不该看到一个 500，更不该看到「全部失效」而点清理把纠错删了。
     *
     * @param taskNo 对外任务号
     * @return 每条纠错一行；一条都没有时是空列表
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在
     */
    public List<CorrectionView> list(String taskNo) {
        SubtitleTask task = finder.require(taskNo);
        return trackReader.readCleanTrack(task.getId(), workDirOf(task))
                .map(track -> overlay.matchCorrections(task.getId(), track))
                .orElseGet(() -> overlay.matchWithoutCleanTrack(task.getId()));
    }

    /**
     * 清理失效纠错：只删当前字幕里找不到的那些（{@code matched=false}）。
     *
     * <p>「失效」按 id 判：字幕重新生成后切分变了，旧 id 自然对不上。还在的条目一条都不动。
     *
     * @param taskNo 对外任务号
     * @return 实际删掉的条数
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在
     * @throws TaskConflictException 该任务正在跑
     */
    public int clearStale(String taskNo) {
        SubtitleTask task = finder.require(taskNo);
        validateNotRunning(task);

        // 读不到 CLEANED 时 matchWithoutCleanTrack 一律按命中返回，这里自然删 0 条：
        // 判不了失效就不清，避免异常状态下把还能用的纠错一次删光
        List<Long> staleIds = trackReader.readCleanTrack(task.getId(), workDirOf(task))
                .map(track -> overlay.matchCorrections(task.getId(), track))
                .orElseGet(() -> overlay.matchWithoutCleanTrack(task.getId()))
                .stream()
                .filter(view -> !view.matched())
                .map(CorrectionView::id)
                .filter(Objects::nonNull)
                .toList();
        int removed = repository.deleteByIds(staleIds);
        log.info("清理失效字幕纠错: taskNo={}, candidates={}, removed={}", taskNo, staleIds.size(), removed);
        return removed;
    }

    /** 定位工作目录：任务 → 媒体身份 → 工作区根下的目录名。 */
    private Path workDirOf(SubtitleTask task) {
        return finder.workspaceDir(finder.requireWorkspace(task));
    }

    /** 当前字幕里按 id 找那一段；同一 id 出现多次时取第一条（它们是同一句）。 */
    private static Optional<TranscriptionSegment> findSegment(SubtitleTrack cleanTrack, String segmentId) {
        return cleanTrack.segments().stream()
                .filter(segment -> segment.id().equals(segmentId))
                .findFirst();
    }

    /** 该语种译文里按 id 找那一段：译文条目与原文一一对应，找不到说明译文落后于原文。 */
    private static Optional<TranslatedSegment> findTranslatedSegment(TranslatedTrack translatedTrack,
                                                                    String segmentId) {
        return translatedTrack.segments().stream()
                .filter(segment -> segment.id().equals(segmentId))
                .findFirst();
    }

    /** 该任务最近一条 run 还在跑就拒绝：运行中产物正在换版，此刻改容易与实际看到的字幕错位。 */
    private void validateNotRunning(SubtitleTask task) {
        taskRunRepository.findLatestByTaskId(task.getId()).ifPresent(run -> {
            if (run.getStatus() == TaskRunStatus.RUNNING) {
                throw new TaskConflictException(task.getTaskNo().value(), runNoOf(run));
            }
        });
    }

    /** 落库的 run 一定带 run_no；真为空时给 0，只为不让响应体拼出 null。 */
    private static int runNoOf(TaskRun run) {
        return run.getRunNo() == null ? 0 : run.getRunNo();
    }

    private static void validateSegmentId(String segmentId) {
        if (!StringUtils.hasText(segmentId)) {
            throw new IllegalArgumentException("segmentId 不能为空：请传字幕段 id（形如 seg-0-3520）");
        }
    }

    /**
     * 归一语种：原文纠错不要语种（一律空串），译文纠错必须带、且必须过支持列表。
     *
     * @throws IllegalArgumentException     译文纠错缺 lang
     * @throws com.vidsage.domain.exception.UnsupportedLanguageException 语种不在支持列表（也是 400）
     */
    private static String resolveLang(SubtitleCorrectionField field, String lang) {
        if (field == SubtitleCorrectionField.TEXT) {
            return "";
        }
        if (!StringUtils.hasText(lang)) {
            throw new IllegalArgumentException("改译文时必须带 lang：请传目标语种短码，如 en");
        }
        return SupportedLanguage.parse(lang).getCode();
    }

    /** 校验并去掉首尾空白：空内容要用户改走删除接口，超长则明确告诉他上限。 */
    private static String validateValue(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("纠错内容不能为空：要恢复机器文本请改用删除"
                    + "（DELETE /api/tasks/{taskNo}/corrections/{segmentId}）");
        }
        if (trimmed.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("纠错内容过长：" + trimmed.length() + " 字符，单条最多 "
                    + MAX_VALUE_LENGTH + " 字符；请拆成更短的句子后重试");
        }
        return trimmed;
    }
}
