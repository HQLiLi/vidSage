-- 任务表。
-- 走 Spring SQL init（spring.sql.init.mode: always，见 application-local.yml 注释块），
-- 不引 Flyway：建表幂等就够了，多环境差异出现时再议。可安全重复执行。
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

-- 字幕关键词（用户手填，供 LLM 纠音误）。
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

-- ============================================================================
-- 以下五张表由字幕模型重构新增。命名规则：
--   CONSTRAINT pk_<表名> PRIMARY KEY (id) / CONSTRAINT uk_<表名>_<列...> UNIQUE (...) / INDEX idx_...
-- 枚举列一律 VARCHAR(32)：不用 ENUM、不用 CHECK，加值零 DDL。
-- ============================================================================

-- 媒体身份：一个媒体来源（URL / 上传）在库里的身份与内容指纹。
-- 内容指纹就是 duration_ms + published_at 两个可读列的组合，不另拼一列串；
-- 目录名是 source_key 的纯函数（sanitizeName(source_key)），故没有 workspace_dir 列。
CREATE TABLE IF NOT EXISTS workspace
(
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
    source_type   VARCHAR(32)   NOT NULL COMMENT 'URL / UPLOAD',
    source_key    VARCHAR(512)  NOT NULL COMMENT 'URL 路线：bvid|p；上传路线：内容 sha256',
    source_uri    VARCHAR(2048) NOT NULL COMMENT 'URL 原文 / 上传文件原始名。红线：不得写日志',
    canonical_url VARCHAR(1024) NULL     COMMENT '规范化后的 URL，只给人看',
    duration_ms   INT           NULL     COMMENT '内容指纹之一（URL 路线）；UPLOAD 留空',
    published_at  DATETIME      NULL     COMMENT '内容指纹之二，来自 yt-dlp 的 timestamp',
    created_at    DATETIME      NOT NULL COMMENT '创建时间',
    updated_at    DATETIME      NOT NULL COMMENT '最近变更时间',
    CONSTRAINT pk_workspace PRIMARY KEY (id),
    CONSTRAINT uk_workspace_source_type_source_key UNIQUE (source_type, source_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='媒体身份';

-- 字幕任务：一个媒体对应一个对外稳定的 task_no。没有状态列——主状态、失败原因、
-- 阶段全部从 task_run 派生。
CREATE TABLE IF NOT EXISTS subtitle_task
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
    task_no      VARCHAR(32) NOT NULL COMMENT '对外稳定号（32 位短 UUID）',
    workspace_id BIGINT      NOT NULL COMMENT '逻辑外键 → workspace.id',
    created_at   DATETIME    NOT NULL COMMENT '创建时间',
    updated_at   DATETIME    NOT NULL COMMENT '最近一次 run 收尾的时刻',
    CONSTRAINT pk_subtitle_task PRIMARY KEY (id),
    CONSTRAINT uk_subtitle_task_task_no UNIQUE (task_no),
    CONSTRAINT uk_subtitle_task_workspace_id UNIQUE (workspace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='字幕任务';

-- 每次执行：提交 / 重新生成 / 翻译各新插一条 run。
-- uk_task_run_task_id_active_flag 是互斥的全部机制：active_flag 是生成列，只有 RUNNING 的
-- 行被约束住（NULL 不参与唯一比较），于是抢互斥退化成一句 INSERT——撞键就是抢失败（409）。
CREATE TABLE IF NOT EXISTS task_run
(
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
    task_id         BIGINT      NOT NULL COMMENT '逻辑外键 → subtitle_task.id',
    run_no          INT         NOT NULL COMMENT '任务内递增（MAX+1，不需要事务）',
    run_type        VARCHAR(32) NOT NULL COMMENT 'MAIN / RECLEAN / TRANSLATE',
    target_language VARCHAR(16) NULL     COMMENT '只有 TRANSLATE 有值',
    status          VARCHAR(32) NOT NULL COMMENT 'RUNNING / SUCCEEDED / FAILED / INTERRUPTED',
    current_stage   VARCHAR(64) NULL     COMMENT '前端「卡在哪一步」',
    error_message   TEXT        NULL     COMMENT '失败原因（不用中文前缀区分来源）',
    started_at      DATETIME    NULL     COMMENT '插 RUNNING 行时填 now()',
    finished_at     DATETIME    NULL     COMMENT '落终态时填 now()；INTERRUPTED 填启动收口的时刻',
    created_at      DATETIME    NOT NULL COMMENT '创建时间',
    active_flag     VARCHAR(32) GENERATED ALWAYS AS (IF(status = 'RUNNING', 'RUNNING', NULL)) STORED,
    CONSTRAINT pk_task_run PRIMARY KEY (id),
    CONSTRAINT uk_task_run_task_id_run_no UNIQUE (task_id, run_no),
    CONSTRAINT uk_task_run_task_id_active_flag UNIQUE (task_id, active_flag)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='每次执行';

-- 字幕产物版本：一行 = 一个逻辑版本，文件用固定四列表达。
-- lang NOT NULL DEFAULT '' —— MySQL 里 NULL 不参与唯一约束，空串占位才能保证
-- (task,type,revision) 唯一；uk_..._active_flag 只约束 READY 行，逼出换版落定顺序。
-- 四列 *_sha256 与四个文件列一一对应、同生同灭。
CREATE TABLE IF NOT EXISTS subtitle_artifact
(
    id                       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
    task_id                  BIGINT       NOT NULL COMMENT '逻辑外键 → subtitle_task.id',
    derived_from_run_id      BIGINT       NOT NULL COMMENT '哪一次执行产出的（审计；收口靠它定位）',
    artifact_type            VARCHAR(32)  NOT NULL COMMENT 'ORIGINAL / CLEANED / TRANSLATED',
    lang                     VARCHAR(16)  NOT NULL DEFAULT '' COMMENT 'TRANSLATED 才填；空串 = 无语言维度',
    revision                 INT          NOT NULL COMMENT '同 (task,type,lang) 递增（MAX+1）',
    status                   VARCHAR(32)  NOT NULL COMMENT 'CREATING / READY / SUPERSEDED / FAILED / DELETED',
    derived_from_artifact_id BIGINT       NULL     COMMENT '审计 / 溯源：内容继承自哪一版上游。不参与级联、不参与读取判据',
    keywords_fingerprint     VARCHAR(64)  NULL     COMMENT '只有 CLEANED 有值',
    segments_file_name       VARCHAR(255) NULL     COMMENT '检查点 JSON',
    subtitles_file_name      VARCHAR(255) NULL     COMMENT 'SRT 派生视图',
    context_file_name        VARCHAR(255) NULL     COMMENT '只有 CLEANED 有（摘要副产物）',
    terms_file_name          VARCHAR(255) NULL     COMMENT '只有 TRANSLATED 有（术语查证副产物）',
    segments_sha256          CHAR(64)     NULL     COMMENT '成品文件的 sha256；与文件列同生同灭',
    subtitles_sha256         CHAR(64)     NULL     COMMENT '同上',
    context_sha256           CHAR(64)     NULL     COMMENT '同上',
    terms_sha256             CHAR(64)     NULL     COMMENT '同上',
    created_at               DATETIME     NOT NULL COMMENT '创建时间',
    active_flag              VARCHAR(32) GENERATED ALWAYS AS (IF(status = 'READY', status, NULL)) STORED,
    CONSTRAINT pk_subtitle_artifact PRIMARY KEY (id),
    CONSTRAINT uk_subtitle_artifact_task_id_artifact_type_revision_lang UNIQUE (task_id, artifact_type, revision, lang),
    CONSTRAINT uk_subtitle_artifact_task_id_artifact_type_lang_active_flag UNIQUE (task_id, artifact_type, lang, active_flag),
    INDEX idx_subtitle_artifact_derived_from_run_id (derived_from_run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='字幕产物版本';

-- 中间产物（task 级缓存）：媒体源 / 元数据 / 音频，跨 run 存活。
-- uk(task_id, file_name) 决定一个文件在一个 task 里只有一行，所以「重新产出」是 UPDATE 那行回
-- READY，不是插新行——故没有 revision、没有 CREATING。ASR 结果直接写成 original-segments.json，
-- 中间没有落盘环节，所以 file_type 里没有 ASR 原始输出这一类。
CREATE TABLE IF NOT EXISTS intermediate_file
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
    task_id             BIGINT       NOT NULL COMMENT '逻辑外键 → subtitle_task.id',
    file_name           VARCHAR(255) NOT NULL COMMENT '工作区内文件名；文件系统上限即 255',
    file_type           VARCHAR(32)  NOT NULL COMMENT 'SOURCE_MEDIA / INFO_JSON / AUDIO',
    status              VARCHAR(32)  NOT NULL COMMENT 'READY / SUPERSEDED / DELETED',
    derived_from_run_id BIGINT       NULL     COMMENT '产出它的那条 run（审计，无承重用途）',
    created_at          DATETIME     NOT NULL COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL COMMENT '最近变更时间',
    CONSTRAINT pk_intermediate_file PRIMARY KEY (id),
    CONSTRAINT uk_intermediate_file_task_id_file_name UNIQUE (task_id, file_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='中间产物（task 级缓存）';
