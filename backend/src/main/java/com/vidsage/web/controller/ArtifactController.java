package com.vidsage.web.controller;

import com.vidsage.application.subtitle.SubtitleArtifactQueryService;
import com.vidsage.application.subtitle.query.ArtifactDownload;
import com.vidsage.web.dto.ArtifactResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 字幕产物的 HTTP 入口：当前有效版本的清单与按文件位下载。
 *
 * <p>与任务入口分开：任务那边管提交 / 查询 / 进度订阅，这里只管产物文件，两类关注点各占一个类。
 *
 * <p>本类只做「接收 → 卫语句 → 委托 → 返回」。参数合法性（类型 / 文件名段 / 语种）与产物可用性
 * 都由查询服务判：前者映射 400，后者映射 404。
 */
@Slf4j
@Tag(name = "产物", description = "字幕产物清单与下载：只给当前有效版本，历史版本不列出也不能下载")
@RestController
@RequestMapping("/api/tasks")
public class ArtifactController {

    /** SRT 就是文本，声明字符集让浏览器直接按 UTF-8 读。 */
    private static final MediaType SRT_MEDIA_TYPE = MediaType.valueOf("text/plain;charset=UTF-8");

    private final SubtitleArtifactQueryService artifactQueryService;

    public ArtifactController(SubtitleArtifactQueryService artifactQueryService) {
        this.artifactQueryService = artifactQueryService;
    }

    /**
     * 列该任务当前完好的全部产物版本：原始转写在前、清洗次之、译文最后，同类型按语种升序。
     *
     * <p>文件被挪走的版本整版不出现（不列半版产物）。
     *
     * @param taskNo 提交响应返回的任务号
     * @return 产物清单；一个可用版本都没有时是空列表
     * @throws IllegalArgumentException                                     taskNo 为空（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     */
    @Operation(summary = "列出当前有效的产物",
            description = "只列当前有效版本（按类型 ORIGINAL → CLEANED → TRANSLATED，同类型按语种升序）；"
                    + "每项给版本号与文件列表，文件项带下载地址与字节数。"
                    + "清洗产物的 keywordsFingerprint 可与 GET /api/tasks/{taskNo}/keywords 的 fingerprint 比对。"
                    + "应带文件不全的版本整版不出现；任务不存在返回 404")
    @GetMapping("/{taskNo}/artifacts")
    public List<ArtifactResponse> listArtifacts(@PathVariable String taskNo) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        return artifactQueryService.listArtifacts(taskNo).stream()
                .map(snapshot -> ArtifactResponse.from(taskNo, snapshot))
                .toList();
    }

    /**
     * 下载一版产物里的一个文件：正文默认是「用户看到的样子」（叠过字幕纠错），{@code raw=1} 时是磁盘原文件。
     *
     * @param taskNo 提交响应返回的任务号
     * @param type   产物类型：{@code ORIGINAL} / {@code CLEANED} / {@code TRANSLATED}（不区分大小写）
     * @param file   文件名段：{@code segments.json} / {@code subtitles.srt} / {@code context.json} / {@code terms.json}
     * @param lang   目标语种；只有翻译产物必填
     * @param raw    是否直发磁盘原文件（{@code ?raw=1} 时）——用于核对机器产出的原样
     * @return 200 与文件字节（SRT 是文本、JSON 是 application/json）
     * @throws IllegalArgumentException                                     taskNo 为空、类型 / 文件名段 /
     *                                                                      文件位与类型不匹配 / 语种缺失或多余（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     * @throws com.vidsage.application.subtitle.exception.ArtifactNotFoundException 该版产物不可用或文件已不在（404）
     */
    @Operation(summary = "下载一个产物文件",
            description = "按类型 + 文件名段 + 可选语种定位：segments.json / subtitles.srt / context.json / terms.json。"
                    + "TRANSLATED 必须带 ?lang=（zh 与 zh-Hans 等价），其余类型不能带。"
                    + "默认返回叠过用户纠错的正文（无纠错时与磁盘文件一个字节都不差）；?raw=1 永远返回磁盘机器原文件。"
                    + "参数不合法返回 400；任务不存在、没有这一版或文件已不在返回 404。"
                    + "SRT 为 text/plain;charset=UTF-8")
    @GetMapping("/{taskNo}/artifacts/{type}/{file}")
    public ResponseEntity<Resource> download(@PathVariable String taskNo,
                                             @PathVariable String type,
                                             @PathVariable String file,
                                             @RequestParam(value = "lang", required = false) String lang,
                                             @RequestParam(value = "raw", required = false, defaultValue = "false")
                                             boolean raw) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        ArtifactDownload download = artifactQueryService.openArtifact(taskNo, type, file, lang, raw);
        log.info("下载产物: taskNo={}, type={}, file={}, raw={}", taskNo, type, file, raw);
        // 只按文件位名字判类型：web 层不引领域类型（与其它响应 DTO 同一写法）
        MediaType mediaType = "SUBTITLES".equals(download.kind().name())
                ? SRT_MEDIA_TYPE : MediaType.APPLICATION_JSON;
        String fileName = download.path().getFileName().toString();
        // 正文两种来源：套了纠错就发渲染好的字节，否则直发磁盘文件（零纠错时不重新序列化）
        Resource body = download.content() == null
                ? new FileSystemResource(download.path())
                : new ByteArrayResource(download.content());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(fileName).build().toString())
                .body(body);
    }
}
