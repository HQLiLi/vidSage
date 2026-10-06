# 执行计划：application 出站依赖倒置

> 契约与取舍见 `design.md`，验收标准见 `prd.md`。本文件只管**顺序、命令、门、回滚点**。

## 批次 0 —— 基线（已完成）

```
BASELINE classes=45 tests=465 errors=0 failures=0 skipped=1
```

命令（在 `backend/` 下）：

```bash
rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q clean test
```

**必须带 `clean`**：本轮要删类改名，`target/classes/` 里残留的旧 `.class` 会被 Spring 扫到，只删 `surefire-reports` 不够（2026-09-27 实测过同类故障）。同理，**测量前先删 `surefire-reports`**——XML 文件名是 `TEST-<全限定类名>.xml`，改名后旧报告不被覆盖，会把幽灵测试数进来。

数结果：

```bash
grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' target/surefire-reports/*.xml | python -c "
import sys,re
t=e=s=f=n=0
for line in sys.stdin:
    m=dict(re.findall(r'(\w+)=\"(\d+)\"',line))
    t+=int(m['tests']); e+=int(m['errors']); s+=int(m['skipped']); f+=int(m['failures']); n+=1
print(f'classes={n} tests={t} errors={e} failures={f} skipped={s}')
"
```

判定：`errors=0 failures=0`，`tests` 总数（含 skipped）应回到 **465**（类数会因改名而变，故只比数量）。

---

## 批次 1 —— 持久化

**改动**（`design.md` §2.1 / §4 / §5.1 / §6.1 / §6.2）

1. 新建 `domain/task/VideoTaskRepository.java`（接口，四方法，Javadoc 迁自现具体类）。
2. `infrastructure/persistence/repository/VideoTaskRepository.java` → 改名 `MybatisVideoTaskRepository.java`，加 `implements VideoTaskRepository`，删「不抽接口」类 Javadoc，方法体零改动。
3. 消费方 import 改 `domain.task.VideoTaskRepository`：`application/task/TaskService`、`application/task/TaskPipelineService`（`TaskService:75` 的构造参数 Javadoc 措辞一并调）。
4. 测试：`TaskServiceTest` / `TaskPipelineServiceTest` 只改 import 行；`VideoTaskRepositoryTest` → `MybatisVideoTaskRepositoryTest`（类型引用改名，断言零改动）。
5. `infrastructure/persistence/package-info.java` 的「保持具体类，不抽接口」重写。

**验证**：批次 0 的命令。预期 `tests=465 errors=0 failures=0`。

**回滚点 R1**：`git checkout -- .` + `git clean -fd`。批次 1 是独立可编译单元，单独 revert 不影响后续批次。

---

## 批次 2 —— LLM worker

**改动**（`design.md` §2.1 / §3.1 / §4 / §5.1 / §6.1 / §6.2）

1. 新建 `domain/subtitle/SubtitleCleanser.java` / `SubtitleTranslator.java` / `SubtitleContextSummarizer.java`（三个端口，各只声明上层调用的那一个签名）。
2. 新建 `domain/subtitle/CleanseResult.java` / `TranslateResult.java`（从两个 worker 的嵌套 record 提升为顶层 record，字段与顺序原样）。
3. 两个 worker 删除嵌套 record，改用 domain 的顶层 record。
4. 改名：`SubtitleCleanser` → `LlmSubtitleCleanser`、`SubtitleTranslator` → `LlmSubtitleTranslator`、`SubtitleContextSummarizer` → `LlmSubtitleContextSummarizer`，各加 `implements <对应端口>`。
   - **保留不动**：`LlmSubtitleCleanser` 的 3 参 `clean` 重载、包私有 `validate` / `baselineOf` / `ValidationFailure`；`LlmSubtitleTranslator` 的 3/4 参 `translate` 重载与包私有 `validate`。
