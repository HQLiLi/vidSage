# 技术设计：媒体获取装配改为 @Bean 显式产出 List

> 需求与已定决策见 [`prd.md`](./prd.md)。本文只写技术设计、取舍与迁移。

## 一、目标结构

```
com.vidsage
├── config/
│   ├── WorkspaceProperties.java              # 已有：@ConfigurationProperties(vidsage.workspace)
│   └── MediaAcquisitionConfiguration.java    # 新增：唯一的 @Bean —— 直接产出 List
└── provider/download/
    ├── MediaAcquirer.java                    # 接口，不变
    ├── ytdlp/YtDlpMediaAcquirer.java         # 摘 @Component（:43）+ import（:17）；不再是 bean
    └── local/LocalFileMediaAcquirer.java     # 摘 @Component（:31）+ import（:18）；不再是 bean

com.vidsage.service.MediaAcquisitionService   # 零改动（保留 @Service）
```

装配关系（改后）：

```
MediaAcquisitionConfiguration
  └─ @Bean List<MediaAcquirer> mediaAcquirers(YtDlpRunner, ObjectMapper,
                                              ExternalProcessRunner, ExternalToolDiagnostics,
                                              ExternalToolProperties)
         └─ List.of(new YtDlpMediaAcquirer(...), new LocalFileMediaAcquirer(...))
                                            ↑ 两个实现是普通对象，不进容器

容器里 MediaAcquirer 类型的 bean：0 个
        ↓ 于是 MediaAcquisitionService 的构造参数 List<MediaAcquirer>
          走「集合候选为空 → 落回单 bean 解析」这条支路，拿到上面那个 List
```

## 二、三条判据

### 判据 1：为什么 `@Bean List<MediaAcquirer>` 能生效——而它生效的前提是实现不进容器

实测（prd Background-2）：容器解析 `List<MediaAcquirer>` 依赖时先按**元素类型**找候选；候选为空才落回单 bean 解析、按依赖类型 `List<MediaAcquirer>` 匹配到本 bean。候选非空时本 bean 被完全无视。

→ 因此本方案**必须**让容器里 `MediaAcquirer` 类型的 bean 数为 0：不标 `@Component`、也不给实现各自声明 `@Bean`。

**这是本设计最脆弱也最要紧的一环**，写在这里以免后人误改：谁把某个实现注册成 bean，本 `@Bean List` 就静默失效（服务改从容器收集）。由 `prd.md` R4 断言第 2 条（容器中不得存在 `MediaAcquirer` bean）报警。

### 判据 2：为什么两个实现必须摘 `@Component`

同上——不是"顺手清理"，而是**本方案生效的前提**。留着一个 `@Component`，清单就不再由配置类决定：

| 留着的 `@Component` 个数 | 服务拿到的清单 |
|---|---|
| 0（本方案） | `List.of` 的两项 ✅ |
| 1 | 只有那一个游离实现 ❌（两条正常路线全废） |
| 2 | 容器收集的两个实现（内容恰好相同，但**清单已不由你写入的字面量决定**）❌ 语义已偏 |

### 判据 3：依赖走 `@Bean` 方法参数，而不是配置类构造函数注入

`@Bean` 方法的 5 个参数由容器解析注入。相比「配置类持有字段 + 构造器注入」：

- 配置类保持**无状态**，只是一段装配说明
- 避开「配置类早实例化」与配置属性绑定的时序纠缠

后者不展开论证——本项目里它只是**择优**，不是硬规则。

## 三、改动清单（精确到 file:line）

### 新增 `config/MediaAcquisitionConfiguration.java`

