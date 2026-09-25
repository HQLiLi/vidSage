# 开源实现参考清单（本地源码）

> 用途：vidSage 各 MVP 环节「照哪个项目抄」的索引。
> 结论来自对三份本地源码的逐文件通读（2026-09-25），所有路径均已核实存在。

## 使用方法（先读这段）

**本文件是索引，不是实现说明。** 它只回答「去哪看」，不回答「怎么实现」——条目里的机制描述是导航线索，省略了源码中的边界处理与错误分支。**动手写代码前，必须按条目给出的路径打开源码读原文**，只读本文件的摘要等于二手转述。

**怎么进入上下文**：MVP 任务规划期（Phase 1.3 Configure context）把本文件挂进该任务的 `implement.jsonl` 与 `check.jsonl`，子 agent 读到后按路径自行读取源码。两个 manifest 都必须有 ≥1 条真实条目，否则 `task.py start` 会被拒。命令：

```bash
python .trellis/scripts/task.py add-context <task-dir> implement \
  .trellis/tasks/09-24-vidsage/research/oss-references.md \
  "<环节>实现参考索引；文档含源码绝对路径，按路径自行读取"
```

**源码根路径**：三个项目都在 `F:\learning_projects\asr_references\` 下（`--depth 1` 浅克隆，无 git 历史）。下文条目中的路径均相对**各自的参考项目根**，不是相对本仓库。

**许可证红线**：**VideoCaptioner 是 GPL-3.0，不要复制其源码进 vidSage**（会传染，vidSage 若公开则需整体以 GPL-3.0 开源）。它只提供思路、算法、接口契约、常量参数——读懂后自己重写。VideoLingo（Apache-2.0）与 AI-Media2Doc（MIT）可搬运代码，保留版权与许可声明即可。

## 一、源码位置与许可证

三个项目均克隆在 `F:\learning_projects\asr_references\` 下（`--depth 1` 浅克隆，无 git 历史）。

| 项目 | 磁盘路径 | 许可证 | 抄代码风险 | 技术栈 |
|---|---|---|---|---|
| **VideoLingo** | `F:\learning_projects\asr_references\VideoLingo` | **Apache-2.0** | 低（署名即可） | Python + Streamlit |
| **VideoCaptioner** | `F:\learning_projects\asr_references\VideoCaptioner` | **GPL-3.0** | **高（强传染）** | Python + PyQt / CLI |
| **AI-Media2Doc** | `F:\learning_projects\asr_references\AI-Media2Doc` | **MIT** | 低（署名即可） | FastAPI + Vue3 |

**许可证直接影响抄法，务必区分对待：**

- **VideoLingo（Apache-2.0）/ AI-Media2Doc（MIT）**：可以逐行搬运代码到 vidSage，需保留版权与许可声明。
- **VideoCaptioner（GPL-3.0）**：**不要复制其源码进 vidSage**。GPL-3.0 要求衍生作品同样以 GPL-3.0 开源，vidSage 若公开（简历项目大概率公开）会被传染。**只抄思路、算法、接口契约、prompt 文案**（prompt 文案属事实性资产，但如果整份 Markdown 直接复制，仍有争议）。参考方式是「读懂后用自己的话重写」。

> 三个项目的技术栈都不是 Java/Spring。vidSage 的 Java 后端只能**抄设计与契约**；`asr-service`（Python）可以真正复用 Python 代码。

## 二、总览：哪个环节看哪个项目

| vidSage 环节 | 首选参考 | 次选 |
|---|---|---|
| MVP1 任务建模 / 状态机 / 进度上报 | VideoLingo | VideoCaptioner |
| MVP1 幂等落库 / 去重 | AI-Media2Doc | VideoLingo |
| MVP2 yt-dlp 下载 + **拉平台已有字幕** | **VideoCaptioner**（唯一有） | — |
| MVP2 ffmpeg 抽音频 | VideoCaptioner | VideoLingo |
| MVP2 **ASRProvider 双路抽象** | **VideoCaptioner**（最完整） | VideoLingo |
| MVP2 长音频分块 + 结果合并 | **VideoCaptioner** | VideoLingo |
| MVP2 ASR 输出归一化 / 时间戳补位 | VideoCaptioner | VideoLingo |
| MVP3 「直译→反思→重写」三遍过 | **VideoLingo**（PRD 点名的就是这个） | VideoCaptioner |
| MVP3 上下文组织 + 全局术语表 | **VideoLingo** | VideoCaptioner |
| MVP3 LLM 输出鲁棒性（漏行/坏 JSON） | VideoCaptioner | VideoLingo |
| MVP3 双语 SRT 落地 | VideoCaptioner | AI-Media2Doc |
| MVP4 结构化总结 prompt | AI-Media2Doc | VideoCaptioner |
| MVP4 长字幕分段压缩 | VideoCaptioner | VideoLingo |
| 阶段二 片段 schema（向量化单元） | VideoCaptioner | — |
| 阶段二 检索评测 / MCP | **三个项目都没有，需自研** | — |

## 三、按 MVP 的可抄实现方式

### MVP1 — 任务提交 / 异步管线 / 进度 / 落库

**1. 任务实体建模** —— `VideoCaptioner/videocaptioner/core/entities.py`
`TranscribeTask` / `SubtitleTask` / `SynthesisTask` 等 dataclass：含 `task_id`（`uuid4().hex[:8]`）、`queued_at/started_at/completed_at` 三个时间戳、嵌套 config dataclass、`need_next_task` 布尔串联下一阶段。**直接映射 vidSage 任务表：任务 ID + 三态时间戳 + 阶段化配置 + 「当前阶段/下一步」判断。**

**2. 进度回调契约 + 阶段加权** —— `VideoCaptioner/videocaptioner/ui/thread/subtitle_pipeline_thread.py`
全链路统一 `callback(progress: int, message: str)` 签名；三段各自映射到 0–40 / 40–60 / 70–100 区间；`progress` 强制单调不回退（见 `core/asr/chunked_asr.py` 的 `last_overall` 锁）。**vidSage 的 SSE payload 就照这个结构推：`{progress, stage, message, state}`。**

**3. 任务状态机 + 分步进度模型** —— `VideoLingo/core/st_utils/task_runner.py`
`TaskRunner` 把流水线抽象成 `steps: list[(label, callable)]`，暴露 `state ∈ {idle,running,paused,stopped,completed,error}`、`current_step`、`total_steps`、`current_label`。VideoLingo 自己是 Streamlit 轮询而非 SSE —— **SSE 那层 vidSage 自己写，但 payload 结构可复用。**

**4. 协作式取消钩子** —— `VideoLingo/core/utils/__init__.py` 的 `check_cancel()`
核心逻辑的长循环里调 `check_cancel()`：无 runner 时是 no-op（CLI 可独立跑），有 runner 时阻塞在暂停或抛 `StopTask`。用类级 `_current` 指针解耦核心逻辑与调度层。**vidSage 异步任务要支持取消/暂停，这是最小实现范式。**

**5. 断点续跑 / 幂等** —— `VideoLingo/core/utils/decorator.py` 的 `check_file_exists` + `VideoLingo/core/utils/models.py`
每阶段写中间产物文件，函数用 `@check_file_exists(path)` 装饰 —— 文件已存在则跳过该步；整段完成用 `.subtitle_done` 之类的 marker 标记。**vidSage「重跑不重复 ASR/翻译」的核心机制。**

**6. 重试装饰器** —— `VideoLingo/core/utils/decorator.py` 的 `except_handler(retry, delay, default_return)`
指数退避 `delay * 2**i`，支持最终降级返回值。**调 DeepSeek / DashScope 的标准重试封装，几乎零改造。**

**7. 幂等去重（内容哈希）** —— `AI-Media2Doc/frontend/src/utils/db.js` + `frontend/src/apis/types.ts`
任务表用 **`md5 + contentStyle` 复合唯一索引**做「同一媒体 + 同一总结风格」去重；`md5` 用 `spark-md5` 分块计算；另有 `cleanupOldTasks` 保留最新 N 条。**vidSage 需要「同 URL/同文件同任务不重复提交」，这套 key 设计可平移到关系表。**

**8. 统一 API 信封 + 异常体系** —— `AI-Media2Doc/backend/core/response.py` + `backend/core/exceptions.py`
统一返回 `{success, message, data, error}`；业务异常 400 / 外部服务 502 / 兜底 500。**vidSage 的 Java 侧定义等价的 `ApiResponse<T>` + 全局异常 handler。**

### MVP2 — 下载 / 平台字幕 / ASR 双路 / 抽音频

**9. ASR Provider 抽象基类（最值得抄的一个文件）** —— `VideoCaptioner/videocaptioner/core/asr/base.py`
`BaseASR` 用模板方法：`run()` 固定流程「缓存命中检查 → `_run()` → 写缓存 → `_make_segments()`」，子类只实现 `_run(callback, **kwargs)`（原始响应）与 `_make_segments(resp) -> List[ASRDataSeg]`。缓存键 = `类名:crc32(音频字节)`。5 个已有实现（`whisper_api.py` / `faster_whisper.py` / `bcut.py` / `jianying.py` / `whisper_cpp.py`）证明契约稳定。
**→ 这就是 vidSage `ASRProvider` 的原型：缓存、限流、进度、结果归一全收敛在基类，新增 provider 只写两三个方法。** 另见 `core/asr/whisper_api.py`（OpenAI 兼容协议，最接近 DashScope）、`core/asr/bcut.py`（异步任务轮询，最接近云端长任务）。

**10. 长音频分块装饰器** —— `VideoCaptioner/videocaptioner/core/asr/chunked_asr.py`
`ChunkedASR` 作为装饰器给任意 `BaseASR` 子类加长音频能力：10 分钟一块、10s 重叠、3 并发（**本地引擎并发=1、20 分钟一块**）。工厂 `_create_asr_instance`（`core/asr/transcribe.py`）按枚举返回包装后的 provider。**vidSage「云端防超时用重叠分块、本地单线程防 OOM」的经验参数直接可用。**

**11. 分块结果合并（重叠区去重）** —— `VideoCaptioner/videocaptioner/core/asr/chunk_merger.py`
`ChunkMerger.merge_chunks` + `_find_best_alignment`（滑动窗口）：词级用精确文本匹配、句子级用 `difflib.SequenceMatcher.ratio() > 0.7` 模糊匹配；得分 `matches/i + epsilon` 取最优，在重叠区中点切分；无匹配则退回时间边界。**纯算法无外部依赖，直接可移植。测试见 `tests/test_asr/test_chunk_merger.py`。**

**12. 统一字幕数据模型** —— `VideoCaptioner/videocaptioner/core/asr/asr_data.py`
`ASRDataSeg(text, start_time, end_time, translated_text)` + `ASRData`：**内部时间戳统一毫秒 int**；`from_srt/from_vtt/from_youtube_vtt/from_ass/from_json` 与 `to_srt/to_ass/to_json/to_txt` 双向；`is_word_timestamp()` 启发式判断词级；`split_to_word_segments()` 把句子级均分成词级；`optimize_timing()` 把相邻边界移到 3/4 点消闪烁；`from_srt` 用 `langdetect` 自动识别双语块。**vidSage 跨服务边界的序列化协议就用它的 `to_json`/`from_json` 格式（`{index: {start_time, end_time, original_subtitle, translated_subtitle}}`）。**

**13. ASR 结果后处理与时间戳补位** —— `VideoLingo/core/asr_backend/audio_preprocess.py` 的 `process_transcription()` / `save_results()`
逐 segment 展平到词表，处理三类异常：①无词级时间戳的后端 → 用 segment 文本合成单 word；②词超 30 字符 → 丢弃；③词缺 start/end → 用前词 end 或后词 start 补位；`speaker_id` 透传。**这是 ASR→字幕最脏、最易出 bug 的环节，FunASR 与 DashScope 输出格式不一时的兜底逻辑可直接翻译。**

**14. 拉取平台已有字幕（vidSage MVP2 的关键缺口）** —— `VideoCaptioner/videocaptioner/ui/thread/video_download_thread.py`
`initial_ydl_opts` 含 `writeautomaticsub`；用 `info_dict["language"]` 匹配 `automatic_captions` 里的语言码，语言不匹配时直接 `requests.get` 重下 vtt；含 `cookiefile` 与 `sanitize_filename`（Windows 保留名处理）。
**注意**：VideoLingo 的 `core/_1_ytdlp.py` **没有** `write_subs`/`writeautomaticsub`，它只下载媒体不做字幕拉取；VideoCaptioner 的 CLI 版 `cli/commands/download.py` 也较简陋（无 `writeautomaticsub`）—— **以 GUI thread 版为准。**

**15. ffmpeg 抽音频** —— `VideoCaptioner/videocaptioner/core/utils/video_utils.py`
`video2audio(input, output, audio_track_index)`：`ffmpeg -i in -map 0:a:{idx} -vn -ac 1 -ar 16000`（单声道 16k 是 ASR 标配）；`get_video_info` 用正则从 stderr 解析时长与**多音轨**（B站视频常有多音轨）。
VideoLingo 的 `core/asr_backend/audio_preprocess.py` 补充两点：`-af aresample=async=1:first_pts=0`（**对齐解码样本与源时钟，避免时间戳漂移**）、编码器降级（`libmp3lame` 缺失时退 `pcm_s16le wav`，用 `_ffmpeg_has_encoder` 探测）。

**16. 长音频按静默点切分** —— `VideoLingo/core/asr_backend/audio_preprocess.py` 的 `split_audio()`
目标 30 分钟一段，在 `[target-60s, target+60s]` 窗口内用 `pydub.silence.detect_silence` 找 >= 1s 的静默区，在其起点后 0.5s 处切。**保证不切断句子，正是本地 FunASR 分段需要的策略。**

**17. 云端 ASR 的区间切片解码** —— `VideoLingo/core/asr_backend/audio_preprocess.py` 的 `audio_slice_wav()`
`ffmpeg -ss start -t dur -ac 1 -ar 16000 -c:a pcm_s16le -f wav pipe:1` 输出到 stdout 直接喂云端 API。**DashScope 兜底时「按区间抽单声道 PCM 再上传」的标准做法。**

**18. ASR 内容寻址缓存** —— `VideoLingo/core/asr_backend/transcription_cache.py`（设计文档 `docs/transcription-cache.md`）
缓存 key = `sha256(媒体 MD5 + 依赖包版本 + schema 版本 + runtime/model/language)`，**刻意排除凭证、文件名、翻译设置**；按「整段 complete + 分段 part」两级落盘，**分段命中可跳过单段重跑**；写文件用临时文件 + `os.replace` 原子替换。**vidSage「同视频重复提交不重跑 ASR」「失败续跑不重复已完成的段」就靠这套。**

**19. 本地模型离线优先加载** —— `VideoLingo/core/asr_backend/whisperX_local.py` 的 `resolve_whisper_model()`
依次查「显式路径 → 项目 model_dir → HF 缓存 `local_files_only=True` → 才联网下载」，每个候选校验 `config.json/model.bin/tokenizer.json` 非空。**本地 FunASR/SenseVoice 加载同样该「离线优先 + 完整性校验」。**

### MVP3 — 上下文感知整段翻译 / 术语一致 / 双语 SRT

**20.「直译 → 反思 → 重写」三遍过（PRD 点名的就是这个）** —— `VideoLingo/core/translate_lines.py` + `VideoLingo/core/prompts.py`
两步流水线：**Step1 faithfulness（直译）** —— `get_prompt_faithfulness` 要求逐行忠实直译，输出 JSON `{i: {origin, direct}}`；**Step2 expressiveness（反思 + 意译）** —— `get_prompt_expressiveness` 拿直译结果，要求「两步思考：先评价直译的流畅度/风格/啰嗦处（reflect），再给自然意译（free）」，输出 `{i: {origin, direct, reflect, free}}`。`reflect_translate` 配置开关控制是否做第二步；两步之间 `shared_prompt` 复用。
**每行带 `origin/direct/reflect/free` 的富结构天然支持以后插第三轮（如术语一致性检查）而不改数据流。这是全项目最值得借鉴的资产。**

**21. 上下文三级组织** —— `VideoLingo/core/_4_2_translate.py`
`split_chunks_by_chars(chunk_size=600, max_i=10)` 按「累计字符 <= 600 或已 10 句」切块；翻译每块时注入 `previous_content`（上一块最后 3 行）+ `after_content`（下一块前 2 行）+ `theme`（全局摘要）。**「块内整段 + 前后窗 + 全局主题」三级上下文，vidSage 的「上下文感知整段翻译」直接复用这个粒度。**

**22. 全局术语表 + 按段注入** —— `VideoLingo/core/_4_1_summarize.py` + `custom_terms.xlsx`
①取前 `summary_length` 字符做摘要输入；②读用户术语表 `{src, tgt, note}`；③LLM 输出 `{theme, terms:[...]}`（`valid_summary` 校验）后与用户术语合并；④翻译每块前用 `search_things_to_note_in_prompt(sentence)` 按 `term['src'].lower() in sentence.lower()` 挑出**本段实际出现**的术语注入。
**「术语与称谓全片一致」的落点：全局表 + 按段只注入相关术语，避免污染 prompt。** 另有 `pause_before_translate` 配置预留「术语人工审核节点」。

**23. 翻译器基类（批量 + 并发 + 缓存）** —— `VideoCaptioner/videocaptioner/core/translate/base.py`
把 segments 转成 `SubtitleProcessData(index, original_text)`，按 `batch_num`（默认 10）切片成批，`ThreadPoolExecutor` 并发；结果按 index 回填原 segment；每批单独磁盘缓存（chunk 的 SHA256 作键）；失败率 ≥50% 抛错、<50% 告警降级。**「一次调用带一批字幕」让 LLM 看到上下文，比逐条翻译的术语一致性更好。**

**24. LLM 输出鲁棒性（漏行 / 坏 JSON）** —— `VideoCaptioner/videocaptioner/core/translate/llm_translator.py` + `VideoLingo/core/translate_lines.py`
VideoCaptioner：`json_repair.loads` 容错解析 + **校验键集合完全一致**（缺键/多键都反馈重试，`MAX_STEPS=3`）；`is_reflect` 模式下要求返回 `{initial_translation, reflection, native_translation}`。
VideoLingo：`valid_translate_result` 校验键 `1..N` 齐全；每步最多 3 次重试（失败时在 prompt 末尾追加空格改变缓存键强制重试）；并发后 `results.sort(key=i)` 恢复顺序，再用 `SequenceMatcher` 相似度把译文块匹配回原文块，**< 0.9 直接报错**。
**LLM 翻译最容易丢行/串行/JSON 不全，这两套校验是生产级保障，vidSage 调 DeepSeek 必须具备。**

**25. LLM 调用层三件套** —— `VideoCaptioner/videocaptioner/core/llm/client.py` + `VideoLingo/core/utils/ask_gpt.py`
共同点：OpenAI SDK + `base_url` 自动补 `/v1` + 兼容任意 OpenAI 格式端点；`tenacity` 对 `RateLimitError` 指数退避重试；`diskcache` / 磁盘 JSON 按 prompt 做响应缓存（异常不缓存）；`json_repair` 修复残缺 JSON；`valid_def` 回调做结构化校验。VideoLingo 另用 `llm_support_json` 开关**不强依赖模型原生 JSON 模式**。
**vidSage 的 DeepSeek 客户端照这套实现。**

**26. 双语 SRT 落地** —— `VideoCaptioner/videocaptioner/core/asr/asr_data.py` + `core/entities.py`
`SubtitleLayoutEnum`（`ORIGINAL_ON_TOP` / `TRANSLATE_ON_TOP` / `ONLY_ORIGINAL` / `ONLY_TRANSLATE`）驱动 `to_srt(layout=...)`：双语时一个 cue 内两行（原文\n译文）；ASS 双语用 Default/Secondary 两个 Style 两个 Dialogue 事件。
VideoLingo 的 `core/_6_gen_sub.py` 提供四种输出配置命名（`src.srt` / `trans.srt` / `src_trans.srt` / `trans_src.srt`）+ `convert_to_srt_format`（秒 → `HH:MM:SS,mmm`）与 SRT 块拼接。AI-Media2Doc 的 `frontend/src/components/VideoToMarkdown/TranscriptionPanel.vue` 有 `msToSrtTime` 毫秒版实现。

**27. 字幕行长度控制与译文对齐** —— `VideoLingo/core/_5_split_sub.py` + `core/prompts.py`
`calc_len()` 按字符加权宽度（中日 1.75、韩 1.5、全角符号 1.75、其它 1）判断「原文 > max_length 或译文加权 > max_length/target_multiplier」需拆分；超长行先让 LLM 按 `[br]` 切原文，再用 `get_align_prompt` 让 LLM 按原文切分结构对齐译文；最多 3 轮直到合规。**中日韩全角字符宽度差异导致的排版问题，这个加权函数直接可用。**

**28. 词级时间戳 → 句级字幕时间轴** —— `VideoLingo/core/_6_gen_sub.py` 的 `get_sentence_timestamps()` / `align_timestamp()`
把词级时间戳拼成完整串建 `position_to_word_idx` 映射；每句「去标点 + 小写 + 去空格」后做**滑动窗口精确匹配**取起止词索引；再消除相邻句 < 1s 的空隙（上句 end 顶到下一句 start）。**FunASR/DashScope 词级输出到双语 SRT 的必经步骤。**

### MVP4 — 结构化总结 / 长字幕分段压缩

**29. 结构化总结 prompt（首选）** —— `AI-Media2Doc/frontend/src/constants.js` 的 `DEFAULT_PROMPTS.note`
完整的分层总结规范：标题三级 `#/##/###` 不跳级、章节 `##` 2–6 个、每章 ≥ 2 小节、标题 ≤ 28 字、**每个标题后跟时间标记 `[mm:ss-mm:ss]`**、关键处插 `#image[秒数]` 截图标记、只返回 Markdown、`{content}` 占位符注入字幕。
**「标题带时间戳」这个约定天然支持阶段二的「来源片段定位」——总结产物即可点击跳转。**

