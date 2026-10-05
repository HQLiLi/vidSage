package com.vidsage.domain.media;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 输入获取阶段的产物：一条输入对应的本地媒体文件及其元数据。
 *
 * <p><b>标题与时长是必填的</b>，两条输入路线都必须给出：远程路线取自下载器落盘的 info json，
 * 本地路线由文件名（标题）与 {@code ffprobe}（时长）补齐。只有 info json 本身可能不存在
 * ——本地路线没有这个文件——所以它是唯一用 {@link Optional} 表达的字段。
 * 「只有真正可能缺失的东西才用 {@code Optional}」是这条契约的取值规则，不要退回成可 {@code null}。
 *
 * @param mediaPath    本地媒体文件路径
 * @param title        媒体标题；本地路线取文件名，不许传 {@code null} 或空串
 * @param durationMs   媒体时长（毫秒），必须 &gt;= 0。
 *                     <b>这是从 0 起算的时长，不是时间戳</b>，不要用它表达绝对时间
 * @param infoJsonPath 下载器落盘的 info json 路径；本地路线为 {@link Optional#empty()}，
 *                     不是 {@code null}
 */
public record AcquiredMedia(Path mediaPath, String title, long durationMs, Optional<Path> infoJsonPath) {
}
