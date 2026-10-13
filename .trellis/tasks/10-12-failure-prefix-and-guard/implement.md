# 执行计划：失败前缀常量化 + 后置生命周期守卫改名

> 配套 `prd.md` / `design.md`。三文件、纯等价改动，一轮做完。
> 命令全部在 **Git Bash** 下、仓库根 `/f/personal_project/vidSage` 执行。

## 0. 动手前：取基线（必做）

```bash
cd /f/personal_project/vidSage/backend
rm -rf target/surefire-reports                                  # 报告会留陈旧 XML，量前先删
JAVA_HOME=/f/java_soft/java21 mvn -q clean test                 # JDK 必须显式 21；clean 防陈旧 class
grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' \
     target/surefire-reports/*.xml | python -c "
import sys,re
t=e=s=f=0
for l in sys.stdin:
    n=[int(x) for x in re.findall(r'\d+',l)]
    t+=n[0]; e+=n[1]; s+=n[2]; f+=n[3]
print(f'tests={t} errors={e} skipped={s} failures={f}')"
```

- **记下这个 tests 数当基线**（`errors=0 failures=0` 才行）。不要引用记忆/文档里的历史数字。
- 判别单个用例是否失败看 `<failure>` 子元素，**别**用 `<testcase/>` 是否自闭合推断。

## 1. 切分支

```bash
cd /f/personal_project/vidSage
git status --porcelain          # 必须干净（含 .trellis 下的本任务文档改动——见下）
git switch -c feature/failure-prefix-and-guard dev
```

> 本任务从 **dev** 切出（当前分支就是 dev），合回 dev。
> 注意：`prd.md` / `design.md` / `implement.md` 是本轮新写的**未跟踪/已改**文件；
> 若不想把它们带进 feature 分支的 diff 白名单，**先单独提交一次任务文档**再切分支，
> 或把它们与代码改动分两次提交（推荐后者：先 `chore(task): plan` 一笔文档，再一笔代码）。

## 2. 改 `VideoTask.java`（常量化 + 注释 + 改名）

文件：`backend/src/main/java/com/vidsage/domain/task/VideoTask.java`

### 2.1 新增两个常量（并入既有常量区块，`:276-281` 附近）

在 `REGENERATE_FAILURE_PREFIX` 旁插入，三个常量连成一整块。每条按 spec 要求带 Javadoc
（`quality-guidelines.md` §三：全大写常量一律留注释），首行大白话，写明「谁组装、谁认领」：

```java
/** 主管线失败的前缀：{@link #fail} 落的文案都以它开头，由 SubtitlePipeline 组装。 */
public static final String TASK_FAILURE_PREFIX = "任务执行失败：";

/** 翻译失败的前缀：{@link #translationFail} 落的文案都以它开头，由 SubtitlePipeline 组装；
 *  {@link #clearRegenerateFailure} 明确不认领它（另一台生命周期）。 */
public static final String TRANSLATION_FAILURE_PREFIX = "翻译失败：";
```

> **全角冒号**（`：`，U+FF1A），逐字符对齐既有常量。写成半角会让后面所有门都失真。

### 2.2 注释补引（4 处）

| 行 | 现在 | 改成 |
|---|---|---|
| `:82-83` `errorMessage` 字段 | 「各自带前缀区分」 | 逐一点名三个常量：`{@link #TASK_FAILURE_PREFIX}` / `{@link #TRANSLATION_FAILURE_PREFIX}` / `{@link #REGENERATE_FAILURE_PREFIX}` |
| `:188` `fail` 的 `@param` | 「（中文，面向用户；由调用方组装「缺什么、怎么补」）」 | 补一句「调用方带 `{@link #TASK_FAILURE_PREFIX}` 组装」 |
| `:244` + `:247` `translationFail` 的 Javadoc | 「（由调用方带「翻译失败：」前缀组装）」 | 引 `{@link #TRANSLATION_FAILURE_PREFIX}` |
| `:285` `clearRegenerateFailure` 的 Javadoc | 「翻译失败（「翻译失败：」前缀，另一台生命周期）」 | 引 `{@link #TRANSLATION_FAILURE_PREFIX}` |

`:265` `recordRegenerateFailure` 的 Javadoc **已经在引** `REGENERATE_FAILURE_PREFIX`，不动。

> `:188` 这一条**超出 prd 初稿列的三处**——prd 已按 2026-10-13 决定把它收进「做」清单。
> 理由：`TASK_FAILURE_PREFIX` 的消费者在 application 层，类内若无指认，常量就没有落点。
> 若 review 想砍，砍这一条不影响其余。

### 2.3 改名

- 声明 `:320`：`private void requireMainSucceededForTranslation(` → `requireMainSucceededForPostLifecycle(`
- 调用 `:219`、`:234`、`:251`、`:271` 同上。

**只替换完整标识符 `requireMainSucceededForTranslation`**（全仓唯一，无前缀冲突）。
**不要**按 `require` / `Translation` 做模式替换——`requireStatus`（同文件 5 处）是本改名的最大假朋友。
`.tooltip` 见 `design.md` §五。

## 3. 改 `SubtitlePipeline.java`（接生产者）

文件：`backend/src/main/java/com/vidsage/application/task/pipeline/SubtitlePipeline.java`

- `:302` `String message = "任务执行失败：" + reason;` → `VideoTask.TASK_FAILURE_PREFIX + reason;`
- `:323` `String message = "翻译失败：" + reason;`   → `VideoTask.TRANSLATION_FAILURE_PREFIX + reason;`
- `:315` Javadoc「错误信息带「翻译失败：」前缀落 error_message」→ 引
  `{@link VideoTask#TRANSLATION_FAILURE_PREFIX}`（`VideoTask` 已在 `:16` import，无需新增）

