package com.vidsage.web.dto;

import com.vidsage.application.subtitle.query.ArtifactFileSnapshot;
import com.vidsage.application.subtitle.query.ArtifactSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一版当前有效产物的对外形状（GET /api/tasks/{taskNo}/artifacts 的列表项）。
 *
 * <p>只表示有效版本：历史版本不出现在清单里，也不能下载。
 *
 * <p><b>下载地址只在本类拼</b>：清单里给出完整 URL，调用方不用自己记文件名段与语种参数的规则；
 * 拼法只有这一处，端点里不再重复。
 *
 * @param type                产物类型：{@code ORIGINAL} / {@code CLEANED} / {@code TRANSLATED}
 * @param lang                语种：只有翻译产物有值，其余是空串
 * @param revision            版本号，同一任务同一类型每产出一次加一
 * @param keywordsFingerprint 关键词指纹：只有清洗产物有值，拿它与关键词端点的 fingerprint 比对
 * @param files               文件位 → 下载地址与字节数；键顺序固定为 segments → subtitles → context / terms
 */
@Schema(description = "一版当前有效产物")
public record ArtifactResponse(
        @Schema(description = "产物类型：ORIGINAL（原始转写）/ CLEANED（清洗后）/ TRANSLATED（译文）",
                example = "CLEANED") String type,
        @Schema(description = "语种：只有翻译产物有值，其余为空串", example = "zh-Hans") String lang,
        @Schema(description = "版本号，同一任务同一类型每产出一次加一", example = "1") int revision,
        @Schema(description = "关键词指纹：只有清洗产物有值；与 GET /api/tasks/{taskNo}/keywords 的 fingerprint 比对",
                example = "a1b2c3d4e5f60708") String keywordsFingerprint,
        @Schema(description = "文件位 → 下载地址与字节数；键顺序固定为 segments → subtitles → context / terms")
        Map<String, ArtifactFileResponse> files) {

    /**
     * 应用层读模型 → 对外响应：拼出每个文件位的下载地址，键顺序照读模型给的文件位顺序。
     *
     * @param taskNo   对外任务号
     * @param snapshot 应用层读模型
     * @return 对外响应
     */
    public static ArtifactResponse from(String taskNo, ArtifactSnapshot snapshot) {
        Map<String, ArtifactFileResponse> files = new LinkedHashMap<>();
        for (ArtifactFileSnapshot file : snapshot.files()) {
            files.put(file.kind().key(),
                    new ArtifactFileResponse(downloadUrl(taskNo, snapshot, file), file.sizeBytes()));
        }
        return new ArtifactResponse(snapshot.type().name(), snapshot.lang(), snapshot.revision(),
                snapshot.keywordsFingerprint(), files);
    }

    /**
     * 一个文件位的下载地址：翻译产物带上语种参数，其余类型不带。
     *
     * <p>只按名字判类型（web 层不引领域类型，与其它响应 DTO 同一写法）。
     */
    private static String downloadUrl(String taskNo, ArtifactSnapshot snapshot, ArtifactFileSnapshot file) {
        String url = "/api/tasks/" + taskNo + "/artifacts/" + snapshot.type().name() + "/"
                + file.kind().token();
        return "TRANSLATED".equals(snapshot.type().name()) ? url + "?lang=" + snapshot.lang() : url;
    }
}
