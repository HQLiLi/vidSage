package com.vidsage.application.subtitle;

import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactFileKind;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.port.SubtitleArtifactRepository;
import com.vidsage.shared.fs.ArtifactFileUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 产物版本的读写门面：开始一版（占位）、落定一版（换版）、按「可读的当前版」查。
 *
 * <p>写与读共用同一套文件位解析（{@link ArtifactFileKind}），所以「该类型带哪几个文件」
 * 只有一处定义；仓储只负责落库，版本交替的顺序收在这里。
 *
 * <p><b>换版落定不包事务</b>：先算哈希、再降级旧版、最后置新版为 READY。崩在中间只会让这个
 * 产物类型暂时没有当前版，占位行由收口标成失败，重跑一次即收敛。
 *
 * <p><b>可读判定只看「行是 READY 且文件是普通文件」</b>：文件不在了就记一条 WARN、按没有处理，
 * 不改任何状态；0 字节文件算存在（空转写是合法产物）。
 */
@Slf4j
@Component
public class SubtitleArtifactStore {

    private final SubtitleArtifactRepository repository;

    public SubtitleArtifactStore(SubtitleArtifactRepository repository) {
        this.repository = repository;
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
     * 落定一版：算文件名与哈希 → 旧当前版降级并清空文件列 → 新版写文件列并置 READY。
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
        ArtifactType type = creating.getArtifactType();
        Map<ArtifactFileKind, String> names = new EnumMap<>(ArtifactFileKind.class);
        Map<ArtifactFileKind, String> hashes = new EnumMap<>(ArtifactFileKind.class);
        for (ArtifactFileKind kind : ArtifactFileKind.values()) {
            if (!kind.belongsTo(type)) {
                continue;
            }
            String fileName = kind.specFor(type).getFileName(creating.getLang());
            hashes.put(kind, ArtifactFileUtils.sha256(workDir.resolve(fileName)));
            names.put(kind, fileName);
        }

        // 降级旧版放在哈希之后：哈希失败时旧版还是可用的当前版
        repository.findReadyByTaskIdAndTypeAndLang(creating.getTaskId(), type, creating.getLang())
                .ifPresent(old -> repository.markSupersededAndClearFiles(old.getId()));

        creating.completeCreation(
                names.get(ArtifactFileKind.SEGMENTS), names.get(ArtifactFileKind.SUBTITLES),
                names.get(ArtifactFileKind.CONTEXT), names.get(ArtifactFileKind.TERMS),
                hashes.get(ArtifactFileKind.SEGMENTS), hashes.get(ArtifactFileKind.SUBTITLES),
                hashes.get(ArtifactFileKind.CONTEXT), hashes.get(ArtifactFileKind.TERMS));
        repository.updateReady(creating);
        return creating;
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
