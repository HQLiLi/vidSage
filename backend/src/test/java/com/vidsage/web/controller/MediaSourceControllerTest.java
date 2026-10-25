package com.vidsage.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vidsage.application.media.MediaSourceQueryService;
import com.vidsage.application.media.exception.MediaSourceNotFoundException;
import com.vidsage.application.media.query.MediaSource;
import com.vidsage.application.task.exception.TaskNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@link MediaSourceController} 的切片测试：只装配 web 层（含全局 {@link ApiExceptionHandler}），
 * 定位与嗅探用 {@link MockitoBean} 替换，媒体文件用临时目录里的真文件。
 *
 * <p>覆盖 200 / 206 / 416 / 404 / HEAD 的头部与字节，以及「多段或语法非法的 Range 按无 Range 处理」。
 */
@WebMvcTest(MediaSourceController.class)
class MediaSourceControllerTest {

    private static final String TASK_NO = "3f2a9c1e7b5d4a6089abcdef01234567";

    private static final String SOURCE_URL = "/api/media/source/{taskNo}";

    private static final int FILE_SIZE = 1000;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MediaSourceQueryService mediaSourceQueryService;

    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private byte[] fileBytes;

    @BeforeEach
    void writeMediaFile() throws IOException {
        fileBytes = new byte[FILE_SIZE];
        for (int i = 0; i < fileBytes.length; i++) {
            fileBytes[i] = (byte) ('a' + (i % 26));
        }
        Path file = tempDir.resolve("source.mp4");
        Files.write(file, fileBytes);
        when(mediaSourceQueryService.locate(TASK_NO))
                .thenReturn(new MediaSource(file, "source.mp4", "video/mp4", FILE_SIZE));
    }

    @Test
    void servesWholeFileWhenNoRangeIsGiven() throws Exception {
        mockMvc.perform(get(SOURCE_URL, TASK_NO))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, FILE_SIZE))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("source.mp4")))
                .andExpect(content().bytes(fileBytes));
    }

    @Test
    void servesSingleRangeAsPartialContent() throws Exception {
        MvcResult result = mockMvc.perform(get(SOURCE_URL, TASK_NO)
                        .header(HttpHeaders.RANGE, "bytes=10-109"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 10-109/" + FILE_SIZE))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 100))
                .andReturn();

        assertArrayEquals(Arrays.copyOfRange(fileBytes, 10, 110),
                result.getResponse().getContentAsByteArray());
    }

    @Test
    void servesSuffixRangeAsLastBytes() throws Exception {
        // bytes=-100：播放器取尾部时发这种区间，起点由文件长度倒推
        MvcResult result = mockMvc.perform(get(SOURCE_URL, TASK_NO)
                        .header(HttpHeaders.RANGE, "bytes=-100"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE,
                        "bytes " + (FILE_SIZE - 100) + "-" + (FILE_SIZE - 1) + "/" + FILE_SIZE))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 100))
                .andReturn();

        assertArrayEquals(Arrays.copyOfRange(fileBytes, FILE_SIZE - 100, FILE_SIZE),
                result.getResponse().getContentAsByteArray());
    }

    @Test
    void clampsRangeEndBeyondFileSize() throws Exception {
        // 末尾超出文件长度的区间按文件末尾截断，不能越界读
        MvcResult result = mockMvc.perform(get(SOURCE_URL, TASK_NO)
                        .header(HttpHeaders.RANGE, "bytes=900-99999"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE,
                        "bytes 900-" + (FILE_SIZE - 1) + "/" + FILE_SIZE))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 100))
                .andReturn();

        assertArrayEquals(Arrays.copyOfRange(fileBytes, 900, FILE_SIZE),
                result.getResponse().getContentAsByteArray());
    }

    @Test
    void mapsUnsatisfiableRangeTo416() throws Exception {
        mockMvc.perform(get(SOURCE_URL, TASK_NO).header(HttpHeaders.RANGE, "bytes=" + FILE_SIZE + "-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */" + FILE_SIZE))
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void ignoresMultiRangeAndServesWholeFile() throws Exception {
        mockMvc.perform(get(SOURCE_URL, TASK_NO).header(HttpHeaders.RANGE, "bytes=0-9,20-29"))
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, FILE_SIZE))
                .andExpect(content().bytes(fileBytes));
    }

    @Test
    void ignoresMalformedRangeAndServesWholeFile() throws Exception {
        mockMvc.perform(get(SOURCE_URL, TASK_NO).header(HttpHeaders.RANGE, "bytes=abc"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(fileBytes));
    }

    @Test
    void headReturnsHeadersWithoutBody() throws Exception {
        MvcResult result = mockMvc.perform(head(SOURCE_URL, TASK_NO))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, FILE_SIZE))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                .andReturn();

        assertEquals(0, result.getResponse().getContentAsByteArray().length);
    }

    @Test
    void headIgnoresRangeAndReturnsWholeFileHeaders() throws Exception {
        MvcResult result = mockMvc.perform(head(SOURCE_URL, TASK_NO)
                        .header(HttpHeaders.RANGE, "bytes=10-109"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, FILE_SIZE))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
                .andReturn();

        assertEquals(0, result.getResponse().getContentAsByteArray().length);
    }

    @Test
    void mapsMissingMediaSourceTo404() throws Exception {
        when(mediaSourceQueryService.locate(TASK_NO))
                .thenThrow(new MediaSourceNotFoundException(TASK_NO));

        mockMvc.perform(get(SOURCE_URL, TASK_NO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("媒体源不可用")));
    }

    @Test
    void mapsUnknownTaskTo404() throws Exception {
        when(mediaSourceQueryService.locate(TASK_NO))
                .thenThrow(new TaskNotFoundException(TASK_NO));

        mockMvc.perform(get(SOURCE_URL, TASK_NO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString(TASK_NO)));
    }
}
