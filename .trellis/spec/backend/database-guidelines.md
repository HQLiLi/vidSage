# 数据库与持久化规范

> 2026-09-30 随 MVP-1 落库重建（此前因「没有任何持久化依赖」删除）；2026-10-05 DDD 四层化起，三层代码
> （entity / mapper / repository）位于 `infrastructure/persistence/`。分层职责与可见性见
> [directory-structure.md](./directory-structure.md)，本文只管数据库侧契约。
> **2026-10-17**「字幕模型重构 S1：数据层地基」起，建表契约一节按新模型（六张表）重写。

## 环境与依赖

- **MySQL 8**（开发机 Windows 服务 `MySQL80`@3306；交付形态 Docker Compose）。库名 `vidsage`，utf8mb4。
- 依赖：`com.baomidou:mybatis-plus-spring-boot4-starter`（**Boot 4 必须用 boot4 starter**，3.5.15 起提供，
  当前 3.5.17；boot3 starter 在 Boot 4 下自动装配对不上）+ `com.mysql:mysql-connector-j`（runtime，BOM 管版本）。
- **凭据只进 `application-local.yml`**（git 不跟踪）；`application.yml` 不得出现真实凭据。
- 建表走 `schema.sql` + `spring.sql.init.mode: always`：`CREATE TABLE IF NOT EXISTS` 幂等，不引 Flyway
  （六张表也用不上迁移框架；多环境差异出现时再议）。

## 建表契约

> **现状是六张表**（`workspace` / `subtitle_task` / `task_run` / `subtitle_artifact` /
> `intermediate_file` / `subtitle_keyword`）；旧的 `task` 表已随字幕模型重构退场。
> 全文见 `backend/src/main/resources/schema.sql`；
> **逐列的权威定义与「为什么这么定」在 `.trellis/tasks/10-15-subtitle-task-model-refactor/design.md` §1–§2**
> （归档后会挪到 `.trellis/tasks/archive/` 下同名目录）。本文件不复述 DDL，只写规则。

| 表 | 装什么 | 状态列 |
|---|---|---|
| `workspace` | 媒体身份：一个来源（URL / 上传）一行，按 `(source_type, source_key)` 唯一 | 无 |
| `subtitle_task` | 字幕任务：一个媒体一个对外稳定的 `task_no` | **无** —— 主状态、失败原因、阶段全从 `task_run` 派生 |
| `task_run` | 每次执行（提交 / 重新生成 / 翻译）各一行 | `RUNNING` / `SUCCEEDED` / `FAILED` / `INTERRUPTED` |
| `subtitle_artifact` | 产物版本：一行 = 一个逻辑版本，文件用固定四列表达 | `CREATING` / `READY` / `SUPERSEDED` / `FAILED` / `DELETED` |
| `intermediate_file` | 中间产物缓存（媒体源 / 元信息 / 音频），跨 run 存活 | `READY` / `SUPERSEDED` / `DELETED` |
| `subtitle_keyword` | 用户手填的关键词，按 `task_id` 一个任务一份（覆盖写）；唯一约束 `uk_subtitle_keyword_task_id` | 无 |

### 七条规则

**1. 主键具名，对外标识另设一列。** 内部主键 `id BIGINT AUTO_INCREMENT` **必须显式定义为主键**
（`CONSTRAINT pk_<表名> PRIMARY KEY (id)`）—— 自增列不定义成 key 直接建表失败（MySQL 1075，实测过）。
对外标识（如 `subtitle_task.task_no`）另设 `VARCHAR(32)` 唯一列，**自增 id 不出境**。

> ⚠️ **MySQL 不保留主键的约束名**：`CONSTRAINT pk_workspace PRIMARY KEY (id)` 建出来后，
> `information_schema.table_constraints` 里记的恒是 `PRIMARY`，不是 `pk_workspace`。所以 `pk_` 前缀的
> 作用是**让 DDL 文本自解释**，不是可查询的事实 —— 核对建表结果时别拿主键名去比，会误判成失败。
> （唯一约束与普通索引的名字 MySQL 照留，`uk_` / `idx_` 可以照名字核。）

**2. 索引与约束一律具名** —— 机械规则，不用判断：

