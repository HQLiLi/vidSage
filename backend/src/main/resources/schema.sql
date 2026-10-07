-- 任务表（MVP-1，列定义与 design.md「任务表」节一一对应）。
-- 走 Spring SQL init（spring.sql.init.mode: always，见 application-local.yml 注释块），
-- 不引 Flyway：单表用不上迁移框架。可安全重复执行。
--
-- ⚠️ 存量迁移说明（2026-10-02 两段式改造）：
--   CREATE TABLE IF NOT EXISTS 不会为已存在的旧表加列，MySQL 8 也没有 ADD COLUMN IF NOT EXISTS。
--   本版新增 translation_status 列——已有旧库必须二选一：
--   1. DROP TABLE task;                （推荐：本地 / 测试数据，重启后由本脚本重建）
--   2. ALTER TABLE task ADD COLUMN translation_status ENUM('RUNNING','SUCCEEDED','FAILED') NULL
--      COMMENT '翻译子状态（主任务 SUCCEEDED 后按需触发）：NULL=从未触发' AFTER current_stage;
--   旧表不迁移的后果：MyBatis-Plus 按列名映射，写入 translation_status 时报 Unknown column，
--   触发翻译即 500。
CREATE TABLE IF NOT EXISTS task
(
    id                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外（对外只暴露 task_no）',
    task_no            VARCHAR(32)   NOT NULL COMMENT '对外任务号：32 位短 UUID',
    input_type         ENUM ('URL', 'UPLOAD') NOT NULL COMMENT '输入类型：URL 提交 / 文件上传',
    source_uri         VARCHAR(2048) NOT NULL COMMENT 'URL 原文或上传文件原始名（日志不落原值）',
    workspace_key      CHAR(12)      NOT NULL COMMENT '工作区短哈希：URL/本地路径取 normalize 串摘要，上传取内容 sha256 前 12 位；提交查重用',
    workspace_dir      VARCHAR(512)  NOT NULL COMMENT '工作区目录相对路径（相对 vidsage.workspace.root）',
    status             ENUM ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED') NOT NULL COMMENT '任务状态（状态机权威存储：PENDING→RUNNING→SUCCEEDED/FAILED）',
    current_stage      VARCHAR(64)   NULL COMMENT '最近阶段名',
    translation_status ENUM ('RUNNING', 'SUCCEEDED', 'FAILED') NULL COMMENT '翻译子状态（2026-10-02 两段式：翻译不随主管线自动执行，主任务 SUCCEEDED 后按需触发）：NULL=从未触发，与主状态 status 相互独立',
    error_message      VARCHAR(2048) NULL COMMENT '终态错误信息（中文，面向用户；主任务失败或翻译子状态失败——后者带「翻译失败：」前缀）',
    created_at         DATETIME      NOT NULL COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL COMMENT '最近变更时间',
    started_at         DATETIME      NULL COMMENT '开始执行时间',
    finished_at        DATETIME      NULL COMMENT '终态时间（成功或失败）',
    CONSTRAINT pk_task PRIMARY KEY (id),
    CONSTRAINT uq_task_task_no UNIQUE (task_no),
    INDEX idx_task_workspace_key (workspace_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='视频任务';
