# 步骤 4 证据：测试重分（旧方法名 → 新方法名，35 条一条不丢）

> 任务 `10-21-subtitle-run-flow-split`｜AC5 的证据。
> 命令：`cd backend && mvn clean test`；逐类计数取自 `target/surefire-reports/TEST-*.xml`。

## 逐条对照（35 行）

### 来自 `SubtitlePipelineTest`（22 → 14 MAIN + 4 RECLEAN + 4 TRANSLATE）

| # | 旧方法名（`SubtitlePipelineTest`） | 新类 | 新方法名 |
|---|---|---|---|
| 1 | `runsPrepareThenCleanAndFinishesRun` | `SubtitleMainServiceTest` | `runsPrepareThenCleanAndFinishesRun` |
| 2 | `wrapsEachStageWithArtifactPlaceholderAndSettle` | `SubtitleMainServiceTest` | `wrapsEachStageWithArtifactPlaceholderAndSettle` |
| 3 | `passesArtifactReadabilityToPreparationAndCleanup` | `SubtitleMainServiceTest` | `passesArtifactReadabilityToPreparationAndCleanup` |
| 4 | `probesFingerprintBeforePreparing` | `SubtitleMainServiceTest` | `probesFingerprintBeforePreparing` |
| 5 | `reuseHitOnOriginalSkipsPlaceholderAndSettle` | `SubtitleMainServiceTest` | `reuseHitOnOriginalSkipsPlaceholderAndSettle` |
| 6 | `reuseHitOnCleanedSkipsPlaceholderAndSettleAndCascade` | `SubtitleMainServiceTest` | `reuseHitOnCleanedSkipsPlaceholderAndSettleAndCascade` |
| 7 | `cleanedMissPointsToStillReadyOriginalWhenOriginalIsReused` | `SubtitleMainServiceTest` | `cleanedMissPointsToStillReadyOriginalWhenOriginalIsReused` |
| 8 | `throwsWhenArtifactTableSaysNoIntactOriginalButPreparationReportsReuse` | `SubtitleMainServiceTest` | `throwsWhenArtifactTableSaysNoIntactOriginalButPreparationReportsReuse` |
| 9 | `readsKeywordsByTaskId` | `SubtitleMainServiceTest` | `readsKeywordsByTaskId` |
| 10 | `uploadWorkspaceRebuildsInWorkspaceInputAtSanitizedSourceKeyDir` | `SubtitleMainServiceTest` | `uploadWorkspaceRebuildsInWorkspaceInputAtSanitizedSourceKeyDir` |
| 11 | `prepareFailureFinishesRunWithReasonAndSkipsClean` | `SubtitleMainServiceTest` | `prepareFailureFinishesRunWithReasonAndSkipsClean` |
| 12 | `settleFailureFinishesRunAndSkipsClean` | `SubtitleMainServiceTest` | `settleFailureFinishesRunAndSkipsClean` |
| 13 | `failureMessageFallsBackToExceptionClassNameWhenMessageNull` | `SubtitleMainServiceTest` | `failureMessageFallsBackToExceptionClassNameWhenMessageNull`（**只保留这一条**） |
| 14 | `queuedMainRunInterruptFinishesRunWithoutRunningStages` | `SubtitleMainServiceTest` | `queuedMainRunInterruptFinishesRunWithoutRunningStages` |
| 15 | `regenerateRunsCleanupAndFinishesRun` | `SubtitleRecleanServiceTest` | `regenerateRunsCleanupAndFinishesRun` |
| 16 | `regenerateWrapsCleanupWithArtifactPlaceholderAndSettle` | `SubtitleRecleanServiceTest` | `regenerateWrapsCleanupWithArtifactPlaceholderAndSettle` |
| 17 | `regenerateFailureFinishesRunWithReason` | `SubtitleRecleanServiceTest` | `regenerateFailureFinishesRunWithReason` |
| 18 | `queuedRegenerateInterruptFinishesRun` | `SubtitleRecleanServiceTest` | `queuedRegenerateInterruptFinishesRun` |
| 19 | `translateRunsTranslationThenCreatesPlaceholderAndSettles` | `SubtitleTranslateServiceTest` | `translateRunsTranslationThenCreatesPlaceholderAndSettles` |
| 20 | `translateReusesCurrentIntactTranslationWithoutCallingStage` | `SubtitleTranslateServiceTest` | `translateReusesCurrentIntactTranslationWithoutCallingStage` |
| 21 | `translateFailureFinishesRunWithoutPlaceholder` | `SubtitleTranslateServiceTest` | `translateFailureFinishesRunWithoutPlaceholder` |
| 22 | `queuedTranslationInterruptFinishesRun` | `SubtitleTranslateServiceTest` | `queuedTranslationInterruptFinishesRun` |

