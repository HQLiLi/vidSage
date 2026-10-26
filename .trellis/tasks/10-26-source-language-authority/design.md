# 设计：原始语种以字幕产物为准

## 1. 数据来源与裁定

**2026-10-26 取证订正**：云端 ASR 模型 `qwen-audio-3.1-asr-flash-filetrans` 的结果 JSON 里没有
`language` 字段（原始 JSON 全文 0 次命中；`transcripts[]`、`properties` 都没有；加
`language_hints: ["auto"]` 也不返回）。库里 4 个任务全是 `null`。原稿「ASR 实测值为唯一依据」
不成立，改为下面的双层来源：

| 来源 | 值 | 可信度 | 本次角色 |
|------|-----|--------|----------|
| `SubtitleTrack.language`（CLEANED segments） | 新清洗由第 2.5 节补齐；ASR 一旦真返回语种会顶到前面 | 高 | **前端锁定与后端校验的唯一读取字段** |
| `SubtitleContext.sourceLanguage` | LLM 通读字幕文本后写的短码，实测 `zh`；偶尔会写「中文」 | 中 | ①清洗时兜底写进 `language`；②前端读旧产物（`language` 仍为 null）时兜底 |

归一规则复用两端现成实现，不新造：

- 前端 `normalizeLanguageCode(raw, options)`（[language.ts](../../../frontend/src/utils/language.ts)）——认 code / label / 中文同义词 / 前缀。
- 后端 `SupportedLanguage.getFirstMatchLang(raw)`——认 `matchKeys` 里的别名（`zh`、`zh-CN`、`zh-SG` 等）。
- 比对用 `SupportedLanguage.isSameLanguage`（按 code 相等）：`zh-Hans` 与 `zh-Hans` 相同。

## 2. 前端改动

### 2.1 store（`frontend/src/stores/workspace.ts`）

```ts
const manualSourceLanguage = ref('')            // 只在识别不出语种时用

const detectedSourceLanguage = computed<string | null>(() => {
  if (languages.value.length === 0) return null
  return normalizeLanguageCode(subtitles.cleanedTrack?.language ?? '', languages.value)
      ?? normalizeLanguageCode(subtitles.context?.sourceLanguage ?? '', languages.value)
})

const sourceLanguageLocked = computed(() => detectedSourceLanguage.value !== null)
const selectedSourceLanguage = computed(() => detectedSourceLanguage.value ?? manualSourceLanguage.value)
```

- `selectedSourceLanguage` 由 `ref` 变只读 `computed`，对外形状不变（仍是 `string`），
  组件侧 `storeToRefs` 照常可用。
- `selectSourceLanguage(code)` 改成：锁定中直接返回，否则写 `manualSourceLanguage`。
- `swapLanguages()` 整个删除。
- `applyLanguages()` 不再回填源语种（改由 computed 推导），只保留目标语种默认值逻辑。
- `reset()` 清 `manualSourceLanguage`。
- `translate()` 的取值处不变，仍读 `selectedSourceLanguage`。

优先级反转的收益：旧的顺序让 LLM 的猜测压过识别器的实测值，正好是本次缺陷的成因。

### 2.2 组件（`TranslationBar.vue`）

- 新增 prop `sourceLocked?: boolean`（默认 `false`）。
- 源语种：`sourceLocked` 时渲染静态文本（同高、去边框、`title` 说明「由字幕识别得到，不可修改」），
  否则维持原下拉。
- 中间互换按钮换成 `<span>to</span>`，删除 `swap` 事件与图标 SVG。
- 布局列宽不改：中列 `2.25rem` 放得下 `to`。

### 2.3 页面（`TranscriptPane.vue`）

- 传 `:source-locked="sourceLanguageLocked"`。
- 删 `onSwapLanguages` 与 `@swap` 绑定，`swapLanguages` 从解构里去掉。

### 2.4 边界

识别不出语种（`null` / 白名单外）→ `sourceLanguageLocked=false`，回到今天的可编辑下拉。
此时请求值仍可能错，但后端同样比对不了（见 3.3），两端口径一致，不会出现「前端锁死、后端拒绝」。

## 3. 后端改动

### 3.0 数据补齐（`SubtitleCleanupCoordinator`）

清洗本来就在摘要之后、写 `-clean` 之前拿到两个值，只差一句合并：

```java
SubtitleTrack cleaned = new SubtitleTrack(
        resolveLanguage(original.language(), subtitleContext), result.segments(), fingerprint);
```

