package com.vidsage.application.media;

import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.media.MediaInput;
import com.vidsage.domain.shared.SourceUri;
import com.vidsage.domain.shared.WorkspaceDir;
import com.vidsage.domain.shared.WorkspaceKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 存放产物的目录：按<b>输入内容寻址</b>给每条 {@link MediaInput} 解析出唯一的本地目录。
 *
 * <p>寻址公式单源在 domain/shared，本类只做「按输入变体分派 + 文件系统落盘」：
 * <ul>
 *   <li><b>输入身份</b>：{@link SourceUri}——{@link MediaInput.RemoteUrl} 取 URL 原文；
 *       {@link MediaInput.LocalPath} 取「绝对路径 + 文件大小 + 最后修改时间」，其中文件 stat 在
 *       本类完成（技术 I/O），身份公式在 domain；</li>
 *   <li><b>内容 key</b>：{@link WorkspaceKey#ofHash}——身份串 sha256 前 12 位十六进制；</li>
 *   <li><b>目录名</b>：{@link WorkspaceDir#of}——{@code sanitize(标签)-key}，唯一性完全由哈希保证，
 *       标签只是给人看的安全化名字。</li>
 * </ul>
 *
 * <p>目录<b>懒创建</b>：{@link #workspaceFor} 被调用时才 {@link Files#createDirectories}，
 * 构造函数不碰文件系统（否则上下文加载就会写盘）。
 */
@Slf4j
@Service
public class WorkspaceService {

    private final WorkspaceProperties properties;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param properties 工作目录根配置
     */
    public WorkspaceService(WorkspaceProperties properties) {
        this.properties = properties;
    }

    /**
     * 取（必要时创建）该输入对应的任务工作目录。
     *
     * <p>{@link MediaInput.InWorkspace} 是唯一例外路线：工作区目录已解析、随输入携带，直接命中
     * 零重算（MVP-1 上传路线，design.md「权衡记录」）——对它重新哈希会算出另一个目录，破坏
     * 任务 workspace_dir 与产物落点的一致性。
     *
     * @param input 媒体输入；不可为空
     * @return 工作目录的绝对路径，已确保存在
     * @throws IllegalArgumentException 输入为空
     * @throws IllegalStateException    目录创建失败（根路径不可写等）
     */
    public Path workspaceFor(MediaInput input) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.RemoteUrl（远程 URL）或 MediaInput.LocalPath（本地文件）");
        }
        if (input instanceof MediaInput.InWorkspace in) {
            return ensureCreated(in.workspaceDir());
        }
        SourceUri identity = normalize(input);
        WorkspaceKey key = WorkspaceKey.ofHash(identity.value());
        return ensureCreated(rootDir().resolve(WorkspaceDir.of(displayLabel(input), key).value()));
    }

    /**
     * 以<b>显式 key</b> 取（必要时创建）任务工作目录：上传路线（MVP-1）的 key 是文件内容
     * sha256 的前 12 位，只有把整份文件流写完后才算得出来，走不了「先 normalize 再 ofHash」的
     * 常规路线。目录名 = {@code <安全化名字>-<显式 key>}，其余行为与
     * {@link #workspaceFor(MediaInput)} 完全一致。
     *
     * <p>URL / 本地路径两条旧路线<b>不受影响</b>，仍走 {@link #workspaceFor(MediaInput)}。
     *
     * @param label 供人阅读的标签（上传原始文件名等）；可为 {@code null} 或空白
     * @param key   内容寻址 key（{@link WorkspaceKey}，12 位十六进制）；取值由调用方保证
     * @return 工作目录的绝对路径，已确保存在
     * @throws IllegalStateException 目录创建失败（根路径不可写等）
     */
    public Path workspaceFor(String label, WorkspaceKey key) {
        return resolveAndCreate(WorkspaceDir.of(label, key).value());
    }

    /**
     * 工作区根目录的绝对路径（<b>不建目录</b>）。
     *
     * <p>给 MVP-1 的两条新链路用：上传路线在根下落临时文件、按内容寻址归位；任务执行时
     * 按持久化的 workspace_dir 还原媒体文件绝对路径。目录创建仍由 {@link #workspaceFor}
     * 的懒创建触发，本方法保持无副作用。
     *
     * @return 根目录绝对路径（root 未配置时回退 {@link WorkspaceDir#FALLBACK_NAME}，与 workspaceFor 同口径）
     */
    public Path workspaceRoot() {
        return rootDir().toAbsolutePath();
    }

    /**
     * 工作区根目录（按配置解析，<b>不建目录</b>；root 未配置时回退
     * {@link WorkspaceDir#FALLBACK_NAME}）。
     */
    private Path rootDir() {
        return Path.of(properties.root() == null ? WorkspaceDir.FALLBACK_NAME : properties.root());
    }

    /**
     * 按目录名解析绝对路径并懒创建（workspaceFor 常规路线的公共尾部）。
     */
    private Path resolveAndCreate(String dirName) {
        return ensureCreated(rootDir().resolve(dirName));
    }

    /**
     * 确保目录存在并返回其绝对路径（workspaceFor 两条路线的公共尾部；已存在时是幂等空操作）。
     */
    private Path ensureCreated(Path dir) {
        Path absolute = dir.toAbsolutePath();
        try {
            // 如果已存在则静默返回
            Files.createDirectories(absolute);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建任务工作目录：" + absolute
                    + "；请检查 vidsage.workspace.root 是否存在且可写。原因：" + e.getMessage(), e);
        }
        log.debug("任务工作目录: dir={}", absolute.getFileName());
        return absolute;
    }

    /**
     * 输入的规范化身份：内容寻址的哈希输入，按 {@code MediaInput} 变体分派到 {@link SourceUri}
     * 的两条公式（URL 原文 / 本地三元组）。
     *
     * <p>public static：TaskService（application/task）落库 workspace_key 跨包复用；测试可直接断言
     * 「同一输入同串、不同输入不同串」，不必落盘。
     *
     * @param input 媒体输入（不可为 {@code null}）
     * @return 规范化后的身份值对象
     * @throws IllegalStateException 输入为 {@link MediaInput.InWorkspace}——它的目录已随输入解析，
     *                               不参与哈希重算（重算正是 InWorkspace 变体要防住的 bug）
     */
    public static SourceUri normalize(MediaInput input) {
        return switch (input) {
            case MediaInput.RemoteUrl url -> SourceUri.ofRemoteUrl(url.value());
            case MediaInput.LocalPath local -> localPathKey(local.value());
            case MediaInput.InWorkspace ignored -> throw new IllegalStateException(
                    "MediaInput.InWorkspace 自带已解析的工作区目录，不参与哈希重算："
                            + "请直接用其 workspaceDir，不要走 normalize + ofHash 路线");
        };
    }

    /**
     * 本地文件的 stat 与身份组装：文件大小与最后修改时间由本类读取（技术 I/O），读到的三元组交给
     * {@link SourceUri#ofLocalFile} 拼身份公式（绝对路径|大小|毫秒）。
     *
     * <p>文件不存在或不可读时大小与时间戳取占位值——<b>此处不报错</b>：
     * 「文件不存在 / 不可读」的权威报错属于输入获取适配器，服务层重复报一次只会让错误来源含糊。
     *
     * @param path 本地文件路径；为 {@code null} 时返回占位身份
     * @return 规范化后的身份值对象
     */
    static SourceUri localPathKey(Path path) {
        if (path == null) {
            return SourceUri.ofLocalFile(null, SourceUri.UNAVAILABLE, SourceUri.UNAVAILABLE);
        }
        Path absolute = path.toAbsolutePath().normalize();
        long size = SourceUri.UNAVAILABLE;
        long lastModifiedMs = SourceUri.UNAVAILABLE;
        try {
            if (Files.isRegularFile(absolute)) {
                size = Files.size(absolute);
                lastModifiedMs = Files.getLastModifiedTime(absolute).toMillis();
            }
        } catch (IOException e) {
            log.debug("读取本地文件属性失败，按占位值计算工作目录: file={}, reason={}",
                    absolute.getFileName(), e.getMessage());
        }
        return SourceUri.ofLocalFile(absolute, size, lastModifiedMs);
    }

    /**
     * 供人阅读的标签：远程取 URL，本地取文件名，工作区内取媒体文件名。
     *
     * <p>包级 static：可直测。
     */
    static String displayLabel(MediaInput input) {
        return switch (input) {
            case MediaInput.RemoteUrl url -> url.value() == null ? "" : url.value();
            case MediaInput.LocalPath local -> {
                Path value = local.value();
                Path fileName = value == null ? null : value.getFileName();
                yield fileName == null ? "" : fileName.toString();
            }
            // InWorkspace 的工作区目录名在提交口已按标签拼好，此处永不参与目录名重算
            case MediaInput.InWorkspace in -> {
                Path fileName = in.value() == null ? null : in.value().getFileName();
                yield fileName == null ? "" : fileName.toString();
            }
        };
    }
}