```sql
CONSTRAINT pk_<表名>            PRIMARY KEY (id)
CONSTRAINT uk_<表名>_<列名...>  UNIQUE (...)
INDEX      idx_<表名>_<列名...> (...)
```

带表名段；唯一约束写成 `CONSTRAINT ... UNIQUE`，**不写 `UNIQUE KEY` / `UNIQUE INDEX`**；
名字超 64 字符时在**列名边界**截断（不留悬空 `_`）。

**3. 枚举列一律 `VARCHAR(32)`，不用 `ENUM`、也不用 `CHECK`。**
理由：**加值零 DDL** —— 新值（如 `INTERRUPTED`）在 `ENUM` 下每次都要手工 `ALTER TABLE`；
宽度取 32 而不是贴着最长值取 16，换「加值不用先改列宽」，而 `VARCHAR` 按实际长度存、成本为 0。
`CHECK` 同样别用：加值一样要 `ALTER`，好处立刻没了。
适用范围：`source_type` / `run_type` / 各表的 `status` / `artifact_type` / `file_type` 与两个 `active_flag`。
**状态值本身怎么定、谁能写它，见 [state-machine-guidelines.md](./state-machine-guidelines.md)** ——
本文只管列类型，不管状态语义。

**4. 「部分唯一」用生成列 + 唯一索引，不靠应用层判重。**

```sql
active_flag VARCHAR(32) GENERATED ALWAYS AS (IF(status = 'RUNNING', 'RUNNING', NULL)) STORED,
CONSTRAINT uk_task_run_task_id_active_flag UNIQUE (task_id, active_flag)
```

`NULL` 不参与唯一比较，于是只有被约束的那个状态值「占位」。两条现用：`task_run` 保证一个任务
同时最多一条 `RUNNING`（**抢互斥退化成一句 INSERT，撞键即抢失败**）；`subtitle_artifact` 保证每
`(task_id, artifact_type, lang)` 最多一条 `READY`（当前版）。**别改成「先查一遍再插」** —— 那是两套
判据，并发下照样漏，且内存登记表那类做法不跨重启。

**5. 只写逻辑外键，不写 `FOREIGN KEY` 约束。** 同源约束由应用层保证。

**6. 时间列按需，不再「四件套」。** 每张表只落它真正需要的：`created_at` 普遍都有，`task_run` 另有
`started_at` / `finished_at`，`subtitle_artifact` 只有 `created_at`。
**凡是带 `updated_at` 的表，写入点一律是「在同一条 UPDATE 里一起刷」**，首插时与 `created_at` 同值 ——
`subtitle_task` 是每次 run 收尾、`workspace` 是指纹写入、`intermediate_file` 是三条状态变更、
`subtitle_keyword` 是覆盖写那条 upsert 的 UPDATE 分支。
**「有列没写入路径」是明确的病，不许留**（定了列就要同时定谁写、什么时候写）。

**7. 脱敏红线：`source_uri` 列不得写日志。** 它装 URL 原文 / 上传文件原始名，含用户原始信息。

### Validation & Error Matrix

| 条件 | 结果 |
|---|---|
| 自增列未定义为主键 | 建表即败：MySQL 1075「there can be only one auto column and it must be defined as a key」（2026-09-30 实测） |
| 枚举列用 `ENUM`，事后要加新值 | 必须手工 `ALTER TABLE` —— 本仓已改用 `VARCHAR(32)` 规避 |
| 生成列省掉 `STORED` 或写成普通列 | 唯一索引失去「只约束一个状态值」的语义，互斥/当前版静默失效 |
| 同一 task 已有 `RUNNING` run 时再触发 | `TaskConflictException` → 409（响应体与 202 同形状 `{taskNo, runNo}`）——撞 `task_run` 的 `uk_task_run_task_id_active_flag` |
| taskNo 不存在 | `TaskNotFoundException` → 404 |
| 真库不可达且 sql.init.mode=always | 上下文启动失败——刻意显式暴露，不做静默降级 |

### Wrong vs Correct

