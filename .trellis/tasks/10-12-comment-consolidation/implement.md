# 注释收口 · 执行计划

> **给执行者（AI）的执行脚本**，不是给用户的清单——用户 2026-10-12 明确本任务由 AI 全量扫 main。
> 粒度标准见 `design.md`，验收标准见 `prd.md`。

## 0. 子任务顺序与门

```
子任务 1  domain 试点（含正典定稿 + 两份 quality-guidelines 改写）  ← 先做，钉标准
   └─ 门 G1：用户审查 domain 的 diff 与正典草稿，确认标准      ← 不过就不往下走
子任务 2  application
子任务 3  infrastructure
子任务 4  web / shared / config
子任务 5  backend test
子任务 6  frontend
   └─ 子任务 6 收尾时装钩子（AC7）并跑全量验收
```

**G1 是硬门**：domain 试点没被确认前，不许开子任务 2–6。理由见 `design.md` §6——
标准错了会波及 144 个文件，返工面太大。

**每个子任务自己的门**：改完 → 停下 → 报「改了哪些文件 / 例外登记 / 待订正清单」→ 等用户点头 →
才 `task.py finish` 并进下一个。不自行提交（分支工作流硬规则）。

## 1. 每个子任务的标准动作

**Step A · 定调查面（不写代码）**

```bash
cd f:/personal_project/vidSage/backend/src/main/java
# 1) 长尾块：>15 行的 Javadoc（粗筛信号，统计脚本见本文件 §5）
# 2) 变更史 / 归档指针：**模式清单只住正典 §六**，照抄那一段即可，此处不复制
# 3) 层级错位（2026-10-12 domain 试点补入）：实现快照 / 跨类类比 / 实现层细节混进契约
grep -rnE '同构|同一个道理|的处理|一致）|无字段无方法|不开放 setter|compact constructor|由 Spring 注进来|@ConditionalOnProperty|给测试用|当前是|目前分' --include=*.java .
```

**三批的并集就是要动的文件。** 第 3 批是试点审查时补的：头两批是**机械特征**，
筛不出「一个 6 行的注释既无变更史、也不超 15 行，却满篇是实现快照与跨类类比」这类问题——
domain 试点因此从 26 个文件扩到 32 个。**后续每层都必须跑第 3 批**，
并按 `guides/comment-guidelines.md` §三的**十三条禁令**逐文件过一遍（grep 只捞典型词，捞不全）。

中位数那批（既无变更史、不超 15 行、也不命中第 3 批）不碰。

**Step B · 逐文件过内容分流表**（`design.md` §1.2）

对每个待动文件：把注释按四问分类 → 该上移的上移 → 该删的删 → 该留的压缩。
**上移优先于删除**：能搬去 `package-info` / spec / `design.md` 的，先搬再删本地副本。

**Step C · 订正腐坏**

断句残句、与代码矛盾的表述：改注释不改代码，逐条登记。

**Step D · 自检**

```bash
# 只改注释？（应只有注释行）
cd f:/personal_project/vidSage && git diff -w --stat
# 变更史归零？（本层）—— 模式清单只住正典 §六，照抄那一段
grep -rnE "<正典 §六 的模式清单>" <本层目录>            # 期望命中 0
# 长尾收敛？（粗筛：>15 行的块逐个复核「是否只回答那四件事」）
<块行数统计脚本>
```

**Step E · 报审查**：输出三段——① 改动文件清单 ② 例外登记（>15 行的块及理由）③ 待订正清单。

## 2. 例外登记表（滚动填）

> 超过 15 行的 Javadoc 块在这里逐条登记。空表 = 没有例外。

| 层 | 文件 | 块位置 | 行数 | 理由 |
|---|---|---|---|---|
| domain | `subtitle/package-info.java` | :1 | 19 | **package-info 豁免**（AC2 明文）。承载 subtitle 包的桶结构 + 构件清单 + 依赖方向；19 行已是从 22 行压过的，再砍就要删构件名。 |
| domain | `package-info.java`（domain 根） | :1 | 16 | **package-info 豁免**。本块**无任何变更史**，收口时一字未动——它出现在这里只是因为 AC2 的机械线扫到了块类型。**这条记录本身是校准数据**：说明 15 行线单独用会把干净文档也标红。 |
| domain | `shared/model/SourceUri.java` | :1 | 16 | **用户 2026-10-12 校准轮亲手定的文本**。类注释按新判据回答四件事（是什么 / 不变量 / 与其他类型的关系 / 边界），每件各一段，加 `@param` 恰好 16 行。**压到 15 行就得牺牲其中一件** —— AC2 的 15 行线对「4 件事 + record 参数」的类型定得过紧，建议后续各层按「块是否只回答那四件事」判，而不是数行数。 |
| （待填） | | | | |

## 3. 待订正清单（滚动填）

> 发现的断句残句 / 失实表述。已发现的种子：

| # | 位置 | 现象 | 处置 |
|---|---|---|---|
| 1 | `backend/src/main/java/com/vidsage/web/dto/TaskResponse.java:45-47` | Javadoc 断句残句：「字段一一对应，产物清单逐元素映射为」后无下文 | 补全或删整句，归 web 子任务 |
| 2 | `web/dto/*` 等 13 文件 50 字段 | `@param` 与 `@Schema(description=…)` 文案重复 | **本任务不动**（父 `prd.md`「不做」），只在两者不一致时订正 |

## 4. 正典文档骨架（子任务 1 产出）

`.trellis/spec/guides/comment-guidelines.md` 至少含：

1. 第一原理（删了会不会有人踩坑）
2. 内容分流表（`design.md` §1.2 的表格）
3. 按层级的写法（§1.3，Java 与 TS 各配例子）
4. 正例 / 反例对照：拿本仓库真实前后各一段（如 `TaskConflictException` 收口前后）
5. 改代码前去哪找「为什么」（`.trellis/tasks/archive/**/design.md`）
6. 钩子：脚本全文 + 重装方法 + 已知缺口

同时在两份 `quality-guidelines.md` 的注释章节头部落一行指针。

## 5. 收尾（子任务 6 之后）

```bash
cd f:/personal_project/vidSage/backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' target/surefire-reports/*.xml
```

- 基线**实测**，不引用历史数字（历史 570 已被后续任务刷新）
- 全量 AC1 两遍：`backend/src/{main,test}/java`、`frontend/src`
- AC7：装钩子 → 故意写日期 → 验拦截 → 撤掉那行假数据