**不动** `:306` / `:309` / `:328` / `:330` / `:334` 五条半角冒号的日志格式串。

## 4. 改 `TranslationStatus.java`

文件：`backend/src/main/java/com/vidsage/domain/task/model/TranslationStatus.java`

- `:24` 枚举常量注释「（带「翻译失败：」前缀）」→
  引 `{@link com.vidsage.domain.task.VideoTask#TRANSLATION_FAILURE_PREFIX}`
  （该文件没有 `VideoTask` 的 import，**用全限定名，不为一句 Javadoc 加 import**）。

## 5. 验证（逐条对着 AC 跑）

```bash
cd /f/personal_project/vidSage
SPL=backend/src/main/java/com/vidsage/application/task/pipeline/SubtitlePipeline.java

# AC2 全角字面量零命中 —— 两条都应无输出
grep -n '"任务执行失败："' "$SPL"
grep -n '"翻译失败："'   "$SPL"

# AC3 反向门 1：半角日志串逐字在位（期望 1 / 2 行）
grep -n '"任务执行失败: ' "$SPL"
grep -n '"任务翻译失败: ' "$SPL"

# AC3 反向门 2（更硬）：整文件的 Java 字符串字面量集合，改动前后只该少这两条
grep -o '"[^"]*"' "$SPL" | sort | uniq -c > /tmp/spl-lit-after.txt
diff /tmp/spl-lit-before.txt /tmp/spl-lit-after.txt
#   期望输出：恰好两行删除（"翻译失败：" 与 "任务执行失败：" 各少 1 次），无新增
#   （/tmp/spl-lit-before.txt 需在第 3 步动手前先跑同一条生成）

# AC4 常量各至少被 1 处 main 代码引用
grep -rn 'VideoTask\.TASK_FAILURE_PREFIX\|VideoTask\.TRANSLATION_FAILURE_PREFIX' backend/src/main

# AC5 旧名零命中（限定 backend/src！归档文档里的史实引用不算）
grep -rn 'requireMainSucceededForTranslation' backend/src/
#   期望空。注意不要 grep 全仓——archive/2026-10/10-12-comment-domain-pilot/implement.md 有两处史实引用
grep -rc 'requireMainSucceededForPostLifecycle' backend/src/main/java/com/vidsage/domain/task/VideoTask.java
#   期望 5

# AC1 测试文件零改动 + AC6 diff 白名单
git diff --name-only                                   # 期望恰好 3 个 java 文件
git diff --name-only -- backend/src/test               # 期望空
git status --porcelain -- backend/src/test             # 期望空（防新增未跟踪测试文件）

# AC1 全绿 + 与基线同数
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q clean test
grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' \
     target/surefire-reports/*.xml | python -c "
import sys,re
t=e=s=f=0
for l in sys.stdin:
    n=[int(x) for x in re.findall(r'\d+',l)]
    t+=n[0]; e+=n[1]; s+=n[2]; f+=n[3]
print(f'tests={t} errors={e} skipped={s} failures={f}')"
#   期望：errors=0 failures=0，且 tests == 第 0 步基线
```

> 行尾：仓库 `core.autocrlf=true`，存储层是 LF。若中途混用过 `sed -i` 与 Edit 工具，
> 收尾前 `sed -i 's/\r$//' <三个文件>` 统一一遍；比对文本先 `tr -d '\r'` 再 diff。

## 6. Review gate（停下来等批准）

提交前把这三样贴出来给用户看：

1. `git diff` 全文（应只有三个文件、改动形状与 `design.md` §一表格一致）；
2. 第 5 步各门的结果（尤其 diff 白名单与基线 tests 数）；
3. 明确声明「测试文件一行未改」。

**改动到位即停**，等用户批准再提交、再合并、再归档——逐门批准，不自行推进。

## 7. 提交（批准后）

```bash
git add backend/src/main/java/com/vidsage/domain/task/VideoTask.java \
        backend/src/main/java/com/vidsage/application/task/pipeline/SubtitlePipeline.java \
        backend/src/main/java/com/vidsage/domain/task/model/TranslationStatus.java
git commit    # refactor(task): 失败前缀常量化并接到生产者，后置生命周期守卫改名
```

- 任务文档（`prd.md` / `design.md` / `implement.md`）若已在第 1 步单独提交，此处不再包含；
- 推送前带代理：`https_proxy=http://127.0.0.1:7897 git push`（直连 GitHub 必失败）。

## 8. 回滚点

| 点 | 动作 |
|---|---|
| 第 2 步后出问题 | `git checkout -- backend/src/main/java/com/vidsage/domain/task/VideoTask.java` |
| 第 3/4 步后出问题 | 同上，逐个文件 `checkout --` |
| 已提交未合并 | `git revert <sha>` |
| 分支已合并 | `git revert <sha>`（单提交，无数据/配置变更，安全） |

无部分回滚复杂度：三处改动彼此独立，`VideoTask` ↔ `SubtitlePipeline` 之间只靠常量引用耦合，拆开即回到今天的形态。

## 9. 收尾

- 测试基线变更写进 `.trellis/workspace/HeQiu/journal-1.md`；
- 本任务无 spec 需要更新（改动都在既有规范的射程内：常量带注释、方法动词开头都已成文）；
  若 review 时觉得「失败前缀常量该有一条 spec 口径」，那才是 `trellis-update-spec` 的活。
