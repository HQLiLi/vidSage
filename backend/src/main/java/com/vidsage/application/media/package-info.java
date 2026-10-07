/**
 * 媒体应用层：根留 {@code WorkspaceService}（按输入内容寻址的工作区目录，被多处方共用的横切服务，
 * 唯一有 web 之外调用面的一档），其余挪进角色子包。
 *
 * <p>子包 {@code pipeline/} 装本上下文的管线阶段：{@code MediaAcquisitionStage}
 * （按输入类型分派 {@code MediaAcquirer}）与 {@code AudioPreparationStage}
 * （acquire → extract-audio 两阶段编排）。两者都是管线构件，各自只被上级管线按顺序调用一次。
 */
package com.vidsage.application.media;
