/**
 * 媒体应用层：媒体获取与音频准备的管线阶段，外加工作区服务——{@code MediaAcquisitionStage}
 * （按输入类型分派 {@code MediaAcquirer}）、{@code AudioPreparationStage}
 * （acquire → extract-audio 两阶段编排）、{@code WorkspaceService}
 * （按输入内容寻址的工作区目录，被多处方共用的横切服务）。
 */
package com.vidsage.application.media;
