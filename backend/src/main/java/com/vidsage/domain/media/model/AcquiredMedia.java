package com.vidsage.domain.media.model;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 输入获取阶段的产物：一条输入对应的本地媒体文件及其元数据。
 *
 * <p><b>标题与时长是必填的</b>：任何输入路线都必须给出。只有 {@code infoJsonPath} 可能不存在，
 * 所以它是唯一用 {@link Optional} 表达的字段——「只有真正可能缺失的东西才用 {@code Optional}」
 * 是这条契约的取值规则，不要退回成可 {@code null}。
 *
 * @param mediaPath    本地媒体文件路径
 * @param title        媒体标题，不许传 {@code null} 或空串
 * @param durationMs   媒体时长（毫秒），必须 &gt;= 0。
 *                     <b>这是从 0 起算的时长，不是时间戳</b>，不要用它表达绝对时间
 * @param infoJsonPath 下载器落盘的 info json 路径；不存在时为 {@link Optional#empty()}，不是 {@code null}
 */
public record AcquiredMedia(Path mediaPath, String title, long durationMs, Optional<Path> infoJsonPath) {
}
