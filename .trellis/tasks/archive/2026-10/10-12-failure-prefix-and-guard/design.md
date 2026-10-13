# 设计：失败前缀常量化 + 后置生命周期守卫改名

> 配套 `prd.md`。本任务**行为必须逐字等价**——不改任何可执行语义，只改「同一份文案的出处」
> 与一个私有方法的名字。故本文重点在**边界**（改哪几行、哪些形近串必须原样）与**判据**
> （常量放哪、叫什么、注释怎么引），不在算法。

## 一、改动边界一览

只有三个 main 源文件，零测试文件，零资源文件。

| 文件 | 改动 | 净增删 |
|---|---|---|
| `domain/task/VideoTask.java` | 新增 2 常量（并入既有常量区块）+ 4 处注释补引 + 1 处改名（1 声明 + 4 调用） | +2 常量带 Javadoc；注释若干行 |
| `application/task/pipeline/SubtitlePipeline.java` | 2 处字面量改引常量 + 1 处 Javadoc 改引常量 | 3 行 |
| `domain/task/model/TranslationStatus.java` | 1 处枚举常量注释改引常量 | 1 行 |

**不在边界内、且必须原样**（写在这里是给 review 和验收脚本当白名单用）：

- `SubtitlePipeline` 里 5 条**半角冒号**的日志格式串（`:306`、`:309`、`:328`、`:330`、`:334`）；
- `backend/src/test/**` 全部（约 20 处全角前缀字面量断言）；
- `schema.sql:24` 的 DDL 注释；
- `archive/2026-10/10-12-comment-domain-pilot/implement.md:207,280` 与任何 journal 里对旧名的史实引用。

## 二、常量：值、可见性、摆位

### 值

| 常量 | 值 | 消费者 |
|---|---|---|
| `TASK_FAILURE_PREFIX` | `"任务执行失败："`（**全角冒号**） | `SubtitlePipeline.failTask`（`:302`） |
| `TRANSLATION_FAILURE_PREFIX` | `"翻译失败："`（**全角冒号**） | `SubtitlePipeline.failTranslation`（`:323`） |
| `REGENERATE_FAILURE_PREFIX`（不动） | `"重新生成失败："` | `TaskProgressNotifier.failRegenerate`（`:98`）；类内 `clearRegenerateFailure`（`:289`） |

值必须与改动前**逐字符相同**，含全角冒号 `：`（U+FF1A）而非半角 `:`（U+003A）。这是本任务最容易
手滑的一处——半角化会让 AC 的字面量门「通过」而行为已变。

### 可见性与形态

`public static final String`，与既有 `REGENERATE_FAILURE_PREFIX` 同形。

- 必须 `public`：消费者在 application 层，跨层引用；
- 必须是**编译期常量**（字面量初始化）：`SubtitlePipeline` 侧会内联，行为与内联前一致。
  不要写成 `String.join` / `new String(...)` 之类的运行期构造——那会改变字符串身份语义
  （虽然 `equals` 仍等价，但没必要引入差异）。

### 摆位（决定 1：聚簇）

三个常量连成一块，留在 `REGENERATE_FAILURE_PREFIX` 现有位置（`recordRegenerateFailure` 与
`clearRegenerateFailure` 之间，约 `:276`）。做法：把新两个插到 `REGENERATE_FAILURE_PREFIX` 的
Javadoc 之前/之后，让「三个前缀」在读源码时是一整块。

配套：`errorMessage` 字段注释（`:82-83`）作为这三者的**总索引**，逐一点名。

> 已知代价（prd 附已记）：`fail`、`translationFail` 旁边不再就近有常量。补偿手段是
> 各方法 Javadoc 里的 `{@link}` 指过来——`recordRegenerateFailure` 现在就是这么做的（`:265`），
> 本次把 `fail`、`translationFail` 补齐到同一密度。

## 三、常量 Javadoc 的写法

spec 硬性要求（`quality-guidelines.md` §三）：**全大写常量一律保留注释，哪怕只是一句人话翻译**；
且首行用大白话、避开正式抽象腔（`comment-style-plain` 口径）。

三个常量各自要回答的一件事：**谁组装、谁认领**。参照既有 `REGENERATE_FAILURE_PREFIX` 的 Javadoc
（`:276-280`，点名「组装方与清除方共用」）写。要点：

