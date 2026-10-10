# 执行计划：WorkspaceService 端口倒置

## 执行前须知

- **环境**：Git Bash 下必须显式 `JAVA_HOME=/f/java_soft/java21`；命令一律 `cd backend && JAVA_HOME=/f/java_soft/java21 mvn ...`。
- **量测试前先删 `backend/target/surefire-reports`**（陈旧报告会冒充本次结果）；**新增/改名类之后必须 `mvn clean`**。
- **分工（沿用既有口径）**：主源码（批次 1、2、3）由**项目所有者**写，示例代码在本文件里、不贴会话；测试（批次 4）由 **AI** 按写出来的代码改。每步动手前先问。
- **门**：每批做完**立刻停**，等审查；提交 / 推送 / 合并 / 归档各是一道独立的门。
- **顺序铁律**：**先改判据、再搬代码**（批次 0 → 1）。

### 步骤 0 — 记录基线（执行前必做）

```bash
rm -rf backend/target/surefire-reports
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q test
grep -h "Tests run" target/surefire-reports/*.txt | awk -F'[,:]' '{t+=$2; f+=$4; e+=$6; s+=$8} END {print "tests="t, "fail="f, "err="e, "skip="s}'
```

把三个数字记进本文件的「实测记录」节。**基线用例数 = 之后的验收下限**（只许涨，不许因为改结构而少）。

---

## 批次 0 — 判据先行（纯文档，不碰代码）

按 design.md §七 的清单改 **#1、#2、#5、#6、#7、#11** 六处（`spec/backend/directory-structure.md` 四处 + 两个 `package-info`；#11 是 2026-10-09 stereotype 改判随行追加）：

1. `infrastructure/fs/` 行：读数 → 「读数与定位/创建」；
2. 依赖表 `application` / `infrastructure` 两行补 `config`；
3. 端口归属判据节补 `WorkspaceDirectoryManager` 例；
4. `application/media/package-info.java` 重写（根留 resolver，stereotype `@Component` 机制/协作件档；**已落盘初稿里那个 `{@code @Service}` 字样随本追加改掉**）；
5. `infrastructure/package-info.java` 分包清单补 `fs`；
6. 构件说明表两档 → 三档（机制/协作件档收编 `TaskSubscriptionRegistry` / `WorkspaceKeyGenerator`）、服务判据加限定、「根留门面」措辞松口（design §七 #11）。

> `spec/` 属「规范文档」，改它按分支规则**必须走 feature 分支**（纯 `prd/design/implement` 等任务工件才可在 dev 上直接改）。故本批开始前先开分支，见下。

**验证**：`git diff --stat` 只该出现上面 6 处所在的 3 个文件；`grep -rn "WorkspaceService" .trellis/spec backend/src/main/java/com/vidsage/application/media/package-info.java` 的命中数应只剩第 3、4 项里「历史注 / 待改」的约定部分。

**门**：停下，交审查。

---

## 批次 1 — 新增端口 + 实现（不动任何调用点）

新增两件，`WorkspaceService` 原样留着——此批结束时**两条路径并存、全绿**，可独立回滚。

### 1.1 端口 `application/media/port/WorkspaceDirectoryManager.java`

```java
package com.vidsage.application.media.port;

import com.vidsage.domain.shared.model.WorkspaceKey;
import java.nio.file.Path;

/**
 * 工作区目录的定位与创建（内容寻址目录 + 根解析）。
 *
 * <p>本端口是「本机文件系统的工作区落点」这件事的接缝：调用方只认它，不认识
 * {@code java.nio.file} 与根配置从哪来。目录名公式（{@code sanitize(标签)-key}）不在这里，
 * 它早已是 domain 的 {@code WorkspaceDir}；本端口只负责把公式算出的名字挂到配置根下。
 */
public interface WorkspaceDirectoryManager {

    /**
     * 解析工作区目录并确保它存在（已存在时是幂等空操作）。
     *
     * @param label 给人看的目录名标签（可空白，公式内有回退）
     * @param key   内容寻址 key
     * @return 已创建的工作区目录绝对路径
     * @throws IllegalStateException 目录创建失败（根路径不可写等）
     */
    Path workspaceFor(String label, WorkspaceKey key);

    /**
     * 工作区根目录的绝对路径（<b>不建目录</b>，无副作用）。
     *
     * @return 根目录绝对路径（root 未配置时回退 {@code WorkspaceDir.FALLBACK_NAME}）
     */
    Path workspaceRoot();
}
```

