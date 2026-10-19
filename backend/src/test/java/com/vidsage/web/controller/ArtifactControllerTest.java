package com.vidsage.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vidsage.application.subtitle.SubtitleArtifactQueryService;
import com.vidsage.application.subtitle.exception.ArtifactNotFoundException;
import com.vidsage.application.subtitle.query.ArtifactDownload;
import com.vidsage.application.subtitle.query.ArtifactFileSnapshot;
import com.vidsage.application.subtitle.query.ArtifactSnapshot;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.domain.subtitle.model.ArtifactFileKind;
import com.vidsage.domain.subtitle.model.ArtifactType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@link ArtifactController} 的切片测试：只装配 web 层（含全局 {@link ApiExceptionHandler}），
 * 查询服务用 {@link MockitoBean} 替换——清单与下载的形状由本类验，产物可用性判定在应用层测。
 *
 * <p>覆盖清单的字段 / 顺序 / 下载地址与空列表、下载的字节与 Content-Type、以及 404 / 400 的映射。
 */
@WebMvcTest(ArtifactController.class)
class ArtifactControllerTest {

    private static final String TASK_NO = "3f2a9c1e7b5d4a6089abcdef01234567";

    private static final String ARTIFACTS_URL = "/api/tasks/{taskNo}/artifacts";

    private static final String DOWNLOAD_URL = "/api/tasks/{taskNo}/artifacts/{type}/{file}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubtitleArtifactQueryService artifactQueryService;

    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    // ---------- 清单 ----------

