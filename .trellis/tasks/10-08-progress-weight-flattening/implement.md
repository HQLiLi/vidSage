# 进度权重扁平化 — 执行步骤

前置：`dev` 干净；`task.json` 的 `base_branch = dev`（已核对）。所有命令在 Git Bash 下、工作目录 `f:/personal_project/vidSage`。

产物证据统一落 `.trellis/tasks/10-08-progress-weight-flattening/evidence/`（**别用 `/tmp`**——Git Bash 的 `/tmp` 与 Windows 程序不通用）。

---

## 步骤 0 — 开分支

```bash
git checkout -b feature/progress-weight-flattening   # 从 dev 起
git branch --show-current                            # 期望 feature/progress-weight-flattening
```

## 步骤 1 — 枚举改造（可独立验证）

改 `backend/src/main/java/com/vidsage/domain/subtitle/model/common/SubtitleStageName.java`，按 `design.md` §一：加 `weight` / `inMainPipeline` 字段、`start()` / `end()`、类加载时按声明顺序累加出区间表、不在表里的阶段调用即抛。

### 示例代码（整文件重写，可直接对着敲）

```java
package com.vidsage.domain.subtitle.model.common;

import lombok.Getter;

import java.util.EnumMap;
import java.util.Map;

/***
 * 从媒体源生成字幕管线的所有阶段
 *
 * <p>注意：枚举声明顺序就是进度区间的累加顺序，别随意调换。
 * 每个主管线阶段只声明一个相对份额，任务级区间在类加载时按顺序算出来。
 */
@Getter
public enum SubtitleStageName {

    ACQUIRE_MEDIA("acquire-media", 42),
    EXTRACT_AUDIO("extract-audio", 42),
    TRANSCRIBE_AUDIO("transcribe-audio", 49),
    WRITE_SUBTITLES("write-subtitles", 7),
    CLEAN_SUBTITLES("clean-subtitles", 60),
    TRANSLATE_SUBTITLES("translate-subtitles", 0, false);

    private final String stageName;

    /** 相对份额：只比大小，不必凑满 100。 */
    private final int weight;

    /** 是否在主管线上分任务级进度；翻译不参与。 */
    private final boolean inMainPipeline;

    /**
     * 主管线各阶段的任务级区间 [起点, 终点)，类加载时按声明顺序累加算出。
     * 翻译不在表里，调 start()/end() 会抛。
     */
    private static final Map<SubtitleStageName, int[]> RANGES = new EnumMap<>(SubtitleStageName.class);

    static {
        int total = 0;
        for (SubtitleStageName stage : values()) {
            if (stage.inMainPipeline) {
                total += stage.weight;
            }
        }
        int done = 0;
        for (SubtitleStageName stage : values()) {
            if (stage.inMainPipeline) {
                int start = done * 100 / total;
                done += stage.weight;
                RANGES.put(stage, new int[]{start, done * 100 / total});
            }
        }
    }

    SubtitleStageName(String stageName, int weight) {
        this(stageName, weight, true);
    }

    SubtitleStageName(String stageName, int weight, boolean inMainPipeline) {
        this.stageName = stageName;
        this.weight = weight;
        this.inMainPipeline = inMainPipeline;
    }

    /** 本阶段在任务级进度里的起点（含），0..100；不在主管线的阶段调用即抛。 */
    public int start() {
        return range()[0];
    }

    /** 本阶段在任务级进度里的终点（不含），0..100；不在主管线的阶段调用即抛。 */
    public int end() {
        return range()[1];
    }

    private int[] range() {
        int[] range = RANGES.get(this);
        if (range == null) {
            throw new IllegalStateException(stageName + " 不在主管线权重表里，没有任务级区间");
        }
        return range;
    }
}
```

### 敲的时候三个要点（容易错的地方）

1. **累计必须用「累计权重 × 100 / 总权重」的口径**，不能写成 `cursor += weight * 100 / total` 的增量口径——增量口径下 write 会算成 69 而不是 70（`7*100/200` 截断成 3），第 4 格就对不上了。示例里 `int start = done * 100 / total;` 先取起点、再累加 `done`、再算终点，顺序不能换。
2. **翻译用三参构造**（`weight=0, inMainPipeline=false`），其余走两参构造默认 `true`——`weight == 0` 不当「不参与」的代理。
3. 6 行复述注释（「抓取媒体源」等）这次顺手删掉（步骤 3 的活，既然重写就一起做了）；类注释补了「声明顺序 = 区间顺序」那句。