```java
package com.vidsage.config;

import com.vidsage.provider.download.MediaAcquirer;
import com.vidsage.provider.download.local.LocalFileMediaAcquirer;
import com.vidsage.provider.download.ytdlp.YtDlpMediaAcquirer;
import com.vidsage.provider.download.ytdlp.YtDlpRunner;
import com.vidsage.provider.tool.ExternalProcessRunner;
import com.vidsage.provider.tool.ExternalToolDiagnostics;
import com.vidsage.provider.tool.ExternalToolProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 媒体获取的装配点：清单在这里写死，两条输入路线各一个实现。
 *
 * <p>两个实现都是普通对象，<b>不进容器</b>（不标 @Component、也不各自声明 @Bean）。这是刻意的：
 * 容器解析 List&lt;MediaAcquirer&gt; 时先按元素类型找候选，只要有实现进了容器，这里返回的清单
 * 就会被无视、改由容器去凑。
 *
 * <p>两个实现都要在（两条路线在同一次运行里都得能用），选谁由 MediaAcquisitionService 按
 * supports(input) 分派，所以不是「配置二选一」形态。
 */
@Configuration
public class MediaAcquisitionConfiguration {

    /**
     * 媒体获取清单：远程 URL 走 yt-dlp，本地路径直接采用。
     *
     * @param ytDlp        下载执行器（远程路线用）
     * @param objectMapper 解析 info json（Boot 4 的 Jackson 3 在 {@code tools.jackson.*}）
     * @param runner       外部进程执行器（本地路线用它调 ffprobe）
     * @param tools        工具路径解析
     * @param properties   外部工具配置（取默认超时）
     * @return 两个适配器，顺序即此处的书写顺序
     */
    @Bean
    public List<MediaAcquirer> mediaAcquirers(YtDlpRunner ytDlp, ObjectMapper objectMapper,
                                             ExternalProcessRunner runner, ExternalToolDiagnostics tools,
                                             ExternalToolProperties properties) {
        return List.of(new YtDlpMediaAcquirer(ytDlp, objectMapper),
                new LocalFileMediaAcquirer(runner, tools, properties));
    }
}
```

（Javadoc 按 `quality-guidelines.md:92`；文风按业主偏好——首行大白话，避开「契约 / 结构性 / 落点」这类词。）

### 修改两个实现（各两处 + 注释改写）

| 文件 | 行 | 动作 |
|---|---|---|
| `YtDlpMediaAcquirer.java` | `:17` | 删 `import org.springframework.stereotype.Component;` |
| 同上 | `:43` | 删 `@Component`（保留 `:42` `@Slf4j`） |
| 同上 | `:39-40` | **改写**注释（见下） |
| `LocalFileMediaAcquirer.java` | `:18` | 删 import |
| 同上 | `:31` | 删 `@Component`（保留 `:30` `@Slf4j`） |
| 同上 | `:28` | **改写**注释 |

注释改写方向（两处同构，**注意原句在本方案下已变成假的**）：

```
现状：<p>两个下载器同时驻留容器，故<b>不加</b> {@code @ConditionalOnProperty}（见 design §三 决策 2）。

改为：<p>本类<b>不是 Spring bean</b>：两个实现都由
     {@code config/MediaAcquisitionConfiguration#mediaAcquirers} 在方法体里 new 出来并放进清单。
     因此<b>不要</b>给它加 {@code @Component} 或 {@code @ConditionalOnProperty}——一旦它进了容器，
     那个 {@code @Bean List} 返回的清单就会被容器按类型收集的结果顶掉。
```

### `MediaAcquisitionService.java`

**零改动**（含保留 `@Service`）。其构造器参数 `List<MediaAcquirer>` 由 R1 的 `@Bean` 满足。

## 四、兼容性与迁移

- **无外部 API 变化**：URL、请求/响应 JSON、状态码、错误消息全部不变
- **分派语义不变**：`supports` 自荐 + `findFirst()` 一行未动
- **无数据库、无持久化、无迁移脚本**

### 迁移是平滑的（两态行为等价）

| 顺序 | 中间态 | 后果 |
|---|---|---|
| 先加 `@Bean List`，后摘 `@Component` | 容器里 2 个实现 bean + 1 个 List bean；服务走**集合分支**拿到那两个 bean | **无副作用**：清单内容相同；此时你的 List bean 还是死代码 |
| 先摘 `@Component`，后加 `@Bean List` | 容器里 0 个实现 bean → 集合分支返回 `null` → 落回单 bean → **没有** `List<MediaAcquirer>` bean 可注入 → **启动失败** | 会红 |