`resolveLanguage` 的判定：

| 顺序 | 条件 | 结果 |
|------|------|------|
| 1 | `original.language()` 非空白 | 原样用（ASR 真给了） |
| 2 | `context.sourceLanguage()` 能归一到白名单 | 用归一后的短码（`zh` → `zh-Hans`） |
| 3 | 其余（含「中文」这种自由文本、摘要降级成空串） | `null`，表示没认出来 |

**为什么归一而不到原样写入**：`language` 声明的是 BCP-47 短码，写「中文」进去后
`SupportedLanguage.getFirstMatchLang` 解析不动，后端校验就只能跳过——等于把问题从「没有值」
换成「有值但没人认」。归一不了就承认没有，语义诚实。

**为什么不改 ORIGINAL 产物**：原始产物在上下文产出之前就写盘了，要回填得重写已落定的产物文件；
而下游（前端 `cleanedTrack.language`、后端 `SubtitleTrackReader.readCleanTrack`）读的**都是
CLEANED**，改 CLEANED 一处就够。

**旧产物怎么办**：不回填。盘上已有的 `clean-segments.json` 仍是 `null`，前端靠
`detectedSourceLanguage` 的 context 兜底继续锁，后端走「语种为空 → 放行」。重新生成字幕后才带语种。

### 3.1 位置

`SubtitleTranslateService.translate()` 的**同步守卫段**：`requireIntactArtifact` 之后、
`supervisor.start` 之前。失败即 `IllegalArgumentException` → HTTP 边界映射 400，
**不建 run 占位**，不占并发名额。

`workDir` 提到 `translate()` 里算一次，同时喂给 `requireIntactArtifact` 和新增的校验，
避免同一路径查两遍。

### 3.2 判定

```
track = SubtitleTrackReader.readCleanTrack(taskId, workDir)   // 现成组件，读不出来返回 empty
if track 不存在            -> 放行（前面的守卫已确认产物完好，这里只是防守）
actual = track.language()
if actual 为空白           -> 放行（旧产物没补过语种，比不了）
detected = SupportedLanguage.getFirstMatchLang(actual)
if detected 为空（白名单外） -> 放行（没法归一到请求模型里的枚举）
if !detected.isSameLanguage(requested) -> 400
```

错误文案（中文，说清怎么修）：

```
源语种与字幕实际语种不一致：请求 en，字幕实际是 zh-Hans（简体中文）；
请把 sourceLanguage 传成清洗产物的 segments.language
```

### 3.3 为什么不做「自动纠正成实际语种」

自动纠正会让调用方以为自己传对了，问题被藏起来；前端已经锁定，正常路径不会触发 400，
这条校验就是给旧客户端 / 手写调用用的护栏。代价（ASR 识别错时用户没有自救途径）已在 PRD
Out of Scope 里写明。

### 3.4 契约影响

- 请求体字段不变，`POST /api/tasks/{taskNo}/translate` 多一条 400 分支。
- `TranslationRequest.sourceLanguage` 的 `@Schema` 与 `TaskController.translate` 的
  `@Operation` 补一句「必须与字幕实际语种一致」。

## 4. 测试

**后端**（`SubtitleTranslateServiceTest` + `SubtitleCleanupCoordinatorTest`）

| 用例 | 期望 |
|------|------|
| 请求 `en`，产物 `zh-Hans` | 抛 `IllegalArgumentException`，`supervisor.start` 从未调用 |
| 请求 `zh`，产物 `zh-Hans` | 放行（别名归一） |
| 请求 `en`，产物 `language=null` | 放行 |
| 请求 `en`，产物 `ko`（白名单外） | 放行 |
| 请求 `en`，产物读不出来（empty） | 放行，既有用例不受影响 |
| 清洗：原始 `null` + 上下文 `zh` | `clean-segments.json` 的 `language` 为 `zh-Hans` |
| 清洗：原始 `ja` + 上下文 `zh` | 保持 `ja` |
| 清洗：原始 `null` + 上下文「中文」 | 保持 `null` |

**前端**：无测试框架（`package.json` 只有 lint / type-check / build），按
`npm run lint` + `npm run type-check` 把关，行为验证走手动 + AC 清单。

## 5. 回滚

改动集中在 2 个前端文件 + 3 个后端文件（含测试），无数据迁移、无产物形状变更。
回滚即 revert 该分支的提交，磁盘上的产物与数据库无需处理。