写完跑编译确认没碰坏旧调用点（这一步旧常量还在别的类里，编译应该全绿）：

```bash
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean compile
```

新增 `backend/src/test/java/com/vidsage/domain/subtitle/model/common/SubtitleStageNameTest.java`：

- 区间端点：`[0,21) [21,42) [42,66) [66,70) [70,100)`
- 相邻连通、单调不重叠、整体覆盖 `0..100`（首阶段起点 0、末阶段终点 100）
- `TRANSLATE_SUBTITLES.start()` / `.end()` 抛 `IllegalStateException`
- 分母不含翻译（若把翻译的份额算进分母，末段终点不会是 100）

```bash
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean test -Dtest=SubtitleStageNameTest
```

> 卡点：这一步做完枚举可以独立成立（旧调用点仍在用旧常量，编译不受影响）。

## 步骤 2 — 调用点接上

| 文件 | 改法 |
|---|---|
| `application/media/pipeline/AudioPreparationStage.java` | 4 处 emit 改用 `ACQUIRE_MEDIA.start()/end()`、`EXTRACT_AUDIO.start()/end()`；删 4 个常量 |
| `application/subtitle/pipeline/SubtitlePreparationCoordinator.java` | 调 audio 时**去掉 remap**、直接透传 listener；transcribe 的 start/done 用 `TRANSCRIBE_AUDIO.start()/end()`；写字幕**开始发 `.start()`、完成发 `.end()`**（R8）；跳过路径发各自的 `.end()`；删 4 个常量 |
| `application/subtitle/pipeline/SubtitleCleanupCoordinator.java` | 自身事件用 `CLEAN_SUBTITLES.start()/end()`；传给 cleanser 的 listener 改 `ProgressMapper.remap(listener, CLEAN_SUBTITLES.start(), CLEAN_SUBTITLES.end())`；删 `STAGE_BEGIN` / `STAGE_DONE`；补 import |
| `application/task/pipeline/SubtitlePipeline.java` | 两行调用**去掉 remap**、直接透传；删 `PREPARE_END` / `CLEAN_START` / `CLEAN_END` 与 `ProgressMapper` import |

失败 / 跳过事件沿用现状：**跳过发 `.end()`、失败发 `.start()`**（acquire / extract 各一条 FAILED 事件，保留）。

```bash
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean compile
```

## 步骤 3 — 常量清零 + 注释清理

```bash
grep -rn "PREPARE_END\|CLEAN_START\|CLEAN_END\|ACQUIRE_END\|TRANSCRIBE_START\|TRANSCRIBE_DONE\|WRITE_DONE\|ACQUIRE_START\|ACQUIRE_DONE\|EXTRACT_START\|EXTRACT_DONE" backend/src/main   # 期望零命中（AC1）
grep -n "STAGE_BEGIN\|STAGE_DONE" backend/src/main/java/com/vidsage/application/subtitle/pipeline/SubtitleCleanupCoordinator.java   # 期望零命中（AC2）
grep -rn "ProgressMapper" backend/src/main                                                                                          # 期望只剩 cleanup + 自身（AC3/AC4）
```

顺手删枚举里那 6 行复述注释（「抓取媒体源」等）。

## 步骤 4 — 改名（纯改名，可与 1–3 分成两个提交）

```bash
grep -rn "AudioPreparationStage\|MediaAcquisitionStage\|TranscriptionStage" backend/src
```

| 现名 | 新名 |
|---|---|
| `AudioPreparationStage` | `AudioPreparationCoordinator` |
| `MediaAcquisitionStage` | `MediaAcquirerDispatcher` |
| `TranscriptionStage` | `Transcriber` |

含：文件名、类名、Javadoc 里的引用、引用点的字段/参数名、测试类名（`AudioPreparationStageTest` → `AudioPreparationCoordinatorTest`）。

```bash
grep -rn "AudioPreparationStage\|MediaAcquisitionStage\|TranscriptionStage" backend/src   # 期望零命中（AC8）
```

> 改名前先按上面的 grep 把引用点清单拉全——`target/` 下的旧 class 不受 grep 管，所以改名后必须 `mvn clean`（见步骤 6）。