### 来自 `SubtitleTaskServiceTest`（13 → 9 MAIN + 2 RECLEAN + 2 TRANSLATE）

| # | 旧方法名（`SubtitleTaskServiceTest`） | 新类 | 新方法名 |
|---|---|---|---|
| 23 | `submitUrlCreatesIdentityAndDispatchesMainRun` | `SubtitleMainServiceTest` | `submitUrlCreatesIdentityAndDispatchesMainRun` |
| 24 | `secondSubmitInsertsNewRunInsteadOfConflicting` | `SubtitleMainServiceTest` | `secondSubmitInsertsNewRunInsteadOfConflicting` |
| 25 | `conflictWhenTaskAlreadyHasRunningRun` | `SubtitleMainServiceTest` | `conflictWhenTaskAlreadyHasRunningRun` |
| 26 | `concurrentDuplicateSourceFallsBackToLockedRead` | `SubtitleMainServiceTest` | `concurrentDuplicateSourceFallsBackToLockedRead` |
| 27 | `identityIsResolvedBeforeTheTransactionOpens` | `SubtitleMainServiceTest` | `identityIsResolvedBeforeTheTransactionOpens` |
| 28 | `keywordsWrittenOnlyWhenRequestCarriesThem` | `SubtitleMainServiceTest` | `keywordsWrittenOnlyWhenRequestCarriesThem` |
| 29 | `blankUrlIsRejected` | `SubtitleMainServiceTest` | `blankUrlIsRejected` |
| 30 | `submitUploadUsesFullSha256AsIdentityAndSettlesFile` | `SubtitleMainServiceTest` | `submitUploadUsesFullSha`（**仅改名**，断言逐条保留） |
| 31 | `submitUploadRejectsNullContent` | `SubtitleMainServiceTest` | `submitUploadRejectsNullContent` |
| 32 | `regenerateRejectsWhenNoIntactOriginal` | `SubtitleRecleanServiceTest` | `regenerateRejectsWhenNoIntactOriginal` |
| 33 | `regenerateDispatchesRecleanRunWhenIntactOriginalExists` | `SubtitleRecleanServiceTest` | `regenerateDispatchesRecleanRunWhenIntactOriginalExists` |
| 34 | `translateRejectsWhenNoIntactCleaned` | `SubtitleTranslateServiceTest` | `translateRejectsWhenNoIntactCleaned` |
| 35 | `translateDispatchesTranslateRunWhenIntactCleanedExists` | `SubtitleTranslateServiceTest` | `translateDispatchesTranslateRunWhenIntactCleanedExists` |

**合计 35 条**：`SubtitleMainServiceTest` 23 + `SubtitleRecleanServiceTest` 6 + `SubtitleTranslateServiceTest` 6。

## 计数核对（surefire 原始输出）

```
SubtitleMainServiceTest       tests="23"
SubtitleRecleanServiceTest    tests="6"
SubtitleTranslateServiceTest  tests="6"
TaskControllerTest            tests="22"
```

## 搬迁时的断言调整（只改「被构造的对象」与「mock 的依赖」，不降强度）

> **2026-10-21 订正**：本节初稿把派发用例记成 `verify(concurrencyGate).submit(any(Runnable.class), any(Runnable.class))`。
> 那只是一次**中间状态**，而且是**断言降级**——它只断言「提交过某个 Runnable」，不再断言流进去的是哪条 run、
> 哪个 workspace。检查轮已改成：闸门用 `inlineGate()` 把 body 同步跑掉，断言落到执行体的副作用上
> （调了哪个协调器、`supervisor.finish` 拿到哪条 run）。下表已按现状重写。

