package com.vidsage.domain.media.model;

import com.vidsage.domain.shared.model.WorkspaceKey;
import com.vidsage.domain.task.model.TaskInputType;
import java.nio.file.Path;

/**
 * 媒体输入来源：契约只描述「媒体从哪里来」，不绑定任何实现方的获取方式。
 *
 * <p>三条路线（远程 URL 下载 / 本地文件采用 / 已在工作区内的上传文件）对上层是同一个契约——
 * 上层按子类型分派适配器，不感知是下载还是直接采用。
 *
 * <p>负面约束：本类型只承载「位置」，不含进度监听等编排参数（那些由调用方另行传入）；
 * 也不要用它表达尚未实现的来源（如抖音）。唯一携带工作区元数据的是 {@link InWorkspace}——
 * 它描述的是「位置已解析完毕」的输入，不携带工作区目录就得靠下游按「路径+大小+mtime」重算，
 * 上传路线会因此算出另一个目录（MVP-1 design.md「权衡记录·上传路线的工作区归属」）。
 */
public sealed interface MediaInput {

    /**
     * 上传路线在工作区落盘的媒体文件名（design.md「依赖与配置」）：提交时临时文件按此名归位，
     * 管线重建输入时按此名定位——名字单源在此，两个时机不会漂移（2026-10-06 自 VideoTaskService
     * 下沉：跨类引用的产物命名规则归位到配对映射的家）。
     */
    String UPLOAD_SOURCE_FILE_NAME = "source.mp4";

    /**
     * 从任务身份字段重建媒体输入——TaskInputType → MediaInput 变体映射的单源：
     * URL → {@link RemoteUrl}（原文直通，各阶段服务按同一公式重算工作区，落点一致）；
     * UPLOAD → {@link InWorkspace}（媒体固定在工作区目录的 {@code source.mp4}，位置随输入携带、
     * 下游零重算——若包成 LocalPath 会被按「路径+大小+mtime」重算出另一个目录，任务 workspace_dir
     * 与产物落点分家，见 design.md「权衡记录·上传路线的工作区归属」）。
     *
     * <p>提交路线（{@code VideoTaskService}）与管线重建（{@code VideoTaskPipeline}）共用本工厂，
     * 保证同一任务在两个时机得到同一变体与同一落点（2026-10-06 自两处配对重复下沉）。
     * 各变体只取自己需要的字段，其余传 {@code null} 亦可——这正是映射规则的一部分：URL 变体
     * 不携带工作区、UPLOAD 变体不携带原始名（位置已由 {@code source.mp4} 定死）。
     *
     * <p>工作区根解析与相对目录拼接是文件系统事实，由调用方（应用层）取得后传入，本工厂不做任何 I/O。
     *
     * @param inputType    任务输入类型（决定变体）
     * @param sourceUri    持久化的来源（URL 原文或上传文件原始名）
     * @param workspaceDir 任务工作区目录（绝对路径）；UPLOAD 路线必传，URL 路线不使用
     * @param workspaceKey 内容寻址 key；UPLOAD 路线必传，URL 路线不使用
     * @return 与输入类型配对的媒体输入
     */
    static MediaInput ofTaskInput(TaskInputType inputType, String sourceUri,
                                  Path workspaceDir, WorkspaceKey workspaceKey) {
        return switch (inputType) {
            case URL -> new RemoteUrl(sourceUri);
            case UPLOAD -> new InWorkspace(workspaceDir.resolve(UPLOAD_SOURCE_FILE_NAME), workspaceDir,
                    workspaceKey);
        };
    }

    /**
     * 公网可访问的媒体页面地址，由下载器负责解析出可下载的媒体流。
     *
     * @param value 完整 URL；<b>不要传本地文件路径</b>（本地文件请用 {@link LocalPath}）
     */
    record RemoteUrl(String value) implements MediaInput {
    }

    /**
     * 本机可读的媒体文件路径，直接采用，不下载、不拷贝。
     *
     * @param value 绝对或相对路径；调用方负责保证其存在且可读
     */
    record LocalPath(Path value) implements MediaInput {
    }

    /**
     * 已在任务工作区内的媒体文件（MVP-1 上传路线专用）：上传落盘后按内容 sha256 定址的工作区
     * 目录已解析完毕，本变体把它<b>携带直通下游</b>，{@code WorkspaceService.workspaceFor} 对它
     * 直接命中、零重算——若以 {@link LocalPath} 下传，下游会按「绝对路径+大小+mtime」重算出
     * 另一个目录，任务 workspace_dir 与产物落点分家，且 mtime 随重传漂移、断点复用失效。
     *
     * @param value        媒体文件路径（已在工作区内，如 {@code <工作区>/source.mp4}）；调用方保证存在
     * @param workspaceDir 已解析的工作区目录（绝对路径），与 value 所在目录一致
     * @param workspaceKey 内容寻址 key（{@link WorkspaceKey}，12 位十六进制），即 workspaceDir 目录名的 key 后缀
     */
    record InWorkspace(Path value, Path workspaceDir, WorkspaceKey workspaceKey) implements MediaInput {
    }
}