→ **先加后摘**是安全顺序；反序会启动失败。implement.md 按「先加后摘」排序，但两步合并为一批（中间态虽无害，语义未达目标不该停）。

> 与「Service 也由 `@Bean` 组装」的形态相比，本方案的中间态不炸；代价是**没有启动期 fail-fast**（prd Background-7 已如实留档）。

## 五、取舍记录

| 决定 | 取舍 |
|---|---|
| **业主选定**：`@Bean` 直接产出 `List<MediaAcquirer>` | 换来「装配点一处、清单是字面量」；付出「实现必须不进容器」这一脆弱前提 + 无 fail-fast |
| `MediaAcquisitionService` 零改动 | 不需要为 `service/` 的 stereotype 开例外——规范冲突从 3 条降到 2 条 |
| 依赖走 `@Bean` 方法参数（非配置类构造注入） | 配置类保持无状态（判据 3） |
| **不给实现声明 `@Bean`** | 否则 R1 失效（判据 1）——**这一条最容易被后人"顺手优化"掉**，故在配置类注释与两个实现注释里各写一遍 |
| 断言含「容器中不得存在 `MediaAcquirer` bean」 | 直接编码判据 1 的前提；被破坏时立即报警 |
| 断言含「清单恰好两项且类型/顺序正确」 | 抓「漏加实现进 `List.of`」与「清单被替换」 |
| **不引入 `@Order`** | 清单是字面量，顺序即书写顺序 |
| 断言**并入 `VidSageApplicationTests`** | `quality-guidelines.md:214` 反模式是「**单元测试**起 `@SpringBootTest`」；装配验证属集成性质，放既有冒烟测试里零新增容器启动 |
| 不为第三方场景设防 | 业主明确（个人项目）——但机制与后果已留档（prd Background-7） |

## 六、规范改写清单（精确到 file:line）

### `provider-pattern.md`

| 行 | 现状 | 改为 | 范围 |
|---|---|---|---|
| `:24` | 四件套表·实现类行：「一个来源一个类，用 `@Component` + `@ConditionalOnProperty` 装配」 | 「一个来源一个类；**装配方式随选择依据**：配置二选一用 `@Component` + `@ConditionalOnProperty`（§2），由输入类型分派则由 `config/` 的 `@Configuration` 显式装配（§2b）」 | **硬冲突项** |
| `:73` | §2b 表格「由输入类型选择」行：「**不加** `@ConditionalOnProperty`，用 `supports(input)` 自荐」 | 句末补「；装配由 `config/` 的 `@Configuration` 显式给出，不靠扫包」 | ⚠️ 增补，待确认 |
| `:77` | 参照行「`provider/download/MediaAcquirer.java` 与它的两个实现」 | 补「、装配点 `config/MediaAcquisitionConfiguration`」 | ⚠️ 增补（否则参照改后指不到装配点），待确认 |
| `:57` | §2b 正文：「**当选择依据是输入类型而非配置时**…：多个实现同时驻留，各自由输入类型自荐」 | 改为「多个实现同时**在场**……留在容器里可以，由 `config/` 的 `@Configuration` 显式装配、**不作 bean** 也可以」 | ✅ check 阶段补做（**原 design 清单漏项**） |
| `:209` | 新增实现的检查清单第 2 条：「加 `@Component` + `@ConditionalOnProperty(...)`」 | 改为「装配方式**随选择依据**（§2/§2b）：配置二选一 → `@Component` + `@ConditionalOnProperty`；输入类型分派 → 不加条件装配，改由 `config/` 显式装配」 | ✅ check 阶段补做（**原 design 清单漏项**；不补则照该清单新增一个输入类型分派的实现会直接重现本任务要消除的问题） |

### `directory-structure.md`