5. 消费方：`SubtitleCleanupService`、`SubtitleTranslationService` 改 import；`SubtitleCleanser.CleanseResult` → `CleanseResult`、`SubtitleTranslator.TranslateResult` → `TranslateResult`。
6. 测试：`SubtitleCleanupServiceTest` / `SubtitleTranslationServiceTest` 改 import + 去限定名；`SubtitleCleanserTest` → `LlmSubtitleCleanserTest`、`SubtitleTranslatorTest` → `LlmSubtitleTranslatorTest`、`SubtitleContextSummarizerTest` → `LlmSubtitleContextSummarizerTest`。
7. `domain/subtitle/package-info.java` 补端口说明。

**验证**：同批次 1 的命令，预期 `tests=465 errors=0 failures=0`。

**回滚点 R2**：同 R1。

---

## 批次 3 —— 进程诊断

**改动**（`design.md` §2.2 / §3.2 / §4 / §4.1 / §5.2 / §6.2 / §6.3）

1. 新建 `application/diagnostics/ToolDiagnostics.java`（**application 定义的端口**，单方法 `List<ToolDiagnosticReport> diagnoseAll()`）。
2. 新建 `application/diagnostics/ToolDiagnosticReport.java`（`infrastructure/process/ExternalToolReport.java` 整体搬来 + 改名，`@Schema` 描述文案更新，字段名 / 类型 / 字段级注解参数不动）。
3. `infrastructure/process/ExternalToolDiagnostics.java` → 改名 `ProcessToolDiagnostics.java`，加 `implements ToolDiagnostics`（**这是本轮唯一一处 `infrastructure → application` 的窄口依赖**）；保留 `requireFfmpeg` / `requireFfprobe` / `requireYtDlp` 与全部包私有 static 助手。
4. **删除** `application/diagnostics/ToolDiagnosticsService.java`；`web/controller/MediaController` 改为注入 `ToolDiagnostics` 端口，返回 `List<ToolDiagnosticReport>`，删豁免 b 说明。
5. 同层消费方改按具体类注入：`FfmpegAudioExtractor:9,55,65`、`LocalFileMediaAcquirer:8,42,52`、`YtDlpRunner:5,34,46`。
6. 测试：`MediaControllerTest`（`@MockitoBean ToolDiagnosticsService` → `@MockitoBean ToolDiagnostics`；`ExternalToolReport` → `ToolDiagnosticReport`）；`ExternalToolDiagnosticsTest` → `ProcessToolDiagnosticsTest`；`FfmpegAudioExtractorTest` / `LocalFileMediaAcquirerTest` / `YtDlpRunnerTest` 的 mock 类型改名。
7. `application/diagnostics/package-info.java` 整段重写。

**验证**：同批次 1 的命令，预期 `tests=465 errors=0 failures=0`。

**回滚点 R3**：同 R1。

---

## 批次 4 —— web 层去 domain 化

**改动**（`design.md` §5.3 / §6.3）

1. `application/task/TaskProgressMessage` 新增派生方法，内部调 `VideoTask.isEventSettled`（单源不变）。
   - **命名必须是非 getter 形状**（用 `settled()`），否则 Jackson 会把它当属性序列化进 SSE payload。
2. `web/controller/TaskController:5,191`：删 `VideoTask` import，终态判定改 `snapshot.settled()`。
3. `web/sse/TaskSseHub:4,116`：删 `VideoTask` import，终态判定改 `event.settled()`。
4. `web/dto/TaskResponse`：`inputType` / `status` / `translationStatus` 三个字段类型改 `String`，`@Schema` 描述文案携带取值；`from(TaskSnapshot)` 取 `.name()`（`translationStatus` 可空，`== null ? null : ....name()`）；删三行 domain import。
5. `TaskControllerTest` / `TaskSseHubTest`：**保留** domain import（R8 豁免），只在必要时调整断言。

**验证（两条，都要跑）**

**① 全量测试**——同批次 0 的命令，预期 `tests=465 errors=0 failures=0`。

