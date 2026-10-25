package com.vidsage.application.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vidsage.application.media.exception.MediaSourceNotFoundException;
import com.vidsage.application.media.query.MediaSource;
import com.vidsage.application.task.SubtitleTaskFinder;
import com.vidsage.domain.intermediate.IntermediateFile;
import com.vidsage.domain.intermediate.model.IntermediateFileType;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.workspace.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MediaSourceQueryService} 的单元测试：不起容器，端口用 Mockito 替换，媒体文件写真临时目录。
 *
 * <p>覆盖「有 READY 行且文件在」「没有 READY 行」「文件被挪走」「文件为空」「多条 READY」五条。
 */
class MediaSourceQueryServiceTest {

    private static final String TASK_NO = "3f2a9c1e7b5d4a6089abcdef01234567";

    private static final long TASK_ID = 42L;

    private final SubtitleTaskFinder finder = mock(SubtitleTaskFinder.class);

    private final IntermediateFileStore fileStore = mock(IntermediateFileStore.class);

    private final MediaSourceQueryService service = new MediaSourceQueryService(finder, fileStore);

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    @BeforeEach
    void stubWorkspaceLookup() {
        SubtitleTask task = SubtitleTask.restore(TASK_ID, new TaskNo(TASK_NO), 7L,
                LocalDateTime.now(), LocalDateTime.now());
        Workspace workspace = mock(Workspace.class);
        when(finder.require(TASK_NO)).thenReturn(task);
        when(finder.requireWorkspace(task)).thenReturn(workspace);
        when(finder.workspaceDir(workspace)).thenReturn(workDir);
    }

    @Test
    void locatesReadySourceMediaAndSniffsContentType() throws IOException {
        byte[] mp4 = mp4Header();
        Path file = Files.write(workDir.resolve("source.mp4"), mp4);
        when(fileStore.findReady(TASK_ID)).thenReturn(List.of(
                IntermediateFile.ofReady(TASK_ID, "source.mp4", IntermediateFileType.SOURCE_MEDIA, 1L)));

        MediaSource source = service.locate(TASK_NO);

        assertEquals(file, source.path());
        assertEquals("source.mp4", source.fileName());
        assertEquals("video/mp4", source.contentType());
        assertEquals(mp4.length, source.sizeBytes());
    }

    @Test
    void throwsWhenNoReadySourceMediaRow() {
        when(fileStore.findReady(TASK_ID)).thenReturn(List.of(
                IntermediateFile.ofReady(TASK_ID, "source.info.json", IntermediateFileType.INFO_JSON, 1L)));

        assertThrows(MediaSourceNotFoundException.class, () -> service.locate(TASK_NO));
    }

    @Test
    void throwsWhenRegisteredFileIsGone() {
        when(fileStore.findReady(TASK_ID)).thenReturn(List.of(
                IntermediateFile.ofReady(TASK_ID, "source.mp4", IntermediateFileType.SOURCE_MEDIA, 1L)));

        assertThrows(MediaSourceNotFoundException.class, () -> service.locate(TASK_NO));
    }

    @Test
    void throwsWhenRegisteredFileIsEmpty() throws IOException {
        Files.write(workDir.resolve("source.mp4"), new byte[0]);
        when(fileStore.findReady(TASK_ID)).thenReturn(List.of(
                IntermediateFile.ofReady(TASK_ID, "source.mp4", IntermediateFileType.SOURCE_MEDIA, 1L)));

        assertThrows(MediaSourceNotFoundException.class, () -> service.locate(TASK_NO));
    }

    @Test
    void throwsIllegalStateWhenMultipleReadyRowsExist() {
        when(fileStore.findReady(TASK_ID)).thenReturn(List.of(
                IntermediateFile.ofReady(TASK_ID, "source.mp4", IntermediateFileType.SOURCE_MEDIA, 1L),
                IntermediateFile.ofReady(TASK_ID, "source.mkv", IntermediateFileType.SOURCE_MEDIA, 2L)));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.locate(TASK_NO));

        assertTrue(e.getMessage().contains("多条 READY 的 SOURCE_MEDIA"));
    }

    /** 造一个能被嗅探成 video/mp4 的文件头。 */
    private static byte[] mp4Header() {
        return new byte[] {0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
    }
}