| 行 | 现状 | 改为 | 范围 |
|---|---|---|---|
| `:42` | `config/` 行：职责「跨领域装配（**未来落点，当前未创建**）」；允许「`@Configuration`、跨领域 Bean」 | 职责「跨领域**或跨实现**的装配（已建：`WorkspaceProperties`、`MediaAcquisitionConfiguration`）」；允许列加「**不属于任何单一实现的装配 Bean**」 | **补空洞 + 修过期** |
| `:74` | `config` 判据：「出现不属于任何单一实现、跨领域共用的 `@Configuration`」 | 「出现**不属于任何单一实现**的 `@Configuration`（跨领域或跨实现均可）」 | **补空洞** |
| `:120-124` | 「目录随代码出现，不预建空目录」正文 | **补**：`config/` 的建目录触发条件不止 `@Configuration`——`@ConfigurationProperties`（跨领域配置属性）同样算，`WorkspaceProperties` 即先例 | **补空洞** |
| `:128` | 推论：「`config/` 作为『跨领域装配类的未来落点』记入本文件即可，**出现第一个跨领域 `@Configuration` 时才建**」 | 改为已建事实 + 触发条件放宽（同 `:120-124`） | **修过期** |
| `:149` | stereotype 表：「`provider/`→`@Component`」 | 该格加例外：「（例外：由 `config/` 的 `@Configuration` 装配、不作 bean 的实现不标注解）」 | **硬冲突项** |

> 例外须按本仓库既有做法**写明理由**（先例：`web/dto/` 的破例说明、`controller/ApiExceptionHandler` 的已知破例，见 `directory-structure.md:158-169` 一带）。

**合计**：`provider-pattern.md` 3 处（1 硬 + 2 待确认）、`directory-structure.md` 5 处（1 硬 + 3 补空洞 + 1 修过期，其中 `:42` 一条同时承担补空洞与修过期）。

## 七、风险与回滚

| 风险 | 缓解 |
|---|---|
| 有人给实现补回 `@Component` → 本方案的 List **静默失效**、清单被替换 | 断言第 2 条（容器中不得存在 `MediaAcquirer` bean）报警；配置类注释与实现注释各写明一次 |
| 漏把新实现加进 `List.of` | 断言第 1 条（清单恰好两项且类型正确）报警 |
| 无启动期 fail-fast | 已如实留档（prd Background-7），业主接受 |
| 漏删 import `Component` | 未使用 import 不报错，会留脏 → 显式列为动作项 + grep 验收 |
| 先摘 `@Component` 后加 `@Bean` | 启动失败（§四）→ 按「先加后摘」执行 |
| 与在途改动混入同一提交 | 前置阻塞项已解除，工作树干净；仍逐文件 `git add` |

**回滚**：纯装配重构 + 文档，**无外部契约变化、无数据迁移**。任一步出问题 `git revert` 对应提交即可。

## 八、分支拓扑与前置依赖

- 分支 `feature/media-acquirer-explicit-wiring`，`base_branch` = **`dev`**；已 fast-forward 到 `dev` 顶点 `44ea3ec`
- 无前置任务依赖；前置状态依赖已解除（prd Background-6）
- ⚠️ `dev` 领先 `origin/dev` **18 个提交**，合并与推送均只在本地

## 九、验收方式

- **编译 + 全量测试**：`cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test`，读 surefire 报告取准确数字
- **断言负向实证（必做）**：临时给 `LocalFileMediaAcquirer` 补回 `@Component` → 断言必须变红（证明它能抓「清单被替换」）→ 撤掉后复跑绿
- **运行时验收**：`GET /api/media/tools` 三工具可用；`POST /api/media/audio` 本地文件路线成功；DEBUG 日志含 `委托媒体获取: acquirer=LocalFileMediaAcquirer`
- **规范一致性**：`grep -n "当前未创建" .trellis/spec/backend/directory-structure.md` 零命中；两处硬冲突（`provider-pattern.md:24`、`directory-structure.md:149` provider 格）已改写