**② SSE payload 键集比对（AC5，不许推断）**：

```bash
grep -rn "class=\"\|content()\|jsonPath\|assertThat" backend/src/test/java/com/vidsage/web/sse/TaskSseHubTest.java | head -20
```

- 先看 `TaskSseHubTest` 是否对 payload 做了完整 JSON 断言——若是，多出的 `eventSettled` 字段会直接让它失败，天然兜底。
- 若断言不完整，则显式比对：改造前后各取一个 `TaskProgressMessage` 实例，用项目的 ObjectMapper 序列化，**键集必须逐字一致**（`taskNo` / `taskStatus` / `stage` / `translationStatus`，不多不少）。

**③ 依赖归零（AC1a）**：

```bash
cd /f/personal_project/vidSage && \
grep -rn "com\.vidsage\.infrastructure" \
  backend/src/main/java/com/vidsage/application backend/src/main/java/com/vidsage/web ; \
grep -rn "com\.vidsage\.domain" backend/src/main/java/com/vidsage/web
```

预期：**两条都零输出**。

**回滚点 R4**：`git checkout -- backend/src/main/java/com/vidsage/web backend/src/main/java/com/vidsage/application/task/TaskProgressMessage.java`。

---

## 批次 5 —— spec 与 package-info 对账

**改动**：`design.md` §7 全表。逐文件核对，**不留失实表述**：

- `.trellis/spec/backend/directory-structure.md`（14 处，见 §7.1；含新增「web main 只依赖 application」条款）
- `.trellis/spec/backend/quality-guidelines.md`（§7.2，整节重写）
- `.trellis/spec/backend/provider-pattern.md`（§7.3）
- package-info 7 处（§7.4）
- 代码 Javadoc（§7.5，若批次 1–4 未顺手改完则在此补齐）

**本批次不跑测试**（纯文档，不触碰代码）。但需复核：改完的 spec 与**实际代码**逐条对得上——尤其依赖规则段的「零豁免 + 窄口白名单」表述，必须与批次 4 的 AC1a grep 结果一致。

**回滚点 R5**：`git checkout -- .trellis/spec backend/src/main/java`。

---

## 收尾门（硬规则）

批次 1–5 全部完成、测试全绿、AC1a/AC1b 与 AC5 全部验证之后：

**门 1 —— 停下等审查。** 汇报必须包含：

- 改了什么（按批次的文件清单）
- 怎么验证的（`mvn clean test` 结果数字 + AC1a/AC1b grep 结果 + **SSE payload 键集比对的实际输出**）
- **哪些没验证**（本轮无运行时验收；HTTP 响应体的形状只做了代码级与序列化级比对，未起实例实测）

**不得**在同一次执行里继续 `git commit` / `git push` / 合并 / 归档。

**门 1 批准后**，按 `design.md` §13 分两次提交：

1. `refactor(backend): 出站依赖倒置——端口归 domain/application，infrastructure 实现；web 去 domain 化`
2. `docs(spec): 对账端口倒置后的依赖规则、归属判据与包说明`

随后 `git push -u origin feature/port-inversion`，回到审查窗口（门 2 由项目所有者开）。

---

## 执行期约定

- **遇到规范冲突就停下来问**：本项目有若干非 DDD 视角的既有规范，若改动与某条既有规范**互相矛盾**（不是补写、是真的打架），停下来问项目所有者，不自行选边。
- **不夹带私货**：本批次只做依赖倒置。读到「该进 domain 却没进」的规则，记下来单独提，不混进本轮 diff。
- **Javadoc 写法**：新建端口与改写的类 Javadoc，首行用大白话讲清「这是干什么的」，避开「契约 / 结构性 / 落点」这类抽象腔；`@param` / `@return` / `@throws` 照旧齐全。
- **端口归属有疑问时**：按 `design.md` §1.2 的判据与兜底问题走；判据给出矛盾结论时停下来问，不自己选边。
