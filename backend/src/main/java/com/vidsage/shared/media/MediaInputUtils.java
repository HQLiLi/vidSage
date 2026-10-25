package com.vidsage.shared.media;

import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.workspace.Workspace;
import java.nio.file.Path;

/**
 * 从媒体身份重建管线输入。
 *
 * <p>URL 路线交给下载器一条链接；上传路线指向本机已经落盘的 {@code source.mp4}。
 * 变体选择与上传落点单源在这里——三条 run 用例都从同一处取，免得各自挑变体时走样。
 */
public final class MediaInputUtils {

    private MediaInputUtils() {
    }

    /**
     * 按来源类型挑 {@link MediaInput} 的变体。
     *
     * @param workspace 任务持有的媒体身份；不可为 {@code null}
     * @param workDir   这条输入的工作区目录（调用方已定位好）；上传路线拿它拼 {@code source.mp4}
     * @return 挑好的管线输入
     */
    public static MediaInput from(Workspace workspace, Path workDir) {
        return switch (workspace.getSourceType()) {
            case URL -> new MediaInput.RemoteUrl(workspace.getSourceUri());
            case UPLOAD -> new MediaInput.InWorkspace(workDir.resolve(MediaInput.UPLOAD_SOURCE_FILE_NAME));
        };
    }
}
