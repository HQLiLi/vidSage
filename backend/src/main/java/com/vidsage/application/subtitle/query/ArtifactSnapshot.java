package com.vidsage.application.subtitle.query;

import com.vidsage.domain.subtitle.model.ArtifactType;
import java.util.List;

/**
 * 一版当前有效产物的读模型：清单端点要展示的元信息加文件列表。
 *
 * @param type                产物类型
 * @param lang                语种；没有语言维度时是空串
 * @param revision            版本号
 * @param keywordsFingerprint 关键词指纹；只有清洗产物有值
 * @param files               该类型带的文件位（按文件位声明顺序）
 */
public record ArtifactSnapshot(ArtifactType type, String lang, int revision,
                               String keywordsFingerprint, List<ArtifactFileSnapshot> files) {
}
