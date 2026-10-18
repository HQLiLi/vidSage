/**
 * 中间产物上下文：task 级缓存里的文件（媒体源 / 元信息 / 音频），跨 run 存活。
 *
 * <p>根装实体 {@code IntermediateFile}——一个文件在一个 task 里只有一行，「重新产出」复用同一行。
 * {@code model/} 装类型 {@code IntermediateFileType} 与状态 {@code IntermediateFileStatus}；
 * {@code port/} 装持久化端口 {@code IntermediateFileRepository}，由 persistence 层实现。
 */
package com.vidsage.domain.intermediate;
