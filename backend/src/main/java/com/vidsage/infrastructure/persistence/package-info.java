/**
 * 任务持久化：MyBatis-Plus 的 entity / mapper / repository 三件套，每张表一套。
 * {@code task} 表 → {@code VideoTaskEntity} / {@code VideoTaskMapper} /
 * {@code MybatisVideoTaskRepository}（实现领域端口 {@code domain/task/port/VideoTaskRepository}，
 * 是 {@code VideoTask} 聚合根存取出口的唯一适配器）；{@code subtitle_keyword} 表 →
 * {@code SubtitleKeywordEntity} / {@code SubtitleKeywordMapper} /
 * {@code MybatisSubtitleKeywordRepository}（实现 {@code domain/subtitle/port/SubtitleKeywordRepository}）。
 */
package com.vidsage.infrastructure.persistence;
