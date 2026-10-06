# 数据库与持久化规范

> 2026-09-30 随 MVP-1 落库重建（此前因「没有任何持久化依赖」删除）；2026-10-05 DDD 四层化起，三层代码
> （entity / mapper / repository）位于 `infrastructure/persistence/`。分层职责与可见性见
> [directory-structure.md](./directory-structure.md)，本文只管数据库侧契约。

## 环境与依赖

- **MySQL 8**（开发机 Windows 服务 `MySQL80`@3306；交付形态 Docker Compose）。库名 `vidsage`，utf8mb4。
- 依赖：`com.baomidou:mybatis-plus-spring-boot4-starter`（**Boot 4 必须用 boot4 starter**，3.5.15 起提供，
  当前 3.5.17；boot3 starter 在 Boot 4 下自动装配对不上）+ `com.mysql:mysql-connector-j`（runtime，BOM 管版本）。
- **凭据只进 `application-local.yml`**（git 不跟踪）；`application.yml` 不得出现真实凭据。
- 建表走 `schema.sql` + `spring.sql.init.mode: always`：`CREATE TABLE IF NOT EXISTS` 幂等，不引 Flyway
  （单表用不上迁移框架；多表 / 多环境差异出现时再议）。

## 建表契约（现状：`task` 表，全文见 `backend/src/main/resources/schema.sql`）

- 内部主键 `id BIGINT AUTO_INCREMENT` **必须显式定义为主键**（`CONSTRAINT pk_task PRIMARY KEY (id)`）；
  对外标识 `task_no VARCHAR(32)` UNIQUE（自增 id 可枚举不外泄）。两者并存是刻意设计，不是冗余。
- 状态等低基数列用 MySQL `ENUM`，与 Java enum **按名映射**；时间列四件套
  created_at / updated_at / started_at / finished_at（`DATETIME`）。
- 索引按查询场景建：唯一约束 `task_no`；普通索引 `workspace_key`（提交查重）。不建查询用不到的索引。

### Validation & Error Matrix

| 条件 | 结果 |
|---|---|
| 自增列未定义为主键 | 建表即败：MySQL 1075「there can be only one auto column and it must be defined as a key」（2026-09-30 实测） |
| 同 workspace_key 且 PENDING/RUNNING 重复提交 | `TaskConflictException` → 409（携带 existingTask） |
| taskNo 不存在 | `TaskNotFoundException` → 404 |
| 真库不可达且 sql.init.mode=always | 上下文启动失败——刻意显式暴露，不做静默降级 |

## MyBatis-Plus 用法

- 只用第一档 `BaseMapper<T>`；**禁** `IService` / `ServiceImpl` 继承（理由见 directory-structure.md 反模式表）。
- MP 类型（`BaseMapper` / `LambdaQueryWrapper` / `IPage`）不越出 `infrastructure/persistence/` 包。
- **值对象 ↔ String 列**：领域侧 `VideoTask` 的 `taskNo` / `workspaceKey` 已是值对象（`domain/task/TaskNo`、`domain/shared/WorkspaceKey`）；实体 `VideoTaskEntity` 保持 `String` 列，转换收敛在 `VideoTaskRepository` 的实体↔领域映射边界——schema / SQL / Mapper 层对该转换无感知。
- Mapper 注册依赖 starter 自动配置 + `@Mapper` 注解，**不写 @MapperScan**。
- 枚举持久化按名映射（`@TableName` 实体字段直接用 Java enum）。

### Wrong vs Correct

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

## 测试策略

- **单测不连真库**：`MybatisVideoTaskRepositoryTest` mock 全部 Mapper，断言实体↔领域对象双向转换（含 `TaskNo` / `WorkspaceKey` 值对象与 String 列的互转）与查询条件。
- **真库连通由冒烟测试承担**：`VidSageApplicationTests` 标 `@ActiveProfiles("local")`（测试类路径的
  application.yml 会遮蔽主配置且不带 `spring.profiles.default`——不显式激活，数据源配置根本不加载，
  报「Failed to determine a suitable driver class」，2026-09-30 实测踩过）。
- 落库行为（insert 回填 id、查重 SQL 语义）的最终背书是真实运行证据
  （`09-24-vidsage-mvp1-submit/research/acceptance-evidence.md`）。
