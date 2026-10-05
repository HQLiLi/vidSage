/**
 * 媒体应用服务：媒体获取与音频准备的用例编排——{@code MediaAcquisitionService}
 * （按输入类型分派 {@code MediaAcquirer}）、{@code AudioPreparationService}
 * （acquire → extract-audio 两阶段编排）、{@code WorkspaceService}
 * （按输入内容寻址的工作区目录）。
 */
package com.vidsage.application.media;