**30. Prompt 管理机制** —— `AI-Media2Doc/frontend/src/apis/markdownService.ts` + `frontend/src/components/Settings/SettingsStyle.vue`
默认 prompt 常量 + localStorage 用户自定义覆盖（`customPrompts` JSON）+ `replace(/\{content\}/g, text)` 渲染 + 追加「用户备注不得破坏时间/截图标记格式」的约束 prompt + 设置页可逐风格编辑并「刷新默认配置」。
**平移为「服务端默认模板 + 用户可覆盖 + 系统提示词锁格式」。**
VideoCaptioner 的做法可作补充：prompt 存为 Markdown 文件（`core/prompts/`），`get_prompt(path, **kwargs)` 用 `string.Template.safe_substitute` + `lru_cache` 渲染。

**31. 结构化提取 JSON 模式** —— `VideoCaptioner/videocaptioner/core/prompts/analysis/video.md`
输出 `{summary, terms: {entities, keywords}}`：提取实体（人名/组织/产品）+ 关键词（术语），并纠正同音字识别错误，术语保持原文。**vidSage 的「要点 + 章节 + 关键结论」没有现成的章节切分器，但可在此基础上扩展章节字段；提取的术语表能回喂给第 22 条的全局术语表，形成闭环。**

**32. 长字幕分段策略** —— `VideoCaptioner/videocaptioner/core/split/split.py`
`SEGMENT_WORD_THRESHOLD=500`、`SPLIT_SEARCH_RANGE=30`：按总字数 `// 500` 算分段数，在每段目标字数附近 ±30 个 segment 内**找最大时间间隔**作为切点（保证语义段不被拦腰截断），各段并发送 LLM。**vidSage「长字幕超上下文窗口时压缩」直接用「按字数算段数 + 就近最大时间 gap 切点」。**
VideoLingo 补充一个压缩 prompt：`core/_8_1_audio_task.py` 的 `check_len_then_trim()` + `core/prompts.py` 的 `get_subtitle_trim_prompt()`（估算时长超限 → LLM 精简，保留语义删冗余/填充词；LLM 拒绝则降级正则删标点）。