#### Wrong：用 MySQL `ENUM` 装状态
```sql
status ENUM ('RUNNING', 'SUCCEEDED', 'FAILED') NOT NULL   -- ❌ 加 INTERRUPTED 要手工 ALTER TABLE
```
#### Correct：`VARCHAR(32)` + Java enum 按名映射
```sql
status VARCHAR(32) NOT NULL COMMENT 'RUNNING / SUCCEEDED / FAILED / INTERRUPTED'
```

#### Wrong：凭据入库
```yaml
# application.yml（git 跟踪）
spring.datasource.password: "my-secret-123"   # ❌ 泄露进仓库
```
#### Correct：凭据进本地配置
```yaml
# application-local.yml（.git/info/exclude 排除，不进仓库）
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/vidsade?serverTimezone=UTC
    username: ${MYSQL_USERNAME:root}
    password: ${MYSQL_PASSWORD:}
```

#### Wrong：指望 Boot 自动跑 schema.sql
```yaml
# 非嵌入式数据库下 Boot 默认 spring.sql.init.mode=never，schema.sql 是死文件 ❌
```
#### Correct：显式打开
```yaml
spring.sql.init.mode: always   # CREATE TABLE IF NOT EXISTS 幂等，启动即建表
```

## MyBatis-Plus 用法

- 只用第一档 `BaseMapper<T>`；**禁** `IService` / `ServiceImpl` 继承（理由见 directory-structure.md 反模式表）。
- MP 类型（`BaseMapper` / `LambdaQueryWrapper` / `IPage`）不越出 `infrastructure/persistence/` 包。
- **值对象 ↔ String 列**：领域侧的值对象（`domain/task/model/TaskNo`、
  2026-10-17 起的 `domain/workspace/model/SourceKey` 等）与枚举（`SourceType` 等）在实体里一律是
  `String` / enum 列，转换收敛在 `MybatisXxxRepository` 的实体↔领域映射边界 —— schema / SQL / Mapper
  层对该转换无感知。
- 生成列（`active_flag`）**不映射进 Entity** —— 它是 DB 算出来的，插入与更新都不带它。
- 定向 UPDATE（改一列/几列）手写 `@Update`；其余单表 CRUD 走 `BaseMapper` + `Wrappers`。
  需要「锁定读」时用 `.last("FOR SHARE")`，**不要 `FOR UPDATE`**（见 `design.md` 并发提交一节）。
- Mapper 注册依赖 starter 自动配置 + `@Mapper` 注解，**不写 @MapperScan**。
- 枚举持久化按名映射（`@TableName` 实体字段直接用 Java enum）。

### Wrong vs Correct

#### Wrong：application 层直接注入 Mapper
```java
@Service class Foo { private final SubtitleTaskMapper mapper; }   // ❌ Mapper 是 persistence 的内部细节
```
#### Correct：依赖 domain 的端口，实现住 persistence
```java
@Service class Foo { private final SubtitleTaskRepository repository; }   // ✅ 实现类 MybatisSubtitleTaskRepository
```

## 事务与并发读

**规则**：一个事务里凡是「要读到**另一个并发事务刚提交的行**」的读，一律用**锁定读**（`.last("FOR SHARE")`）。
普通 `SELECT` 走的是事务快照（MySQL 默认 REPEATABLE READ，快照在**事务里第一条普通读**那一刻建立），
读不到对方在快照建立之后提交的行。

**为什么容易漏**：撞唯一键后「重查对方刚插的行」这类分支，头几条重查通常都记得加锁定读，
到后面再查一条（例如「取那条既有的 run」）就忘了 —— 而它读的同样是对方刚提交的行。

**漏了的后果不是「偶发」而是「必现」**：只要走到那个分支就抛 `IllegalStateException`，HTTP 500。
而且它只在**真并发**下出现 —— 串行重试、普通压测都复现不了，因为那些时候快照本来就是新的、
根本走不到撞键分支。

### Wrong vs Correct

#### Wrong：撞键分支里最后一条重查用了普通读
```java
Optional<Workspace> ws = workspaceRepository.findForShareBySourceTypeAndSourceKey(type, key);  // ✅ 锁定读
Optional<SubtitleTask> task = subtitleTaskRepository.findForShareByWorkspaceId(wsId);          // ✅ 锁定读
Optional<TaskRun> run = taskRunRepository.findLatestByTaskId(taskId);                          // ❌ 普通读：快照里没这条
```
#### Correct：同一事务里要读对方刚提交的行，一条都不能漏
```java
Optional<TaskRun> run = taskRunRepository.findLatestForShareByTaskId(taskId);                  // ✅
```

