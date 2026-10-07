/**
 * 媒体获取与音频上下文：拿媒体、抽音频。根只剩本文件，构件按角色分进两个桶。
 *
 * <p>{@code model/} 装数据词汇——{@code MediaInput}（sealed 输入模型，含内嵌 record 变体；另承载
 * TaskInputType → 变体的重建工厂 {@code ofTaskInput} 与上传落盘名 {@code UPLOAD_SOURCE_FILE_NAME}，
 * 提交与管线重建两时机共用）、获取产物 {@code AcquiredMedia}、音频工件与规格
 * {@code AudioArtifact}（含规格指纹命名 {@code fileName}，2026-10-06 业务逻辑下沉 Phase C 吸收）/
 * {@code AudioContainer} / {@code AudioSpec}；{@code port/} 装端口接口 {@code MediaAcquirer}（获取媒体）
 * 与 {@code AudioExtractor}（抽取音频），由 infrastructure/provider 的下载与 ffmpeg 适配器实现。
 */
package com.vidsage.domain.media;