**33. 字幕断句（招牌功能，vidSage 若要自动断句则必看）** —— `VideoCaptioner/videocaptioner/core/split/split.py` + `split_by_llm.py`
LLM 语义断句为主路径、规则降级兜底、相似度校验、词级时间戳回填四层：常量如 `MAX_WORD_COUNT_CJK=25` / `MAX_WORD_COUNT_ENGLISH=18` / `MAX_GAP=1500`；`_split_by_common_words` 含英文连词/中文语气词的前后缀切分词表；`_group_by_time_gaps` 用「窗口 5 × 3 倍均值」检测大间隔；`_validate_split_result` 要求 96% 内容相似度 + 长度校验。
VideoLingo 的 `core/spacy_utils/` 是另一条路线（spaCy 标点/连词/长句 DP），可作为不依赖 LLM 的替代方案。

### 阶段二 — 向量化 / 问答 / MCP / 评测

**三个项目都没有实现这些。** 需要自研，但有两个可直接借用的前置设计：

**34. 片段 schema（向量化入库的最小单元）** —— `VideoCaptioner/videocaptioner/core/asr/asr_data.py`
每个 `ASRDataSeg` 自带 `start_time/end_time`（毫秒）与 `original_subtitle/translated_subtitle`，`to_json` 输出 `{index: {start_time, end_time, original_subtitle, translated_subtitle}}`。**断句后「一行一 cue」的 segment 粒度正好是理想的检索单元，`start_time/end_time` 就是答案要返回的来源时间轴。**

