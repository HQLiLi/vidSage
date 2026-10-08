# 会话移交（2026-10-08）：SubtitlePipeline 执行模型拆分 —— 待建任务

> 上一个任务（`10-08-progress-weight-flattening`）**已完结归档**（merge `7cdcb2c` → dev 已推，archive `db2bf67`）。
> 本文件是它的执行会话留给下一任务的热上下文。**任务尚未创建**。

## 一句话状态

候选下一任务：把 `SubtitlePipeline` 的**执行模型**（executor / semaphore / 排队）从**编排**（阶段顺序、进度回调）里拆出来。
尚未建任务、未开分支、未做任何调研——新会话开局读完本文档后，先问用户是否建任务，再进 brainstorm。

## 下一步怎么起

1. 读本文档 → 需要时补读 `archive/2026-10/10-08-progress-weight-flattening/design.md`（其 §七 与执行模型相关的诚实边界）。
2. **先通读 `backend/src/main/java/com/vidsage/application/task/pipeline/SubtitlePipeline.java` 全文**——本会话只见过它的进度/编排面，executor/semaphore/排队的实现细节没通读，别带着我这份二手摘要直接开写工件。
3. 用户点头后 `task.py create`，brainstorm 出 prd/design/implement 三件套。

## 动机（为什么现在做）

- 这是 `10-08` handoff 遗留清单第 2 条：「AI 建议等本任务落地后再评估」——扁平化已落地，条件齐了。
- 现状一个类身兼数职：任务异步执行 + 并发闸（semaphore）+ 阶段编排（`prepareAndClean` 的语句顺序）+ 进度回调组装（composite listener：先落库再广播）。职责混装的代价在 `10-07`/`10-08` 两次任务里都碰到过——改进度要小心不碰执行，改执行要小心不碰进度。
- 扁平化刚把**进度职责**彻底理干净（管线不再持区间、listener 纯透传），现在拆执行模型不会和进度逻辑纠缠。

## 已定边界——别在 brainstorm 里重开

1. **阶段顺序仍然只存在于编排语句的顺序里**（形状重构定的档位 A：不做列表化遍历；`10-08` design §七 边界 3 复述过）。执行模型拆分 ≠ 引入阶段列表/状态机——除非用户主动改档。
2. **翻译不在主管线**：`TRANSLATE_SUBTITLES` 任务成功后按需触发，直通 0..100，拆分不改这个结构。
3. **进度职责刚定型**：谁发事件谁取数、`ProgressMapper` 只剩 clean 批级一处、`SubtitleStageName` 是区间单源。拆分不得把这些搅回去。
4. 零配置项约束、技术栈不动——沿用 `10-08` prd 的 Constraints 口径（除非用户另说）。

## 本会话攒下的热上下文（二手，需对照源码验证）

- `SubtitlePipeline.runPipeline(taskNo)` 是执行入口；`prepareAndClean` 里 prepare → clean 顺序编排；收尾路径有 `semaphore.release()`。
- 测试构造形如 `service(2)`（`SubtitlePipelineTest`）——参数疑似并发度，拆分时这个口子要在设计里交代。
- 进度链：`progressListener(task)` 组 composite（落库先、广播后），事件在 `任务阶段事件` DEBUG 日志逐条可查——**行为不变**的验收可沿用 `10-08` 的机器判模式（测试全绿 + 真实链路事件流逐格比对）。
- SSE 快照+增量、去重只拦进行中、工作区 `./data/works` 相对 CWD——这些是任务平台的性质，拆分不该改变它们。

## 验收方式建议（沿用项目模式）

结构改造 → 改造前后行为逐格相同：单测全绿 + 若走真实链路，双实例 SSE 对照（AC7 的套路，worktree 对照点用 dev 当前位）。

## 环境坑（memory 里有，别重踩）

JDK 路径 `JAVA_HOME=/f/java_soft/java21`；量测试前删 `target/surefire-reports`、改名后必须 `mvn clean`；起冒烟实例后 `TaskStop` 杀不掉子 JVM，按端口 PID `taskkill //PID <pid> //F`；Git Bash 的 `/tmp` 不通用。

## 其他遗留（本任务范围外，顺带备忘）

1. **Javadoc 全局重构**——用户定另开一次，搭车 spec 两条约定（注释不抄 kebab 值；阶段名 = 动词-对象）。
2. **`backend-before-reconstruction/`**——用户 2026-10-08 明确**留着**，别再提议删。
3. `@Schema` 落位口径——未决，用户自行推进。
4. Trellis 还有 3 个 planning 任务：`09-24-vidsage`（父，[5/7]）、`09-24-vidsage-mvp4-summary`、`09-29-subtitle-translate-qwen-mt`——与本候选任务平级，选哪个由用户定。
