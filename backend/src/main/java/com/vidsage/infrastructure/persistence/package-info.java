/**
 * 任务持久化：MyBatis-Plus 的 entity / mapper / repository 三件套，每张表一套。
 * {@code subtitle_keyword} 表 → {@code SubtitleKeywordEntity} / {@code SubtitleKeywordMapper} /
 * {@code MybatisSubtitleKeywordRepository}（实现 {@code domain/subtitle/port/SubtitleKeywordRepository}）；
 * 其余五张表（{@code workspace} / {@code subtitle_task} / {@code task_run} / {@code subtitle_artifact} /
 * {@code intermediate_file}）同此形状，各有一套 entity / mapper / repository。
 */
package com.vidsage.infrastructure.persistence;