**35. 检索元数据增强** —— `VideoCaptioner/videocaptioner/core/prompts/analysis/video.md`
第 31 条提取的 entities/keywords 可作为片段元数据与向量一起入库，做混合检索，服务于 PRD 里的「检索命中率」评测。

**反面基线（明确别学）** —— `AI-Media2Doc/frontend/src/components/VideoToMarkdown/ChatPanel.vue`
它的问答是「**整段转录文本全量塞进 prompt**」（`initSystemPrompt`），无检索、无来源时间戳、每轮带最近 10 条历史。vidSage 阶段二要做的正是**替换**它。其前端聊天 UI 结构可复用。

## 四、最高优先级的抄取项（Top 5）

1. **`BaseASR` + `ChunkedASR` + `ChunkMerger`**（第 9/10/11 条）—— vidSage `ASRProvider` 双路抽象 + 长音频分块的完整答案，VideoCaptioner 三个文件。
2. **「直译 → 反思 → 重写」三遍过**（第 20 条）+ **三级上下文 + 全局术语表**（第 21/22 条）—— PRD 第 3 条明确要借鉴的，VideoLingo `core/translate_lines.py` + `core/_4_1_summarize.py`。
3. **`ASRData` 统一字幕模型**（第 12 条）—— 跨服务序列化协议 + 双语落地 + 时间轴对齐一站式，VideoCaptioner 一个文件。
4. **任务建模 + 进度契约**（第 1/2 条）—— SSE payload 结构与任务表字段的直接来源，VideoCaptioner `entities.py` + `subtitle_pipeline_thread.py`。
5. **结构化总结 prompt**（第 29 条）—— MVP4 最快落地的一份资产，AI-Media2Doc `constants.js`，「标题带时间戳」还能顺带预埋阶段二。

