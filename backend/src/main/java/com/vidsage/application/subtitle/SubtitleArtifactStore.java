package com.vidsage.application.subtitle;

import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactFileKind;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.port.SubtitleArtifactRepository;
import com.vidsage.shared.fs.ArtifactFileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 产物版本的读写门面：开始一版（占位）、落定一版（换版）、按「完好的当前版」查。
 *
 * <p>写与读共用同一套文件位解析（{@link ArtifactFileKind}），所以「该类型带哪几个文件」
 * 只有一处定义；仓储只负责落库，版本交替的顺序收在这里。
 *
 * <p><b>落定有两个版本，按「有没有下游」分</b>：
 * <ul>
 *   <li>{@link #settleArtifact} —— 无下游的类型（原始转写、译文）：旧版降级与新行生效包在同一个事务里，
 *       崩在中间时旧版仍是当前版。</li>
 *   <li>{@link #settleCleanedAndSupersedeDownstream} —— 清洗产物：下面挂着译文，
 *       旧版降级、下游作废、新行生效是同一个事务；下游文件在事务提交后再删。</li>
 * </ul>
 *
 * <p><b>完好判定只看「行是 READY 且该类型必需的文件位都在」</b>：文件不在了就记一条 WARN、按没有处理，
 * 不改任何状态；0 字节文件算存在（空转写是合法产物）。可选位（译文的术语表）没有文件是正常形态。
 */
@Slf4j
@Component
public class SubtitleArtifactStore {

    private final SubtitleArtifactRepository repository;

    private final TransactionTemplate transactionTemplate;

    public SubtitleArtifactStore(SubtitleArtifactRepository repository,
                                 PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 插一版占位：状态 CREATING、文件列与哈希列全空，版本号由仓储按同类型最大版本加一生成。
     *
     * @param taskId                所属字幕任务
     * @param runId                 本次产出它的执行
     * @param type                  产物类型
     * @param lang                  语种；没有语言维度传空串
     * @param derivedFromArtifactId 内容继承自哪一版上游；没有就传 {@code null}
     * @param keywordsFingerprint   关键词指纹；只有清洗产物传值
     * @return 已落库的占位产物
     */
    public SubtitleArtifact createCreatingPlaceholder(long taskId, long runId, ArtifactType type, String lang,
                                  Long derivedFromArtifactId, String keywordsFingerprint) {
        SubtitleArtifact creating = SubtitleArtifact.createCreatingArtifact(taskId, runId, type, lang,
                derivedFromArtifactId, keywordsFingerprint);
        return repository.insertCreating(creating);
    }

    /**
     * 落定一版<b>没有下游</b>的产物：算文件名与哈希 → 旧当前版降级并清空文件列 → 新版写文件列并置 READY。
     * 后两步包在同一个事务里，崩在中间时旧版仍是当前版。
     *
     * <p><b>只在真产出之后调用</b>：跳过复用的一趟（{@code SubtitlePipeline} 命中检查点）压根不落版，
     * 也就不调本方法，整条链一个字节不动。
     *
     * <p>文件此刻必须已经由产出方原子替换落盘；这里算哈希失败就抛异常，让本次执行收口成失败，
     * 而且此时旧版还一点没动——调用方手里仍有上一版可用。
     *
     * @param creating 本版占位产物
     * @param workDir  工作区目录
     * @return 同一个产物对象（已置 READY，携带文件列与哈希列）
     * @throws IllegalStateException 应带的某个文件读不到，或产物当前不是 CREATING
     */
    public SubtitleArtifact settleArtifact(SubtitleArtifact creating, Path workDir) {
        ArtifactFiles files = resolveArtifactFiles(creating.getArtifactType(), creating.getLang(), workDir);

        // 降级旧版与新行 READY 必须同刻可见：崩在中间时旧版仍是 READY，用户不会看到「零当前版」。
        // 这里用事务模板而不是注解：哈希要留在事务外，事务段只能是内部方法，同类内再调会绕过代理；
        // 且事务边界要在不启 Spring 上下文的单测里可见。
        transactionTemplate.executeWithoutResult(status -> {
            // 降级旧版放在哈希之后：哈希失败时旧版还是可用的当前版
            repository.findReadyByTaskIdAndTypeAndLang(creating.getTaskId(), creating.getArtifactType(),
                            creating.getLang())
                    .ifPresent(old -> repository.markSupersededAndClearFiles(old.getId()));

            markReady(creating, files);
        });
        return creating;
    }

    /**
     * 落定一版<b>清洗</b>产物：旧版降级、下游译文作废、新行生效在同一个事务里；事务提交后再删下游的文件。
     *
     * <p>为什么单独一个方法：清洗是唯一有下游的产物类型，这一步会连带把译文作废，调用点应当一眼看出来。
     * 它和 {@link #settleArtifact} 的差别只在「下游」这一件事，前两步（算文件、降级旧版）完全同款。
     *
     * <p><b>走到这里就意味着清洗真重跑过，下游一律作废</b>：跳过复用的那一趟压根不调本方法
     * （{@code SubtitlePipeline} 先问复用、命中就整条链不动）。所以不再比哈希决定要不要作废——
     * 那个判据随「跳过也落版」一起退场，留下只会让「真重跑但产出逐字节相同」这一个罕见情形白留旧译文。
     *
     * <p>下游的文件删除在事务之外：先标数据库（上面那个事务做完了），再删文件，删干净才说「已回收」。
     *
     * <p><b>不返回任何东西</b>：这一版落定与下游回收是一件事的两个半场，调用方没有「只做前半场」的余地，
     * 也就不该有返回值引诱它去接第二步。
     *
     * @param creating 本版占位产物（方法内原地置 READY，携带文件列与哈希列）
     * @param workDir  工作区目录
     * @throws IllegalStateException 应带的某个文件读不到，或产物当前不是 CREATING
     */
    public void settleCleanedAndSupersedeDownstream(SubtitleArtifact creating, Path workDir) {
        ArtifactFiles files = resolveArtifactFiles(creating.getArtifactType(), creating.getLang(), workDir);

        List<SubtitleArtifact> translationsToReclaim = transactionTemplate.execute(status -> {
            // 旧行要在清列之前读出来：markSupersededAndClearFiles 要它的 id
            repository.findReadyByTaskIdAndTypeAndLang(creating.getTaskId(), creating.getArtifactType(),
                            creating.getLang())
                    .ifPresent(previous -> repository.markSupersededAndClearFiles(previous.getId()));

            // 先读出来：事务提交后要按它们的文件列删文件。改状态另走一句批量，不逐行。
            List<SubtitleArtifact> translationsToSupersede = findDownstreamTranslations(creating.getTaskId());
            if (!translationsToSupersede.isEmpty()) {
                // 只标状态，文件列留着：删文件时要用它的路径
                repository.markSupersededByTaskIdAndType(creating.getTaskId(), ArtifactType.TRANSLATED);
            }

            markReady(creating, files);
            return translationsToSupersede;
        });

        for (SubtitleArtifact translation : translationsToReclaim == null
                ? List.<SubtitleArtifact>of() : translationsToReclaim) {
            reclaimSuperseded(translation, workDir);
        }
    }

    /**
     * 两阶段回收一版已作废的产物：删掉它登记的文件位，全删干净才标 {@code DELETED}。
     *
     * <p>删不动（文件被占用、权限不足、必需位缺登记）只记 WARN，那一行停在 {@code SUPERSEDED}
     * 留给对账——新版已经生效、事务也提交了，回收没做完不该让用户看到失败。
     * 内容变更级联（指纹变了要作废整批旧产物）也用这个方法回收译文。
     *
     * @param artifact 已作废（{@code SUPERSEDED}）的产物行
     * @param workDir  工作区目录
     */
    public void reclaimSuperseded(SubtitleArtifact artifact, Path workDir) {
        try {
            if (reclaimFilesOf(artifact, workDir)) {
                repository.markDeleted(artifact.getId());
            }
        } catch (RuntimeException e) {
            // 不传异常对象：它的消息就是完整路径，而目录名内嵌身份键（脱敏红线）
            log.warn("产物回收失败，该行留在待回收状态: taskId={}, type={}, lang={}, reason={}",
                    artifact.getTaskId(), artifact.getArtifactType(), artifact.getLang(),
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 查某一版「完好的当前版」：行是 READY，且该类型必需的每个文件位都在；
     * 可选位登记了也要在，没登记不算缺。
     *
     * @param taskId  所属字幕任务
     * @param type    产物类型
     * @param lang    语种；没有语言维度传空串
     * @param workDir 工作区目录
     * @return 完好的当前版；没有或文件不齐时为空
     */
    public Optional<SubtitleArtifact> findIntactArtifact(long taskId, ArtifactType type, String lang, Path workDir) {
        return repository.findReadyByTaskIdAndTypeAndLang(taskId, type, lang)
                .filter(artifact -> isIntact(artifact, workDir));
    }

    /**
     * 列该任务全部「完好的当前版」，不对产物类型与语种排序。
     *
     * @param taskId  所属字幕任务
     * @param workDir 工作区目录
     * @return 完好的当前版；一个都没有时是空列表
     */
    public List<SubtitleArtifact> listIntactArtifacts(long taskId, Path workDir) {
        List<SubtitleArtifact> intact = new ArrayList<>();
        for (SubtitleArtifact artifact : repository.findReadyByTaskId(taskId)) {
            if (isIntact(artifact, workDir)) {
                intact.add(artifact);
            }
        }
        return List.copyOf(intact);
    }

    /**
     * 一版产物算出来的文件：文件名与哈希各按文件位一张表，只含该类型带的那些位。
     */
    private record ArtifactFiles(Map<ArtifactFileKind, String> names, Map<ArtifactFileKind, String> hashes) {
    }

    /**
     * 按产物类型求出该带的文件位，逐个求文件名并算 sha256。
     *
     * <p>文件此刻必须已经由产出方原子替换落盘。必需位读不到就抛异常，这时调用方手里还什么都没动；
     * 可选位没有文件就跳过（文件名与哈希都留空）。
     */
    private static ArtifactFiles resolveArtifactFiles(ArtifactType type, String lang, Path workDir) {
        Map<ArtifactFileKind, String> names = new EnumMap<>(ArtifactFileKind.class);
        Map<ArtifactFileKind, String> hashes = new EnumMap<>(ArtifactFileKind.class);
        for (ArtifactFileKind kind : ArtifactFileKind.values()) {
            if (!kind.belongsTo(type)) {
                continue;
            }
            String fileName = kind.specFor(type).getFileName(lang);
            Path file = workDir.resolve(fileName);
            if (!kind.isRequiredFor(type) && !ArtifactFileUtils.isRegularFile(file)) {
                // 可选位没有这个文件：正常形态，不登记、不算哈希
                continue;
            }
            hashes.put(kind, ArtifactFileUtils.sha256(file));
            names.put(kind, fileName);
        }
        return new ArtifactFiles(names, hashes);
    }

    /**
     * 写文件列与哈希列并置 READY：这是换版的最后一步，占位行到这一刻才成为当前版。
     */
    private void markReady(SubtitleArtifact creating, ArtifactFiles files) {
        creating.completeCreation(
                files.names().get(ArtifactFileKind.SEGMENTS), files.names().get(ArtifactFileKind.SUBTITLES),
                files.names().get(ArtifactFileKind.CONTEXT), files.names().get(ArtifactFileKind.TERMS),
                files.hashes().get(ArtifactFileKind.SEGMENTS), files.hashes().get(ArtifactFileKind.SUBTITLES),
                files.hashes().get(ArtifactFileKind.CONTEXT), files.hashes().get(ArtifactFileKind.TERMS));
        repository.updateReady(creating);
    }

    /**
     * 该任务当前有效的译文——级联作废的靶子。
     *
     * <p>只认「状态是 READY」，不按派生关系找：译文基于哪一版清洗不写进判据，由「清洗换版时就把它标掉」保证。
     */
    private List<SubtitleArtifact> findDownstreamTranslations(long taskId) {
        return repository.findReadyByTaskId(taskId).stream()
                .filter(artifact -> artifact.getArtifactType() == ArtifactType.TRANSLATED)
                .toList();
    }

    /**
     * 删掉一版产物登记的全部文件位。
     *
     * <p>全部删干净（或本来就不在）才返回 {@code true} —— 只有这样才能说「文件已确认回收」。
     * 可选位没登记是正常形态，跳过；必需位缺登记或删不动返回 {@code false}，让那一行停在
     * 「待删」状态，留给对账报出来。
     */
    private boolean reclaimFilesOf(SubtitleArtifact artifact, Path workDir) {
        boolean allGone = true;
        for (ArtifactFileKind kind : ArtifactFileKind.values()) {
            if (!kind.belongsTo(artifact.getArtifactType())) {
                continue;
            }
            String fileName = artifact.getFileName(kind);
            if (fileName == null) {
                // 可选位本来就没有文件：不算「没回收干净」。必需位缺登记才是异常
                if (kind.isRequiredFor(artifact.getArtifactType())) {
                    log.warn("产物缺少必需文件位登记，无法确认文件已回收: taskId={}, type={}, lang={}, file={}",
                            artifact.getTaskId(), artifact.getArtifactType(), artifact.getLang(), kind.key());
                    allGone = false;
                }
                continue;
            }
            try {
                Files.deleteIfExists(workDir.resolve(fileName));
            } catch (IOException e) {
                // 只带文件名与异常类名，不带消息：删文件失败的异常消息就是完整路径，
                // 而目录名内嵌身份键（用户资源，脱敏红线）
                log.warn("产物文件删除失败，先不标已回收: taskId={}, type={}, lang={}, file={}, reason={}",
                        artifact.getTaskId(), artifact.getArtifactType(), artifact.getLang(), kind.key(),
                        e.getClass().getSimpleName());
                allGone = false;
            }
        }
        return allGone;
    }

    /**
     * 一版产物完不完好：必需位都登记了文件名，且工作区里确实有这个普通文件；
     * 可选位登记了也要在，没登记不算缺。
     *
     * <p>缺文件只记 WARN、按没有处理，<b>不改数据库状态</b>——文件可能只是被手工挪走，
     * 放回去就该重新可见。
     */
    private boolean isIntact(SubtitleArtifact artifact, Path workDir) {
        for (ArtifactFileKind kind : ArtifactFileKind.values()) {
            if (!kind.belongsTo(artifact.getArtifactType())) {
                continue;
            }
            String fileName = artifact.getFileName(kind);
            if (fileName == null) {
                if (kind.isRequiredFor(artifact.getArtifactType())) {
                    log.warn("产物缺少必需文件位登记，按没有处理: taskId={}, type={}, lang={}, file={}",
                            artifact.getTaskId(), artifact.getArtifactType(), artifact.getLang(), kind.key());
                    return false;
                }
                // 可选位没有登记：这一版本来就没有这个文件，正常
                continue;
            }
            if (!ArtifactFileUtils.isRegularFile(workDir.resolve(fileName))) {
                log.warn("产物文件不在工作区，按没有处理: taskId={}, type={}, lang={}, file={}",
                        artifact.getTaskId(), artifact.getArtifactType(), artifact.getLang(), kind.key());
                return false;
            }
        }
        return true;
    }
}
