# 后端包结构改为技术分层 — 执行计划

## 基线（已实测，非引用）

```
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q test
```

实测结果：**16 个测试全绿**（`VidSageApplicationTests` 1 + `DashScopeAsrProviderTest` 11 + `AsrControllerTest` 4），0 失败 / 0 错误 / 0 跳过。

> 上一会话 `handoff.md` 记录的「6/6 绿」有误，以本次实测 16 为准。

回滚点：`b43e0c5`（重构前 `master` HEAD）。

## 执行顺序

### 阶段 1：搬包（纯机械，不触碰业务逻辑）

- [ ] **1.1** `git mv` 搬 9 个主文件与 2 个测试文件到新路径

  **必须用 `git mv`**，不要「删除 + 新建」，否则 git 无法识别为 rename，diff 不可读。

  目标路径见 `design.md` 第 2 节映射表。

- [ ] **1.2** 改 11 个文件的 `package` 声明

- [ ] **1.3** 改 21 处 `import`（主 13：`ASRProvider` 2 + `TranscriptionService` 3 + `DashScopeAsrProvider` 5 + `AsrController` 3；测试 8：`AsrControllerTest` 4 + `DashScopeAsrProviderTest` 4）

  `model/asr/` 的四个契约类互相引用都在同包内，**只改 package、不改 import** —— 若这四个文件出现 import 变更，说明搬包位置错了，停下来核对。

- [ ] **1.4** 编译验证：`cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q compile`

- [ ] **1.5** 删空目录：`asr/` 下搬空后的残留目录，以及磁盘上已存在的空 `backend/src/main/java/com/vidsage/config/`

  ```bash
  find backend/src -type d -empty -delete
  ```

- [ ] **1.6** 跑测试

  ```bash
  cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q test
  grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' target/surefire-reports/*.xml
  ```

  期望 3 个 testsuite、合计 16 测试、全 0。

  ```bash
  # 确认测试类已落在新包
  grep -l "com\.vidsage\.controller\.AsrControllerTest\|com\.vidsage\.provider\.asr\.dashscope" target/surefire-reports/*.xml
  ```

### 🚦 Gate A：代码层必须绿透才能动规范

**通过条件**（两条同时满足）：

1. `mvn test` 16/16 绿
2. `git diff` 对已跟踪 `.java` 文件**只含 `package` 与 `import` 行变更**

```bash
git add -A
git diff -M --cached -U0 | grep -E "^[+-][^+-]" | grep -v -E "^[+-](package |import )"
# 期望：无输出。有输出即表示夹带了逻辑改动，回退重做。
# 注意 -v 的模式必须带 +/- 前缀：diff 行首是 "-package ..." 而非 "package ..."。
# 漏掉前缀会导致一条都滤不掉、命令永远有输出，检查形同虚设（初稿即犯此错）。
```

```bash
git diff -M --cached --stat
# 期望：9 主 + 2 测试显示为 {old => new} 形式的 rename，而非删除+新增
```

**为什么设这道闸**：若搬包本身有问题，改 spec 只是把错误固化进文档。代码绿了再改规范，spec 描述的才是真实存在的结构。

### 阶段 2：重写规范

- [ ] **2.1** 重写 `.trellis/spec/backend/directory-structure.md`

  必须包含 `design.md` 第 8 节列出的 7 项。要点：

  - 删除全部 package-by-feature 表述（当前第 3 节「按功能分包，不按技术分层」整节作废）
  - 新增顶层分包判据表（每包「允许/禁止出现」）
  - 新增 `model`/`domain`/`persistence` 判据表 + `domain` 升级触发条件
  - 新增持久化三层（Entity/Mapper/Repository）职责与可见性规则：三层**共存**，受限的是可见性（`service/` 不许直接依赖 `Mapper`）；`XxxRepository` 不预抽 `interface` + `Impl`（**不是**「禁用 `repository`」，该表述已由 `design.md` §4.1 判定为错误）
  - 新增「目录随代码出现，不预建空目录」硬规则，并写明空目录曾造成误导（本次需求的起因之一）
  - 保留既有命名约定：接口不加 `I` 前缀、`ASRProvider` 全大写不「顺手改正」、`@Component` 而非 `@Service`、契约不叫 `DTO`/`VO`、测试包镜像对应
  - 说明 `config/` 是未来落点、当前未创建

  写完自检：一个没读过本任务的新会话，能否只依据这份文档把一个新功能（`llm`）的所有类落到正确目录。