## 五、不要抄的部分

| 项目 | 不要抄 |
|---|---|
| VideoLingo | 全部 TTS/配音（`core/tts_backend/*`、`core/_8_2`～`_12_*`）、Streamlit UI（`st.py`、`core/st_utils/*`）、烧字幕进视频（`core/_7_sub_into_vid.py`）、人声分离（`core/asr_backend/demucs_vl.py`）、安装脚本与文档站 |
| VideoCaptioner | PyQt5 GUI（`videocaptioner/ui/`）、ASS 渲染与字幕样式（`core/subtitle/`）、TTS/配音（`core/tts/`、`core/dubbing/`、`core/speech/`）、硬字幕烧录（`video_utils.py` 的 `add_subtitles*`）、打包脚本与 `resource/` |
| AI-Media2Doc | 后端无状态薄代理架构（无 DB/ORM/队列）、浏览器 ffmpeg.wasm、火山系硬编码实现（只借契约形态）、智能截图、小红书/公众号风格 prompt、前端 eventBus |

## 六、三个项目都没覆盖的缺口（vidSage 必须自研）

1. **服务端异步任务管线 + 落库**：AI-Media2Doc 的编排在前端串行、VideoLingo 是单进程 + 文件产物、VideoCaptioner 是 QThread —— **没有一个有「DB 任务表 + 服务端状态机 + SSE」**。
2. **拉取平台已有字幕**：只有 VideoCaptioner 有个 GUI 版实现可参考，且需要自己补 `writesubtitles`/`subtitleslangs` 的完整参数。
3. **长文本分段压缩（map-reduce 式总结）**：AI-Media2Doc 完全没做（整段塞 prompt）；只有 VideoCaptioner 的分块切点策略和 VideoLingo 的 trim prompt 可拼。
4. **向量化入库 / 检索问答 / MCP Server / 检索命中率与答案质量评测**：三个项目零覆盖，全部自研。
5. **Java/Spring 侧的对应实现**：三个项目均为 Python/TS，Java 后端只能抄设计与契约。
