# Research: Java 字幕库（写出与解析）可用性与许可证

- **Query**: Java 生态里有没有能用的字幕库——尤其是**写** SRT、以及将来**解析** vtt / json3 时
- **Scope**: external（Maven Central 直连查证 + 网络检索）
- **Date**: 2026-09-26
- **状态**：**部分完成**。写方向的结论已核实（MVP-2b 决策 7 用它）；**读方向（vtt / json3 解析）未调研完**，见文末「未完成的调研」。

> 调查缘起：MVP-2b 原计划要「vtt / json3 → SRT 归一」，需要知道有没有现成库。后平台字幕路线整体移出该任务，调研中途停止。**本次复核只查了「写」这一半**，因为 MVP-2b 仍要产出 SRT。

---

## 一、写方向：不引库（已核实）

结论：**自己写，约 15 行**。理由不是「没有库」，而是「引库不划算」。

| 候选 | Maven 坐标 | 许可证 | 状态 | 结论 |
|---|---|---|---|---|
| `fr.noop:subtitle` | `fr.noop:subtitle:0.9.0` | **LGPL-3.0** | Central 上**只有 0.9.0 一个版本**；编译目标 **Java 1.7**（`maven-compiler-plugin 3.1`）；`inceptionYear 2015`；定位是 CLI 转换工具（`mainClass fr.noop.subtitle.Convert`） | **排除**：撞项目「只引 Apache-2.0 / MIT / BSD」的许可证红线；且是 2015 年的 Java 7 产物 |
| `fredyw/jsrt` | — | **MIT** | Gradle 项目；以**解析与编辑**为主；**未在 Maven Central 上找到** | 不可用（不在 Central 上，只能搬源码，比自己写更费） |

**`fr.noop:subtitle` 的传递依赖**（摘自其 pom）：`fr.noop:charset:1.0.1`、`org.apache.commons:commons-lang3:3.4`、`commons-cli:commons-cli:1.3.1`——三个 2016 年前后的老包。

**关键判据**：库省下的只有格式化那 15 行；而我们的输入类型是 `TranscriptionSegment`，库认的是它自己的模型（如 `SrtObject`），**映射代码无论如何都要自己写**。所以「引库」的净成本是：一个新依赖 + 3 个传递依赖 + 一次 LGPL 例外审批 + 一个 Java 7 编译产物，换 15 行代码。

### 写 SRT 的实际工作量（参考实现）

```java
static String toSrt(List<TranscriptionSegment> segments) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < segments.size(); i++) {
        TranscriptionSegment s = segments.get(i);
        sb.append(i + 1).append('\n')
          .append(formatTimestamp(s.beginTimeMs())).append(" --> ")
          .append(formatTimestamp(s.endTimeMs())).append('\n')
          .append(s.text()).append("\n\n");
    }
    return sb.toString();
}

static String formatTimestamp(long ms) {
    long t = Math.max(ms, 0);
    return String.format("%02d:%02d:%02d,%03d", t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000);
}
```

---

## 二、读方向（vtt / json3 解析）：**未完成**

将来接平台字幕时必须补做。已知的输入形态与难点（来自 `platform-subtitle-formats.md` 的实测）：

| 格式 | 来源 | 难点 |
|---|---|---|
| `vtt`（人工） | YouTube 人工字幕 | 结构简单，接近 SRT |
| `vtt`（自动） | YouTube 自动字幕 | **体积是人工的 3.5 倍**，含**滚动重复行**、**零时长 cue**、`align:start position:0%` 注释、内联时间标签——`VideoCaptioner` 专门有个 `from_youtube_vtt` 就是为它 |
| `json3` | YouTube 原生 | `events[].tStartMs / dDurationMs / segs[].utf8`，**毫秒、好解析**；但 ffmpeg 不认，yt-dlp 的 `--convert-subs` 转不了 |
| `json` | **B站登录后**（未验） | 格式未知，需先实测 |
| `srt` | YouTube 原生 | 无需转换，`--sub-format srt` 直取 |

**调研时必须带上的两个约束**：

1. **查许可证**，且要查 pom / LICENSE 原文而不是凭库名印象。本项目只接受 Apache-2.0 / MIT / BSD；GPL-3.0 / AGPL 一律不进 `pom.xml`，LGPL 需单独审批
2. 评估「库能不能处理**自动字幕的滚动重复行**」——这是本环节真正的难点，不是格式解析本身。多数通用库只管格式，去重逻辑通常还得自己写（它属语义层，不属格式层）

> **`VideoCaptioner` 是 GPL-3.0**：它的 `asr_data.py`（`from_vtt` / `from_youtube_vtt` / `from_json`）是这一环节最好的思路参考，但**一行源码都不得复制**，只能读懂后用自己的话重写。见 `../../../09-24-vidsage/research/oss-references.md` 开篇「许可证红线」。

---

## 三、本次使用的查证方法（可复用）

`search.maven.org` 的 solrsearch API 在本机网络下**不可达**（curl 返回 `http=000`），但 **`repo1.maven.org` 可达**。可行路径：

```bash
# 列某 groupId/artifactId 的版本目录（可用它确认某个库到底在不在 Central 上）
curl -s "https://repo1.maven.org/maven2/fr/noop/subtitle/"

# 取最新版本号
curl -s "https://repo1.maven.org/maven2/fr/noop/subtitle/maven-metadata.xml"

# 取 pom —— 许可证与传递依赖都在这里，这是许可证核查的权威来源
curl -s "https://repo1.maven.org/maven2/fr/noop/subtitle/0.9.0/subtitle-0.9.0.pom"
```

`WebFetch` 对 `github.com` 与 `search.maven.org` 均被本机网络策略拦下（「Unable to verify if domain is safe to fetch」），但 `curl` 直连 `repo1.maven.org` 正常。
