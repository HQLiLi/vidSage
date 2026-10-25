/**
 * 媒体应用层：构件按角色进子包，根只剩本文件。
 *
 * <p>子包 {@code port/} 装两个技术端口：{@code FileStateReader}（文件 stat 读数）与
 * {@code WorkspaceDirectoryManager}（工作区目录的定位与创建）。实现都在
 * {@code infrastructure/fs/}，本层只认接口。
 *
 * <p>子包 {@code pipeline/} 装本上下文的管线阶段：{@code MediaAcquirerDispatcher}
 * （按输入类型分派 {@code MediaAcquirer}）与 {@code AudioPreparationCoordinator}
 * （获取媒体 → 抽取音频两阶段编排）。两者都是管线构件，各自只被上级管线按顺序调用一次。
 *
 * <p>根包的 {@code MediaSourceQueryService} 与子包 {@code query/}、{@code exception/} 一起服务
 * 「播放源媒体」这条读路径：服务定位文件并嗅探格式，{@code MediaSource} 是它交出去的读模型，
 * {@code MediaSourceNotFoundException} 是「任务在但没有可播放媒体」的具名异常（映射 404）。
 */
package com.vidsage.application.media;
