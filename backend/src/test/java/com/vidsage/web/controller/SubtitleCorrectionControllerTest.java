package com.vidsage.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vidsage.application.subtitle.SubtitleCorrectionService;
import com.vidsage.application.subtitle.exception.CorrectionSegmentOutdatedException;
import com.vidsage.application.subtitle.query.CorrectionView;
import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.application.task.exception.TaskNotFoundException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link SubtitleCorrectionController} 的切片测试：只装配 web 层（含全局 {@link ApiExceptionHandler}），
 * 服务层用 {@link MockitoBean} 替换。
 *
 * <p>钉住四个端点的形状与状态码，以及三条纠错专属的映射：条目过期 409（{@code {error}}）、
 * 运行中 409（{@code {taskNo, runNo}}）、任务不存在 404。
 */
@WebMvcTest(SubtitleCorrectionController.class)
class SubtitleCorrectionControllerTest {

    private static final String TASK_NO = "3f2a9c1e7b5d4a6089abcdef01234567";

    private static final String SEGMENT_ID = "seg-0-1000";

    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 10, 25, 10, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubtitleCorrectionService correctionService;

    // ---------- 查询 ----------

    @Test
    void listReturnsItemsWithLowercaseFieldTokens() throws Exception {
        when(correctionService.list(TASK_NO)).thenReturn(List.of(
                new CorrectionView(1L, SEGMENT_ID, "text", "",
                        "机器原文", "用户改的原文", true, false, UPDATED_AT),
                new CorrectionView(2L, "seg-9-9999", "translation", "en",
                        "旧原文", "旧译文改动", false, true, UPDATED_AT)));

        mockMvc.perform(get("/api/tasks/{taskNo}/corrections", TASK_NO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].segmentId").value(SEGMENT_ID))
                .andExpect(jsonPath("$.items[0].field").value("text"))
                .andExpect(jsonPath("$.items[0].lang").value(""))
                .andExpect(jsonPath("$.items[0].sourceText").value("机器原文"))
                .andExpect(jsonPath("$.items[0].value").value("用户改的原文"))
                .andExpect(jsonPath("$.items[0].matched").value(true))
                .andExpect(jsonPath("$.items[0].sourceChanged").value(false))
                .andExpect(jsonPath("$.items[1].field").value("translation"))
                .andExpect(jsonPath("$.items[1].lang").value("en"))
                .andExpect(jsonPath("$.items[1].matched").value(false))
                .andExpect(jsonPath("$.items[1].sourceChanged").value(true));
    }

    @Test
    void listMapsMissingTaskToNotFound() throws Exception {
        when(correctionService.list("ghost")).thenThrow(new TaskNotFoundException("ghost"));

        mockMvc.perform(get("/api/tasks/{taskNo}/corrections", "ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("任务不存在：ghost")));
    }

    @Test
    void listRejectsBlankTaskNoBeforeTouchingService() throws Exception {
        mockMvc.perform(get("/api/tasks/{taskNo}/corrections", " "))
                .andExpect(status().isBadRequest());

        verify(correctionService, never()).list(anyString());
    }

    // ---------- 保存 ----------

    @Test
    void saveReturnsSavedCorrection() throws Exception {
        when(correctionService.save(TASK_NO, SEGMENT_ID, "text", null, "新文本"))
                .thenReturn(new CorrectionView(1L, SEGMENT_ID, "text", "",
                        "机器原文", "新文本", true, false, UPDATED_AT));

        mockMvc.perform(put("/api/tasks/{taskNo}/corrections/{segmentId}", TASK_NO, SEGMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"field":"text","value":"新文本"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segmentId").value(SEGMENT_ID))
                .andExpect(jsonPath("$.field").value("text"))
                .andExpect(jsonPath("$.sourceText").value("机器原文"))
                .andExpect(jsonPath("$.value").value("新文本"))
                .andExpect(jsonPath("$.matched").value(true));
    }

    @Test
    void savePassesFieldTokenAndLanguageThrough() throws Exception {
        when(correctionService.save(TASK_NO, SEGMENT_ID, "translation", "en", "新译文"))
                .thenReturn(new CorrectionView(2L, SEGMENT_ID, "translation", "en",
                        "原文", "新译文", true, false, UPDATED_AT));

        mockMvc.perform(put("/api/tasks/{taskNo}/corrections/{segmentId}", TASK_NO, SEGMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"field":"translation","lang":"en","value":"新译文"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.field").value("translation"))
                .andExpect(jsonPath("$.lang").value("en"));

        // 词形不做解析，原样转给应用层（解析归 domain 枚举，见 SubtitleCorrectionField#parse）
        verify(correctionService).save(TASK_NO, SEGMENT_ID, "translation", "en", "新译文");
    }

    @Test
    void saveMapsUnknownFieldToBadRequest() throws Exception {
        when(correctionService.save(eq(TASK_NO), eq(SEGMENT_ID), eq("nope"), any(), any()))
                .thenThrow(new IllegalArgumentException("未知的 field：nope；只能是 text（原文）或 translation（译文）"));

        mockMvc.perform(put("/api/tasks/{taskNo}/corrections/{segmentId}", TASK_NO, SEGMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"field":"nope","value":"新文本"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("text")));
    }

    @Test
    void saveMapsOutdatedSegmentToConflictWithMessage() throws Exception {
        // 条目过期走通用 {error} 形状（不是 {taskNo, runNo}）：用户要读到「刷新后重试」
        when(correctionService.save(eq(TASK_NO), eq(SEGMENT_ID), any(), any(), any()))
                .thenThrow(new CorrectionSegmentOutdatedException(TASK_NO, SEGMENT_ID));

        mockMvc.perform(put("/api/tasks/{taskNo}/corrections/{segmentId}", TASK_NO, SEGMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"field":"text","value":"新文本"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("字幕条目已过期，请刷新后重试")))
                .andExpect(jsonPath("$.taskNo").doesNotExist());
    }

    @Test
    void saveMapsRunningTaskToConflictShape() throws Exception {
        when(correctionService.save(eq(TASK_NO), eq(SEGMENT_ID), any(), any(), any()))
                .thenThrow(new TaskConflictException(TASK_NO, 2));

        mockMvc.perform(put("/api/tasks/{taskNo}/corrections/{segmentId}", TASK_NO, SEGMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"field":"text","value":"新文本"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.taskNo").value(TASK_NO))
                .andExpect(jsonPath("$.runNo").value(2))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    // ---------- 删除单条 ----------

    @Test
    void deleteReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/tasks/{taskNo}/corrections/{segmentId}", TASK_NO, SEGMENT_ID)
                        .param("field", "translation")
                        .param("lang", "en"))
                .andExpect(status().isNoContent());

        verify(correctionService).delete(TASK_NO, SEGMENT_ID, "translation", "en");
    }

    @Test
    void deleteMapsUnknownFieldToBadRequest() throws Exception {
        doThrow(new IllegalArgumentException(
                        "未知的 field：nope；只能是 text（原文）或 translation（译文）"))
                .when(correctionService).delete(TASK_NO, SEGMENT_ID, "nope", null);

        mockMvc.perform(delete("/api/tasks/{taskNo}/corrections/{segmentId}", TASK_NO, SEGMENT_ID)
                        .param("field", "nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("translation")));
    }

    // ---------- 清理失效 ----------

    @Test
    void clearStaleReturnsRemovedCount() throws Exception {
        when(correctionService.clearStale(TASK_NO)).thenReturn(3);

        mockMvc.perform(delete("/api/tasks/{taskNo}/corrections", TASK_NO)
                        .param("staleOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(3));
    }

    @Test
    void clearStaleRequiresExplicitStaleOnlyFlag() throws Exception {
        // 不给开关就 400：本端点不支持「清空全部」，避免误删还能用的纠错
        mockMvc.perform(delete("/api/tasks/{taskNo}/corrections", TASK_NO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("staleOnly=true")));

        verify(correctionService, never()).clearStale(anyString());
    }
}
