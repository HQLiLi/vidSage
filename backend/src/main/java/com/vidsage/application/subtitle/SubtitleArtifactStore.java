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
 * 产物版本的读写门面：开始一版（占位）、落定一版（换版）、按「可读的当前版」查。
 *
 * <p>写与读共用同一套文件位解析（{@link ArtifactFileKind}），所以「该类型带哪几个文件」
 * 只有一处定义；仓储只负责落库，版本交替的顺序收在这里。
 *
 * <p><b>落定有两个版本，按「有没有下游」分</b>：
 * <ul>
 *   <li>{@link #settleArtifact} —— 无下游的类型（原始转写、将来的译文）：换版不包事务，
 *       崩在中间只会让这个产物类型暂时没有当前版，占位行由收口标成失败，重跑一次即收敛。</li>
 *   <li>{@link #settleCleanedAndSupersedeDownstream} —— 清洗产物：下面挂着译文，
 *       旧版降级、下游作废、新行生效必须一起对，所以圈在同一个事务里。</li>
 * </ul>
 *
 * <p><b>可读判定只看「行是 READY 且文件是普通文件」</b>：文件不在了就记一条 WARN、按没有处理，
 * 不改任何状态；0 字节文件算存在（空转写是合法产物）。
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

        // 降级旧版放在哈希之后：哈希失败时旧版还是可用的当前版
        repository.findReadyByTaskIdAndTypeAndLang(creating.getTaskId(), creating.getArtifactType(),
                        creating.getLang())
                .ifPresent(old -> repository.markSupersededAndClearFiles(old.getId()));

        markReady(creating, files);
        return creating;
    }

    /**
     * 落定一版<b>清洗</b>产物：旧版降级、下游译文作废、新行生效在同一个事务里；事务提交后再删下游的文件。
     *
     * <p>为什么单独一个方法：清洗是唯一有下游的产物类型，这一步会连带把译文作废，调用点应当一眼看出来。
     * 它和 {@link #settleArtifact} 的差别只在「下游」这一件事，前两步（算文件、降级旧版）完全同款。
     *
     * <p><b>只有内容真的变了才作废下游</b>：清洗命中检查点时会跳过重跑、文件一个字节没动，这时照样会落
     * 一版新版本号，但下游译文仍然是对的——白作废等于让用户重新烧一遍模型。判据是三个文件位的哈希与旧行
     * 逐个比：哈希相同就是内容相同。
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
            // 旧行必须在清列之前读：清完文件列与哈希列，就再也比不出「内容变了没有」
            Optional<SubtitleArtifact> previousReady = repository.findReadyByTaskIdAndTypeAndLang(
                    creating.getTaskId(), creating.getArtifactType(), creating.getLang());
            previousReady.ifPresent(previous -> repository.markSupersededAndClearFiles(previous.getId()));

            // 先读出来：事务提交后要按它们的文件列删文件。改状态另走一句批量，不逐行。
            List<SubtitleArtifact> translationsToSupersede = isContentChanged(previousReady.orElse(null), files)
                    ? findDownstreamTranslations(creating.getTaskId())
                    : List.of();
            if (!translationsToSupersede.isEmpty()) {
                // 只标状态，文件列留着：删文件时要用它的路径
                repository.markSupersededByTaskIdAndType(creating.getTaskId(), ArtifactType.TRANSLATED);
            }

            markReady(creating, files);
            return translationsToSupersede;
        });

        for (SubtitleArtifact translation : translationsToReclaim == null
                ? List.<SubtitleArtifact>of() : translationsToReclaim) {
            try {
                if (reclaimFilesOf(translation, workDir)) {
                    repository.markDeleted(translation.getId());
                }
            } catch (RuntimeException e) {
                // 新版已经 READY、事务也提交了，回收失败不该把这一趟判成失败；留下的「已作废但文件还在」
                // 正是对账会报出来的那一类。不传异常对象：它的消息就是完整路径
                log.warn("下游译文回收失败，该行留在待回收状态: taskId={}, type={}, lang={}, reason={}",
                        translation.getTaskId(), translation.getArtifactType(), translation.getLang(),
                        e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 查某一版「可读的当前版」：行是 READY，且该类型应带的每个文件都在。
     *
     * @param taskId  所属字幕任务
     * @param type    产物类型
     * @param lang    语种；没有语言维度传空串
     * @param workDir 工作区目录
     * @return 可读的当前版；没有或文件不齐时为空
     */
    public Optional<SubtitleArtifact> findReadableReady(long taskId, ArtifactType type, String lang, Path workDir) {
        return repository.findReadyByTaskIdAndTypeAndLang(taskId, type, lang)
                .filter(artifact -> isReadable(artifact, workDir));
    }

    /**
     * 列该任务全部「可读的当前版」，不对产物类型与语种排序。
     *
     * @param taskId  所属字幕任务
     * @param workDir 工作区目录
     * @return 文件齐全的当前版；一个都没有时是空列表
     */
    public List<SubtitleArtifact> listReadableReady(long taskId, Path workDir) {
        List<SubtitleArtifact> readable = new ArrayList<>();
        for (SubtitleArtifact artifact : repository.findReadyByTaskId(taskId)) {
            if (isReadable(artifact, workDir)) {
                readable.add(artifact);
            }
        }
        return List.copyOf(readable);
    }

    /**
     * 一版产物算出来的文件：文件名与哈希各按文件位一张表，只含该类型带的那些位。
     */
    private record ArtifactFiles(Map<ArtifactFileKind, String> names, Map<ArtifactFileKind, String> hashes) {
    }

    /**
     * 按产物类型求出该带的文件位，逐个求文件名并算 sha256。
     *
     * <p>文件此刻必须已经由产出方原子替换落盘；读不到就抛异常，这时调用方手里还什么都没动。
     */
    private static ArtifactFiles resolveArtifactFiles(ArtifactType type, String lang, Path workDir) {
        Map<ArtifactFileKind, String> names = new EnumMap<>(ArtifactFileKind.class);
        Map<ArtifactFileKind, String> hashes = new EnumMap<>(ArtifactFileKind.class);
        for (ArtifactFileKind kind : ArtifactFileKind.values()) {
            if (!kind.belongsTo(type)) {
                continue;
            }
            String fileName = kind.specFor(type).getFileName(lang);
            hashes.put(kind, ArtifactFileUtils.sha256(workDir.resolve(fileName)));
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
     * 这一版清洗的内容与旧版比变了没有：该带的文件位逐个比哈希，任一不同、或旧行没记，就算变了。
     *
     * <p>旧行不存在时也返回「变了」——首跑时下游必然为空，这么写等于空操作，方向往安全靠。
     */
    private static boolean isContentChanged(SubtitleArtifact previousReady, ArtifactFiles files) {
        if (previousReady == null) {
            return true;
        }
        for (ArtifactFileKind kind : ArtifactFileKind.values()) {
            if (!kind.belongsTo(previousReady.getArtifactType())) {
                continue;
            }
            String before = previousReady.getSha256(kind);
            if (before == null || !before.equals(files.hashes().get(kind))) {
                return true;
            }
        }
        return false;
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
     * <p>全部删干净（或本来就不在）才返回 {@code true} —— 只有这样才能说「文件已确认回收」；
     * 缺登记或删不动都返回 {@code false}，让那一行停在「待删」状态，留给对账报出来。
     */
    private boolean reclaimFilesOf(SubtitleArtifact artifact, Path workDir) {
        boolean allGone = true;
        for (ArtifactFileKind kind : ArtifactFileKind.values()) {
            if (!kind.belongsTo(artifact.getArtifactType())) {
                continue;
            }
            String fileName = artifact.getFileName(kind);
            if (fileName == null) {
                log.warn("产物缺少文件位登记，无法确认文件已回收: taskId={}, type={}, lang={}, file={}",
                        artifact.getTaskId(), artifact.getArtifactType(), artifact.getLang(), kind.key());
                allGone = false;
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
     * 一版产物能不能读：每个应带的文件位都登记了文件名，且工作区里确实有这个普通文件。
     *
     * <p>缺文件只记 WARN、按没有处理，<b>不改数据库状态</b>——文件可能只是被手工挪走，
     * 放回去就该重新可见。
     */
    private boolean isReadable(SubtitleArtifact artifact, Path workDir) {
        for (ArtifactFileKind kind : ArtifactFileKind.values()) {
            if (!kind.belongsTo(artifact.getArtifactType())) {
                continue;
            }
            String fileName = artifact.getFileName(kind);
            if (fileName == null) {
                log.warn("产物缺少文件位登记，按没有处理: taskId={}, type={}, lang={}, file={}",
                        artifact.getTaskId(), artifact.getArtifactType(), artifact.getLang(), kind.key());
                return false;
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