**验收点**：本文件 import 只有 `domain` 与 `java.nio.file`。

### 1.2 实现 `infrastructure/fs/NioWorkspaceDirectoryManager.java`

从 `WorkspaceService` **逐字搬** `rootDir()` / `ensureCreated()` / `workspaceRoot()` / 两个 `workspaceFor` 的目录那半：

```java
package com.vidsage.infrastructure.fs;

import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.shared.model.WorkspaceDir;
import com.vidsage.domain.shared.model.WorkspaceKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工作区目录端口（{@link WorkspaceDirectoryManager}）的本机文件系统实现：
 * 按配置根 + 内容寻址目录名解析落点，并懒创建。
 */
@Slf4j
@Component
public class NioWorkspaceDirectoryManager implements WorkspaceDirectoryManager {

    private final WorkspaceProperties properties;

    public NioWorkspaceDirectoryManager(WorkspaceProperties properties) {
        this.properties = properties;
    }

    @Override
    public Path workspaceFor(String label, WorkspaceKey key) {
        return ensureCreated(rootDir().resolve(WorkspaceDir.of(label, key).value()));
    }

    @Override
    public Path workspaceRoot() {
        return rootDir().toAbsolutePath();
    }

    private Path rootDir() {
        return Path.of(properties.root() == null ? WorkspaceDir.FALLBACK_NAME : properties.root());
    }

    private Path ensureCreated(Path dir) {
        Path absolute = dir.toAbsolutePath();
        try {
            Files.createDirectories(absolute);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建任务工作目录：" + absolute
                    + "；请检查 vidsage.workspace.root 是否存在且可写。原因：" + e.getMessage(), e);
        }
        log.debug("任务工作目录: dir={}", absolute.getFileName());
        return absolute;
    }
}
```

**逐字保真**：ISE 文案、debug 文案、`FALLBACK_NAME` 回退、`workspaceRoot()` 不建目录——一个字不改（design.md §五）。

**验证**：

```bash
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q -DskipTests compile
grep -n "com.vidsage.application" src/main/java/com/vidsage/infrastructure/fs/NioWorkspaceDirectoryManager.java   # 只该有一行 implements 的 import
```

**门**：停下，交审查。

---

## 批次 2 — 新增 `WorkspaceKeyGenerator`

```java
package com.vidsage.application.media;

import com.vidsage.application.media.port.FileStateReader;
import com.vidsage.domain.media.model.MediaFileInfo;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.WorkspaceKey;
import org.springframework.stereotype.Service;

/**
 * 媒体输入 → 内容寻址 key。
 *
 * <p>只有本机路径（{@link MediaInput.LocalPath}）的身份要从文件系统采样（大小 + mtime），
 * 其余来源的身份由输入自己算——所以采样是一次查询，喂给 ``MediaInput.getContentKey``。
 * 把这段从工作区目录能力里摘出来的原因见任务 design.md：目录端口落到 infrastructure 后，
 * 实现类不能反过来依赖 application 的文件读数端口。
 */
@Component
public class WorkspaceKeyGenerator {

    private final FileStateReader fileStateReader;

    public WorkspaceKeyGenerator(FileStateReader fileStateReader) {
        this.fileStateReader = fileStateReader;
    }

    /**
     * 解析输入的内容寻址 key。
     *
     * @param input 媒体输入；不可为空
     * @return 内容寻址 key
     * @throws IllegalArgumentException 输入为空
     */
    public WorkspaceKey resolve(MediaInput input) {
        if (input == null) {
            throw new IllegalArgumentException("媒体输入不能为空：请传 RemoteUrl / LocalPath / InWorkspace");
        }
        return input.getContentKey(fileStateOf(input));
    }

    private MediaFileInfo fileStateOf(MediaInput input) {
        if (!(input instanceof MediaInput.LocalPath local) || local.value() == null) {
            return MediaFileInfo.NOT_APPLICABLE;
        }
        return fileStateReader.getFileState(local.value());
    }
}
```

> 类名 `WorkspaceKeyGenerator` 与 stereotype `@Component`（机制/协作件档）已定（design.md §九 第 1 条，2026-10-09；stereotype 同日改判，见 prd Q7）。