## 步骤 5 — 改写写死数值的测试断言

已知落点（动手时按 `grep -rn "progress" backend/src/test` 全量核一遍）：

| 文件 | 旧断言 | 新期望 |
|---|---|---|
| `AudioPreparationCoordinatorTest` | `List.of(0, 50, 50, 100)` | 新区间端点 `0/21/21/42` |
| 同上 | 跳过 acquire 取 `50`、跳过 extract 取 `100` | `21` / `42` |
| `SubtitlePipelineTest` | `assertEquals(35, …, "prepare 区间 0..70 的中点")` | 按新结构重写（该断言测的是 remap，remap 已从管线移除） |
| `SubtitlePipelineTest` | 翻译事件 `40` 直通 | 不变（翻译仍直通） |
| `SubtitleCleanupCoordinatorTest` | `0` / `100` | `70` / `100` |
| `SubtitleTranslationCoordinatorTest` | `0` / `100` | 不变（翻译仍自报 0..100） |

## 步骤 6 — 全量回归

```bash
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q clean test
grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' target/surefire-reports/*.xml
```

`clean` 必须带（`target/classes/` 与 `surefire-reports/` 都会留陈旧产物，改名后尤其）。逐用例结论看 `<failure` 元素，不看是否自闭合。

## 步骤 7 — 真实链路验证（AC7）

1. **对照实例切到 dev**（`../vidsage-before` 现在停在 `6bda4f5`）：

```bash
git worktree remove ../vidsage-before        # 有文件句柄占用时先杀掉占端口的 JVM
git worktree add ../vidsage-before dff027b   # dev 当前的对照点
```

2. 起两个实例（**不同端口**，避免互相干扰）：本分支 `18080`、对照 `18081`。启动与日志按既有办法（`spring-boot:run` 后台 + 相对路径写日志）。

3. 同一个媒体文件，**走上传路线**（避开 yt-dlp 下 B 站的会员格式提示/超时），两条各提交一次，抓 SSE 事件流存进 `evidence/`。

4. 逐格 diff，期望：

```
对照（dev）：0 / 21 / 21 / 42 / 42 / 66 / 70 / 70 / 70 / 100 / 100
本分支     ：0 / 21 / 21 / 42 / 42 / 66 / 66 / 70 / 70 / 100 / 100
                                    ↑ 写字幕「开始」：70 → 66（有意，R8）
```

差异**必须恰好是这一格**；多一格就是回归。结果写进 `evidence/` 的比对说明里。

5. 收工杀实例（`TaskStop` 杀不掉 `spring-boot:run` 的子 JVM）：

```bash
netstat -ano | grep LISTENING | grep -E ":1808[01]"
taskkill //PID <pid> //F
```

> 翻译链路不做真实运行（省配额）：它不受本任务影响，区间查询路径由单测覆盖。**这是一条已接受的边界**，要写进完成报告。

## 步骤 8 — spec 与文档收口

- `.trellis/spec/backend/directory-structure.md` 命名判据节：清空「存量待改」那行（`MediaAcquisitionStage` / `TranscriptionStage`）；构件档成员表补上 `*Coordinator` / `*Dispatcher` / `*Transcriber` 的落地结果；若「管线构件」判据段还提 `*Stage` 存量，一并改。
- 归档任务 `10-07-shape-refactor/design.md` §五 落点清单第①行「权重区间仍要手算」→ 注明已落地（按既有文档订正惯例：直接重写为现状）。

---

## 评审门与回退点

- **门的纪律**：步骤 0–8 全部做完、测试绿、AC7 比对结果出来后**停下等审查**——不在同一次执行里 commit / push / merge / 归档。
- **建议的提交切分**（审查通过后）：① 枚举 + 调用点 + 常量清理 + 测试断言；② 三个类改名（纯改名，回退时最容易单独摘掉）；③ spec 与文档收口。
- **回退点**：步骤 1 之后（枚举可独立成立）、步骤 3 之后（扁平化已完成、编译与测试绿，改名尚未动）、步骤 6 之后（全量绿）。任一步骤失败都可退到上一个回退点而不丢已完成的验证。
- **最可能的返工点**：步骤 5 的断言改写——`SubtitlePipelineTest` 里那条「prepare 区间中点」的断言测的是被删掉的 remap，需要按新结构重写而不是改数字。