**这类分支怎么验**：多进程 curl 并发**测不出来**（进程启动错开几毫秒，根本进不去撞键分支）。
要用**单进程并行连接**把到达时间压到亚毫秒，例如：
```bash
curl -s --parallel --parallel-immediate --parallel-max 8 -o /dev/null -w '%{http_code} ' \
     -X POST -H 'Content-Type: application/json' -d '{"url":"..."}' \
     $(for i in $(seq 8); do echo "http://localhost:8080/api/tasks"; done)
```
判据：应当恰好一个 202、其余 409（且 409 带的是**正在跑那条**的 runNo）；出现 500 就是漏了锁定读。

### 事务机制怎么选：注解优先，模板兜底

**规则**：默认用 `@Transactional` —— 方法被别的 bean 调用（走 Spring 代理）、事务段就是一个完整方法、事务里不做慢 IO，三条都满足时注解更短也更好读。

只有下面几种情况才用 `TransactionTemplate`：

1. 事务段必须由**同类内部方法**调用 —— 自调用绕过 Spring 代理，注解**静默失效**；
2. 要在事务外先做慢 IO（网络 / 大文件哈希），事务段拆出去又会踩第 1 条；
3. 事务边界要在**不启 Spring 上下文的单测**里被观察到 —— 本仓的门面类单测直接 `new` 对象并注入事务管理器替身，注解在裸实例上是空转。

> **别写成「本仓一律用模板」**：`src/main` 里当前没有 `@Transactional` 只是历史现状，不是禁用规则。能用注解的地方照用。

**实例：`SubtitleArtifactStore#settleArtifact`** —— 算文件名与哈希是慢文件 IO，必须留在事务外；事务段（旧版降级 + 新行 READY）拆出去只能被同类内部调用；事务边界由 `SubtitleArtifactStoreTest` 的 `RecordingTransactionManager` 钉住。三条原因都踩到，所以用 `transactionTemplate.executeWithoutResult(...)`。

#### Wrong vs Correct

##### Wrong：对着带慢 IO 的方法直接加 `@Transactional`
```java
@Transactional   // ❌ 哈希也进了事务；若再拆成同类私有方法，注解还会静默失效
public SubtitleArtifact settleArtifact(SubtitleArtifact creating, Path workDir) {
    ArtifactFiles files = resolveArtifactFiles(...);   // 慢文件 IO
    ...
}
```

##### Correct：慢 IO 在事务外，事务段用模板显式圈
```java
public SubtitleArtifact settleArtifact(SubtitleArtifact creating, Path workDir) {
    ArtifactFiles files = resolveArtifactFiles(...);   // ✅ 事务外
    transactionTemplate.executeWithoutResult(status -> {
        // ✅ 事务内：旧版降级 + 新行 READY
    });
    return creating;
}
```

## 测试策略

- **单测不连真库**：各 `MybatisXxxRepositoryTest`（如 `MybatisSubtitleTaskRepositoryTest`）mock 全部 Mapper，
  断言实体↔领域对象双向转换（含 `TaskNo` 值对象与 String 列的互转）与查询条件。2026-10-17 起的六个仓储
  （`workspace` / `subtitle_task` / `task_run` / `subtitle_artifact` / `intermediate_file` / `subtitle_keyword`）同此口径。
  `LambdaQueryWrapper` 的列名解析走 `TableInfo` 缓存，纯单测要在 `@BeforeAll` 手工
  `TableInfoHelper.initTableInfo(...)` 注册一次。
- **真库连通由冒烟测试承担**：`VidSageApplicationTests` 标 `@ActiveProfiles("local")`（测试类路径的
  application.yml 会遮蔽主配置且不带 `spring.profiles.default`——不显式激活，数据源配置根本不加载，
  报「Failed to determine a suitable driver class」，2026-09-30 实测踩过）。
- 落库行为（insert 回填 id、查重 SQL 语义）的最终背书是真实运行证据
  （`09-24-vidsage-mvp1-submit/research/acceptance-evidence.md`）。