- `TASK_FAILURE_PREFIX`：`SubtitlePipeline.failTask` 组装；无清除方（主管线失败即终态）。
- `TRANSLATION_FAILURE_PREFIX`：`SubtitlePipeline.failTranslation` 组装；`clearRegenerateFailure`
  **明确不认领**它（那是另一台生命周期）——这一点既有的 `:285` 已经说了，引用改成常量即可。
- 三者的共同点（`error_message` 一列、同任务同时刻只有一种「最近失败」）写在 `errorMessage` 字段注释里，
  不重复写在每个常量上。

## 四、注释改引的四种形态（别写错链接语法）

| 位置 | 写法 | 理由 |
|---|---|---|
| `VideoTask` 内部（`:82`、`:188`、`:244`、`:247`、`:285`） | `{@link #TASK_FAILURE_PREFIX}` / `{@link #TRANSLATION_FAILURE_PREFIX}` | 同类成员，`#` 即可 |
| `SubtitlePipeline:315` | `{@link VideoTask#TRANSLATION_FAILURE_PREFIX}` | `VideoTask` 已在该文件 import（`:16`），简单名可用 |
| `TranslationStatus:24` | `{@link com.vidsage.domain.task.VideoTask#TRANSLATION_FAILURE_PREFIX}` | 该文件**没有** `VideoTask` 的 import，且**不为一句 Javadoc 加 import** |

`TranslationStatus` 这一条是决定 2 里代价最明显的一处：Javadoc 层出现 `domain.task.model` →
`domain.task` 的反向引用（运行时无依赖，`VideoTask` 本来就依赖 `TranslationStatus`）。
**若 review 时觉得不值，删这一条不影响其余任何改动。**

## 五、改名：范围与「假朋友」清单

`requireMainSucceededForTranslation` 是**私有**方法，无反射 / AOP / 序列化按名引用，改名零运行时风险。

精确命中（改动前，全仓）= 5 处代码 + 3 处文档：

```
backend/src/main/java/com/vidsage/domain/task/VideoTask.java:219,234,251,271,320   ← 目标，改
.trellis/tasks/archive/2026-10/10-12-comment-domain-pilot/implement.md:207,280    ← 史实，不改
.trellis/tasks/10-12-failure-prefix-and-guard/*（本任务自身文档）                  ← 描述用，不改
```

**必须保护的同族标识符**（含 `require`，极易被朴素替换误伤）：

- `VideoTask.requireStatus`（声明 `:330`，调用 `:167/:179/:192/:206`，共 5 处）——**同文件内的另一个守卫**，
  是本改名最大的假朋友；
- `requireTaskNo`（`TaskSseConnectionManager` 2 调用 + 1 声明、`TaskSubscriptionRegistry` 3 调用 + 1 声明）；
- `requirePublicUrl`、`requireTitle`、`requireFfmpeg`、`requireFfprobe`、`requireYtDlp`、`requireResolved`；
- `requiredAudioSpec`、`required = false`、`requiredMode`。

安全做法：**只替换完整标识符 `requireMainSucceededForTranslation`**（该串在全仓唯一，无前缀冲突——
`requireMainSucceededFor` 这一前缀只此一用），不要按 `require...Translation` 或 `Translation` 做模式替换。

新名的合规性（spec `directory-structure.md`「方法名动词开头」·2026-10-12 立）：`requireXxx` 属
`quality-guidelines.md` 列出的**异常路径命名模式** `requires...`，是合规动词前缀，无需另行论证。
`requireStatus` 同理，本次不动它。

## 六、行为等价论证

1. **字符串值不变** → 落库的 `error_message`、广播的终态事件消息、日志里插值的 `message` 全部逐字相同。
2. **常量内联** → `VideoTask.TASK_FAILURE_PREFIX + reason` 与 `"任务执行失败：" + reason` 编译后等价。
3. **私有方法改名** → 唯一效果是异常 message 里不含方法名（message 由 `action` 参数拼装，与守卫名无关）。
   逐条核对 4 个 `action` 实参不变：`"启动翻译"` / `"收敛翻译为 SUCCEEDED"` / `"翻译标记失败"` / `"记录重新生成失败"`。
4. 注释改动不参与编译。

**唯一真实风险**是「手滑改到不该改的行」——由 `implement.md` 的反向门与 diff 白名单兜底。

## 七、回滚

单提交、三文件、无数据变更、无配置变更。回滚 = `git revert <commit>`，或分支未合并时直接丢弃分支。
无部分回滚需求（三处改动互相独立，真要只留一半也做得到——`VideoTask` 与 `SubtitlePipeline` 之间
的唯一耦合是常量引用，拆开即回到今天的形态）。