**验证**：`mvn -q -DskipTests compile`。

**门**：停下，交审查。

---

## 批次 3 — 调用点改造 + 删除 `WorkspaceService`

### 3.1 四个 Coordinator（各加一个 resolver + 换端口）

```java
// 字段 + 构造参数：WorkspaceService workspaceService  →  WorkspaceDirectoryManager workspaceDirs, WorkspaceKeyGenerator keyResolver

// 调用点（原来一行 workspaceService.workspaceFor(input)）
WorkspaceKey key = keyResolver.resolve(input);
Path workDir = workspaceDirs.workspaceFor(input.dirLabel(), key);
```

落点：`AudioPreparationCoordinator:107`、`SubtitlePreparationCoordinator:95`、`SubtitleCleanupCoordinator:137`、`SubtitleTranslationCoordinator:138` 与 `:187`。

> **两行不能并成一行**：实参从左到右求值，合并后 `input.dirLabel()` 会先于 resolver 的 null 卫语句执行（design.md §一）。

### 3.2 `VideoTaskService` / `SubtitlePipeline`（只换端口，不加 resolver）

- `VideoTaskService`：`:313` `workspaceService.workspaceFor(originalFileName, workspaceKey)` → `workspaceDirs.workspaceFor(...)`；`:134` / `:239` / `:344` 的 `workspaceRoot()` 改口。
- `SubtitlePipeline`：`:343` 的 `workspaceRoot()` 改口。

### 3.3 删除 `application/media/WorkspaceService.java`

**验证**：

```bash
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q -DskipTests compile   # 主源码编译通过（测试此时必然编译不过，属预期）
grep -rn "WorkspaceService" backend/src/main/java/ || echo "main 零命中"
grep -rn "import com.vidsage.infrastructure" backend/src/main/java/com/vidsage/application/ || echo "application 零 infrastructure import"
```

**门**：停下，交审查（此时测试未改，会编译失败——属已知中间态，不要顺手改）。

---

## 批次 4 — 测试重分（AI 执行）

按 design.md §六 的表把 `WorkspaceServiceTest` 一分为二，其余 6 个测试类改构造夹具。

**硬约束：断言值一个字不放宽**（不在断言里加 `assertNotNull` 之类的软垫，不删用例）。

**验证**：

```bash
rm -rf backend/target/surefire-reports
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean test
grep -h "Tests run" target/surefire-reports/*.txt | awk -F'[,:]' '{t+=$2; f+=$4; e+=$6; s+=$8} END {print "tests="t, "fail="f, "err="e, "skip="s}'
```

**验收**：全绿，且用例数 ≥ 基线（拆分不减用例）。

**门**：停下，交审查。

---

## 批次 5 — 收尾文档 + 证据 + 全量验证

1. 补 design.md §七 剩下的 **#3、#4、#8、#9**（`WorkspaceService` 例子清理、历史注补日期、两处 Javadoc 存活引用）。
2. 落证据到本任务 `evidence/`：
   - `grep` 三条：`WorkspaceService` 零命中、`application` 零 `infrastructure` import、`NioWorkspaceDirectoryManager` 零 `application` import（**除 implements 那一行**）；
   - 基线 / 终态测试计数对照；
   - `git diff` 里行为保真清单（design.md §五）的逐项核对注记。
3. 全量：

```bash
rm -rf backend/target/surefire-reports
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean test
```

**门**：停下，交审查 → 审查通过才进入提交门（提交 / 推送 / 合并 / 归档各自单独批准）。

---

## 回滚点

| 批次 | 回滚动作 |
|---|---|
| 0 | `git checkout -- .trellis/spec backend/src/main/java` |
| 1 | 删两个新文件即可，无调用点依赖 |
| 2 | 删 resolver 文件 |
| 3 | 恢复到批次 2 末尾（`git checkout -- backend/src/main/java`）——此时 `WorkspaceService` 尚在 |
| 4 | `git checkout -- backend/src/test` |
| 5 | 文档单独回退，代码不受影响 |

## 实测记录

| 时点 | tests | fail | err | skip |
|---|---|---|---|---|
| 基线（执行前） | 482 | 0 | 0 | 1 |
| 收尾 | 482 | 0 | 0 | 1 |
