-- 任务表（MVP-1，列定义与 design.md「任务表」节一一对应）。
-- 走 Spring SQL init（spring.sql.init.mode: always，见 application-local.yml 注释块），
-- 不引 Flyway：单表用不上迁移框架。可安全重复执行。
--
-- ⚠️ 存量迁移说明（2026-10-14 翻译子状态下线）：
--   本版删掉 translation_status 列（2026-10-02 两段式改造时加的）。CREATE TABLE IF NOT EXISTS
--   不会改已存在的旧表，MySQL 8 也没有 DROP COLUMN IF EXISTS，所以旧库必须手工删一次：
--   1. DROP TABLE task;                        （推荐：本地 / 测试数据，重启后由本脚本重建）
--   2. ALTER TABLE task DROP COLUMN translation_status;
--   旧表不迁移的后果：列留着不会被读写（应用侧已无任何引用），但会误导后来读表的人，
--   以为翻译还有 DB 子状态。翻译在不在跑改由内存登记表回答（TaskSessionRegistry）。
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
    error_message      VARCHAR(2048) NULL COMMENT '错误信息（中文，面向用户）：主管线失败 / 重新生成失败 / 翻译失败共用本列，各自带前缀（「任务执行失败：」「重新生成失败：」「翻译失败：」）区分来源',
    created_at         DATETIME      NOT NULL COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL COMMENT '最近变更时间',
    started_at         DATETIME      NULL COMMENT '开始执行时间',
    finished_at        DATETIME      NULL COMMENT '终态时间（成功或失败）',
    CONSTRAINT pk_task PRIMARY KEY (id),
    CONSTRAINT uq_task_task_no UNIQUE (task_no),
    INDEX idx_task_workspace_key (workspace_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='视频任务';

-- 字幕关键词（用户手填，供 LLM 纠音误）。任务 10-11-subtitle-keyword-regenerate 引入。
--
-- 为什么挂 workspace_key 而不是 task_no：关键词是字幕产物的生成输入，该跟产物同粒度。
-- 同一 URL 重新提交会新插一行 task（查重只拦 PENDING / RUNNING，workspace_key 无唯一约束），
-- 挂 task_no 会让新任务名下一片空白，而目录里躺着的是用旧关键词生成的那份字幕。
-- 本表只挂字符串、不设 DB 外键约束——同源约束由应用层保证（detail 只走任务/媒体两条写入路线）。
--
-- 为什么一个工作区一行、整份清单挤在一列：本项目的唯一查询是「按工作区取整份清单」，
-- 从没有「按词反查哪些工作区用了它」；单列还白送两件事——用户填写顺序天然保序
-- （行存储得再加一列 position 才能还原，InnoDB 不保证按插入序返回），
-- 覆盖全量是一条 upsert 而不是 DELETE + 批量 INSERT。
--
-- 列长够用：50 条 × 51 字 + 49 个分隔符 ≈ 2550 字符，故不需要额外的「总字数」约束，
-- 长度判据在领域值对象 SubtitleKeywords 里（单条 ≤ 50、条数 ≤ 50）。
-- 分隔符用 U+0001 而不是逗号 / 竖线：后者会跟真术语（Dr. Smith, Jr.）冲突；
-- 它是 ISO 控制字符，SubtitleKeywords 的归一化已经把它换成空格，到不了落库这一层。
CREATE TABLE IF NOT EXISTS subtitle_keyword
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
    workspace_key CHAR(12)     NOT NULL COMMENT '工作区短哈希（与 task 表同源）；关键词挂工作区不挂任务',
    keywords      VARCHAR(4096) NOT NULL COMMENT '关键词原文，以 U+0001 连接，顺序即用户填写顺序（已归一化：去首尾空白、结构性字符转空格、去重）；空列表存空串',
    created_at    DATETIME     NOT NULL COMMENT '首次写入时间（覆盖写不刷新）',
    CONSTRAINT pk_subtitle_keyword PRIMARY KEY (id),
    UNIQUE INDEX uk_subtitle_keyword_workspace_key (workspace_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='字幕关键词（用户手填，供 LLM 纠音误）';