    @Test
    void listRendersTypesOrderFingerprintAndFileUrls() throws Exception {
        when(artifactQueryService.listArtifacts(TASK_NO)).thenReturn(List.of(
                new ArtifactSnapshot(ArtifactType.ORIGINAL, "", 1, null, List.of(
                        new ArtifactFileSnapshot(ArtifactFileKind.SEGMENTS, 10),
                        new ArtifactFileSnapshot(ArtifactFileKind.SUBTITLES, 20))),
                new ArtifactSnapshot(ArtifactType.CLEANED, "", 2, "a1b2c3d4e5f60708", List.of(
                        new ArtifactFileSnapshot(ArtifactFileKind.SEGMENTS, 30),
                        new ArtifactFileSnapshot(ArtifactFileKind.SUBTITLES, 40),
                        new ArtifactFileSnapshot(ArtifactFileKind.CONTEXT, 50)))));

        MvcResult result = mockMvc.perform(get(ARTIFACTS_URL, TASK_NO))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("ORIGINAL"))
                .andExpect(jsonPath("$[0].lang").value(""))
                .andExpect(jsonPath("$[0].revision").value(1))
                .andExpect(jsonPath("$[0].keywordsFingerprint").value(nullValue()))
                .andExpect(jsonPath("$[0].files.segments.url").value(
                        "/api/tasks/" + TASK_NO + "/artifacts/ORIGINAL/segments.json"))
                .andExpect(jsonPath("$[0].files.segments.sizeBytes").value(10))
                .andExpect(jsonPath("$[0].files.subtitles.url").value(
                        "/api/tasks/" + TASK_NO + "/artifacts/ORIGINAL/subtitles.srt"))
                .andExpect(jsonPath("$[1].type").value("CLEANED"))
                .andExpect(jsonPath("$[1].revision").value(2))
                .andExpect(jsonPath("$[1].keywordsFingerprint").value("a1b2c3d4e5f60708"))
                .andExpect(jsonPath("$[1].files.context.url").value(
                        "/api/tasks/" + TASK_NO + "/artifacts/CLEANED/context.json"))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.indexOf("\"segments\":") < body.indexOf("\"subtitles\":"),
                "文件键顺序固定：segments 在 subtitles 之前");
        assertTrue(body.indexOf("\"subtitles\":") < body.indexOf("\"context\":"),
                "文件键顺序固定：context 排在最后");
        verify(artifactQueryService).listArtifacts(TASK_NO);
    }

    @Test
    void listPutsTranslatedLastAndCarriesLangOnEveryFileUrl() throws Exception {
        when(artifactQueryService.listArtifacts(TASK_NO)).thenReturn(List.of(
                new ArtifactSnapshot(ArtifactType.CLEANED, "", 1, "fp-1", List.of(
                        new ArtifactFileSnapshot(ArtifactFileKind.SEGMENTS, 10),
                        new ArtifactFileSnapshot(ArtifactFileKind.SUBTITLES, 20),
                        new ArtifactFileSnapshot(ArtifactFileKind.CONTEXT, 30))),
                new ArtifactSnapshot(ArtifactType.TRANSLATED, "zh-Hans", 1, null, List.of(
                        new ArtifactFileSnapshot(ArtifactFileKind.SEGMENTS, 40),
                        new ArtifactFileSnapshot(ArtifactFileKind.SUBTITLES, 50),
                        new ArtifactFileSnapshot(ArtifactFileKind.TERMS, 60)))));

        mockMvc.perform(get(ARTIFACTS_URL, TASK_NO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("CLEANED"))
                .andExpect(jsonPath("$[1].type").value("TRANSLATED"))
                .andExpect(jsonPath("$[1].lang").value("zh-Hans"))
                .andExpect(jsonPath("$[1].files.segments.url").value(
                        "/api/tasks/" + TASK_NO + "/artifacts/TRANSLATED/segments.json?lang=zh-Hans"))
                .andExpect(jsonPath("$[1].files.terms.url").value(
                        "/api/tasks/" + TASK_NO + "/artifacts/TRANSLATED/terms.json?lang=zh-Hans"));
    }

    @Test
    void listReturnsEmptyArrayWhenNoVersionIsReadable() throws Exception {
        when(artifactQueryService.listArtifacts(TASK_NO)).thenReturn(List.of());

        mockMvc.perform(get(ARTIFACTS_URL, TASK_NO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void listMapsUnknownTaskTo404() throws Exception {
        when(artifactQueryService.listArtifacts(TASK_NO)).thenThrow(new TaskNotFoundException(TASK_NO));

        mockMvc.perform(get(ARTIFACTS_URL, TASK_NO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString(TASK_NO)));
    }

    // ---------- 下载 ----------

    @Test
    void downloadSrtReturnsBytesWithTextContentType() throws Exception {
        String srt = "1\n00:00:00,000 --> 00:00:01,000\n你好\n";
        Path file = Files.writeString(tempDir.resolve("clean-subtitles.srt"), srt, StandardCharsets.UTF_8);
        when(artifactQueryService.openArtifact(TASK_NO, "CLEANED", "subtitles.srt", null))
                .thenReturn(new ArtifactDownload(file, ArtifactFileKind.SUBTITLES));

        mockMvc.perform(get(DOWNLOAD_URL, TASK_NO, "CLEANED", "subtitles.srt"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(srt))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("inline")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("clean-subtitles.srt")));
    }

    @Test
    void downloadJsonReturnsBytesWithJsonContentType() throws Exception {
        String json = "{\"segments\":[]}";
        Path file = Files.writeString(tempDir.resolve("clean-segments.json"), json, StandardCharsets.UTF_8);
        when(artifactQueryService.openArtifact(TASK_NO, "CLEANED", "segments.json", null))
                .thenReturn(new ArtifactDownload(file, ArtifactFileKind.SEGMENTS));

        mockMvc.perform(get(DOWNLOAD_URL, TASK_NO, "CLEANED", "segments.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(json))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("clean-segments.json")));
    }

    @Test
    void downloadPassesLangThroughToTheService() throws Exception {
        Path file = Files.writeString(tempDir.resolve("translated-segments-zh-hans.json"), "{}",
                StandardCharsets.UTF_8);
        when(artifactQueryService.openArtifact(TASK_NO, "TRANSLATED", "segments.json", "zh-Hans"))
                .thenReturn(new ArtifactDownload(file, ArtifactFileKind.SEGMENTS));

        mockMvc.perform(get(DOWNLOAD_URL, TASK_NO, "TRANSLATED", "segments.json").param("lang", "zh-Hans"))
                .andExpect(status().isOk());

        verify(artifactQueryService).openArtifact(TASK_NO, "TRANSLATED", "segments.json", "zh-Hans");
    }

    @Test
    void downloadMapsUnavailableArtifactTo404() throws Exception {
        when(artifactQueryService.openArtifact(TASK_NO, "CLEANED", "segments.json", null))
                .thenThrow(new ArtifactNotFoundException(TASK_NO, "CLEANED", "segments.json"));

        mockMvc.perform(get(DOWNLOAD_URL, TASK_NO, "CLEANED", "segments.json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("产物不可用")));
    }

    @Test
    void downloadMapsUnknownTaskTo404() throws Exception {
        when(artifactQueryService.openArtifact(TASK_NO, "ORIGINAL", "segments.json", null))
                .thenThrow(new TaskNotFoundException(TASK_NO));

        mockMvc.perform(get(DOWNLOAD_URL, TASK_NO, "ORIGINAL", "segments.json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString(TASK_NO)));
    }

    @Test
    void downloadMapsInvalidParametersTo400() throws Exception {
        when(artifactQueryService.openArtifact(TASK_NO, "NOPE", "segments.json", null))
                .thenThrow(new IllegalArgumentException("未知的产物类型：NOPE；可选值 ORIGINAL / CLEANED / TRANSLATED"));

        mockMvc.perform(get(DOWNLOAD_URL, TASK_NO, "NOPE", "segments.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("NOPE")));
    }
}