- [ ] **2.2** 修 3 份 spec 的失效路径引用（`design.md` 第 1、2 节的新路径为准）

  | 文件 | 待修行号 | 说明 |
  |---|---|---|
  | `provider-pattern.md` | 23, 24, 25, 26, 102, 112, 135 | 行 23-26 是四件套表格的 4 行；行 102 一行内含 2 处引用 |
  | `quality-guidelines.md` | 14, 74, 147 | 行 74 一行内含 4 处引用 |
  | `error-handling.md` | 12, 53, 110 | 行 110 的 `asr/web/AsrController.java` 同时出现在正文说明中 |

  合计 **13 行**待修。**行号是重构前的快照**，阶段 2.1 重写 `directory-structure.md` 不影响这三份文件的行号；但仍以执行时的 grep 结果为准，不要只按行号改。

  ```bash
  grep -rn "asr/model\|asr/web\|asr/provider\|com\.vidsage\.asr" .trellis/spec/
  ```

- [ ] **2.3** 更新 `.trellis/spec/backend/index.md` 中 `directory-structure.md` 的索引描述

  当前为「包结构、按功能分包、类与文件命名」，含已废弃的「按功能分包」。

- [ ] **2.4** 检查 `directory-structure.md` 的「不适用」一节

  其中引用了 `database-guidelines.md` 与 `logging-guidelines.md` 已删除；重写时保留或按新结构改写，不要丢失这条信息。

### 阶段 3：同步下游任务

- [ ] **3.1** 改 `09-24-vidsage-mvp3-translate/implement.jsonl` 第 3 行的 `reason`

  当前：`"新包 com.vidsage.llm 按功能分包约定"` → 改为技术分层下的落点：`model/llm/`、`provider/llm/`、`service/` 加类、`controller/` 加类。

- [ ] **3.2** 改 `09-24-vidsage-mvp3-translate/handoff.md` 的「待用户确认的事项」第 1 项

  其中 `com.vidsage.llm`：`LLMProvider` / `LLMCommand` / `LLMResult` + `provider/deepseek/DeepSeekLLMProvider` 的草案需改写为新结构落点。

  **「本会话已完成」一节属历史记录，不改写** —— 那是当时真实发生的事。若需标注结构已变更，另加一行说明。

- [ ] **3.3** 检查 `09-24-vidsage-mvp4-summary` 与 `09-24-vidsage-mvp2-subtitles` 的 prd/design 是否含旧结构假设

  已知 `mvp2/prd.md:11`、`epic design.md:19` 提到 `ASRProvider` 与配置键，**不含包路径**，无需改动 —— 执行时用 grep 复核，不要凭此结论跳过。

### 🚦 Gate B：全量验收

- [ ] **4.1** 逐条核对 `prd.md` 的 8 条验收标准，全部通过才能提交

  ```bash
  # 无旧包声明
  grep -rn "package com\.vidsage\.asr" backend/src; echo "exit=$? (期望 1，即无匹配)"

  # spec 无失效路径
  grep -rn "asr/model\|asr/web\|asr/provider\|com\.vidsage\.asr" .trellis/spec/; echo "exit=$? (期望 1)"

  # 无空目录
  find backend/src -type d -empty

  # config/ 未创建
  test ! -d backend/src/main/java/com/vidsage/config && echo "OK: config/ 不存在"

  # 测试仍全绿
  cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q test
  ```

- [ ] **4.2** 派 `trellis-check` 做质量检查（spec 合规 + 路径引用完整性 + 是否有夹带的逻辑改动）

### 阶段 4：收尾

- [ ] **5.1** 确认 spec 已同步（本任务的 spec 改动即交付物本身）
- [ ] **5.2** 单次原子提交 —— 代码搬包 + spec 重写 + 下游同步放同一提交，保证任何单点回滚都得到自洽状态

  提交信息形如：`refactor: 后端包结构改为技术分层（controller/service/model/provider）`

## 回滚点

| 回滚点 | 位置 | 适用情况 | 操作 |
|---|---|---|---|
| R0 | 重构前 HEAD `b43e0c5` | 整体失败 | `git reset --hard b43e0c5` |
| R1 | Gate A 通过后 | 阶段 2 规范改写出问题、代码层无问题 | 只还原 spec 文件，保留搬包 |
| R2 | Gate B 前 | 下游同步（阶段 3）误改 | 只还原 `.trellis/tasks/09-24-vidsage-mvp3-translate/` |

无数据库、无配置、无环境副作用，任何回滚点都是完整可还原的。

## 风险提示

- **最大风险是夹带改动**：搬包时顺手「优化」代码会让 Gate A 的 `git diff` 检查失效，也让回滚不再干净。本次纪律是 0 逻辑改动。
- **次大风险是 spec 漏改**：路径引用分散在 3 份文档共 13 行，靠人眼数容易漏；用 `design.md` 第 1 节的新路径逐条比对，不要凭记忆。