| 用例 | 旧断言 | 现断言 | 为什么 |
|---|---|---|---|
| `submitUrlCreatesIdentityAndDispatchesMainRun` | `verify(pipeline).run(eq(run), any(), eq(workspace))` | `service(inlineGate()).submitUrl(...)` 跑掉 body 后：`verify(subtitlePreparationCoordinator).prepare(eq(new MediaInput.RemoteUrl(URL)), ...)` + `verify(supervisor).finish(ref(run), run, null)` + `verify(fingerprintCoordinator).detectContentChange(eq(TASK_ID), eq(workspace), ...)` | 闸门外壳由 `SubtitlePipeline.run` 内联进 `handleSubmit`，`pipeline` 这个 mock 点消失。最后一条是检查轮补的 **workspace 实例级**断言——`Workspace` 未重写 `equals`，只靠 URL 值比对会漏掉「同 URL、不同 sourceKey」的情形 |
| `conflictWhenTaskAlreadyHasRunningRun` | `verify(pipeline, never()).run(...)` | `verify(concurrencyGate, never()).submit(any(), any())` | 语义等价：冲突时 `conflictFor` 在事务内先抛，执行体根本没派发出去；「没派发」本身就是断言面 |
| `regenerateDispatchesRecleanRunWhenIntactOriginalExists` | `verify(pipeline).regenerate(eq(run), any(), any(), any(SubtitleKeywords.class), eq(originalId))` | `service(inlineGate()).regenerate(taskNo, keywords)` 跑掉 body 后：`verify(cleanupStage).regenerate(eq(new MediaInput.RemoteUrl(URL)), ...)` + `verify(supervisor).finish(ref(run), run, null)` | 执行段与守卫段现在同住一个方法，经公开签名驱动；比旧版更严（旧版多个参数是 `any()`） |
| `translateDispatchesTranslateRunWhenIntactCleanedExists` | `verify(pipeline).translate(eq(run), any(), any(), any(SupportedLanguage.class), any(SupportedLanguage.class), eq(cleanedId))` | `service(inlineGate()).translate(taskNo, source, target)` 跑掉 body 后：`verify(translationStage).translate(eq(new MediaInput.RemoteUrl(URL)), ...)` + `verify(supervisor).finish(ref(run), run, null)` | 同上；旧版 source/target 是 `any()`，现版是 `eq(...)` |
| RECLEAN 四条执行段用例 | `pipeline(inlineGate()).regenerate(run, ref, workspace, keywords, originalId)`（绕过同步守卫段的包内入口） | `service(inlineGate()).regenerate(taskNo, keywords)`，守卫段用真 `SubtitleTaskFinder` + mock 仓储摆前置 | 守卫段与执行段现在同住 `SubtitleRecleanService.regenerate`；顺带把守卫段（404 / 400 / 409）也跑到了 |
| TRANSLATE 四条执行段用例 | `pipeline(inlineGate()).translate(run, ref, workspace, source, target, cleanedId)` | `service(inlineGate()).translate(taskNo, source, target)` | 同上 |

其余用例的 `verify(...)` / 断言内容逐条照搬，未改。

**检查轮实测**：三个派发类用例的断言条数各 **+1**（4→5、3→4、2→3），**无一条减少**；其余 32 个方法逐条一致。检查脚本对 35 个方法做过「`verify(...)` / `assertXxx(...)` 成对 diff」。

## 其他

- 两个旧测试文件（`SubtitlePipelineTest`、`SubtitleTaskServiceTest`）已删除。
- `TaskControllerTest` 的 `@MockitoBean` 从 `SubtitleTaskService` 换成三个新用例服务，调用点分别改指 `mainService` / `translateService` / `recleanService`；断言与请求体一行未改。
- `SemaphoreTaskConcurrencyGateTest` 里那处注释（原提 `SubtitlePipeline`）已按现状改写，断言不变。
