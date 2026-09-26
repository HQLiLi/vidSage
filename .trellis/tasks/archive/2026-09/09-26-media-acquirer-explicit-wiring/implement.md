# 执行计划：媒体获取装配改为 @Bean 显式产出 List

> 设计与取舍见 [`design.md`](./design.md)。本文只写**按序执行的动作、验证命令与回滚点**。

## 前置门（`task.py start` 之前必须全绿）

```bash
git branch --show-current      # 期望 feature/media-acquirer-explicit-wiring
git merge-base --is-ancestor 44ea3ec HEAD && echo "基于 dev 顶点 ✓"
git status --porcelain         # 期望仅 ?? .trellis/tasks/09-26-media-acquirer-explicit-wiring/
```

**前置阻塞项已解除**（2026-09-26）：业主审查代码时的 6 处在途改动已提交到 `dev`（`85a2f7e` / `f5e511c` / `44ea3ec`），工作树干净。仍逐文件 `add`。

**任何一条不满足 → 停，先查明原因。**

---

## 步骤 0 · 基线实测（不改任何代码）

```bash
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
python -c "
import glob,re
t=e=s=f=0
for p in glob.glob('target/surefire-reports/*.xml'):
    h=open(p,encoding='utf-8').read(2000)
    m=re.search(r'tests=\"(\d+)\" errors=\"(\d+)\" skipped=\"(\d+)\" failures=\"(\d+)\"',h)
    if m: t+=int(m[1]); e+=int(m[2]); s+=int(m[3]); f+=int(m[4])
print(f'tests={t} errors={e} skipped={s} failures={f} classes={len(glob.glob(\"target/surefire-reports/*.xml\"))}')
"
```

**期望**：`164 tests / 14 classes / 0 failures`。

- **必须先删 `target/surefire-reports/`**：陈旧 xml 不会被覆盖，会数进幽灵测试
- **python 读 xml 要 `encoding='utf-8'`**：Windows 控制台默认 GBK
- **`bc` 在 Git Bash 下不存在**，算总数用 python

**回滚点**：无（未改动）。

---

## 步骤 1 · 新增配置类（**先加，此步无副作用**）

新建 `backend/src/main/java/com/vidsage/config/MediaAcquisitionConfiguration.java`（代码见 design §三）：一个 `@Bean` 方法返回 `List<MediaAcquirer>`，方法体内 `List.of(new ...)`。

**此时两个实现仍带 `@Component`**——容器里仍有实现 bean，服务走集合分支拿到它们，行为与改前一致；本 `@Bean List` 暂时是死代码。**这是刻意的安全顺序**（design §四：反序会启动失败）。

**验证**：

```bash
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q test
# 期望：全绿，164（行为未变）
```

**回滚点 A**：删掉该新文件即可（本步独立）。

---

## 步骤 2 · 摘 `@Component` + 改写注释（本方案生效的一步）

1. `YtDlpMediaAcquirer.java`：删 `:17` import、删 `:43` `@Component`（留 `:42` `@Slf4j`）；改写 `:39-40` 注释
2. `LocalFileMediaAcquirer.java`：删 `:18` import、删 `:31` `@Component`（留 `:30` `@Slf4j`）；改写 `:28` 注释

> 注释改写方向见 design §三。**注意原句「两个下载器同时驻留容器」在本方案下已成假命题**，必须改掉，不能只删后半句。

**验证**：

```bash
grep -n "Component" backend/src/main/java/com/vidsage/provider/download/ytdlp/YtDlpMediaAcquirer.java \
                    backend/src/main/java/com/vidsage/provider/download/local/LocalFileMediaAcquirer.java
# 期望：零命中（含 import）

grep -c "^    @Bean$" backend/src/main/java/com/vidsage/config/MediaAcquisitionConfiguration.java
# 期望：1（只有清单那一个；实现**不得**各自声明 @Bean）
# 注：直接 grep -c "@Bean" 会数成 2——类注释里也出现过该字样。要按行首缩进匹配。

cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
# 期望：全绿，164 —— 清单来源已切换到配置类
```

**回滚点 B**：`git checkout -- backend/src/main/java/com/vidsage/provider/download/`。

---

## 步骤 3 · 装配断言 + 负向实证

1. `VidSageApplicationTests` 新增一个方法，断言两件事（**注入 `ApplicationContext`，按该测试类现状最小改**）：
   - 注入的 `List<MediaAcquirer>` 恰好**两项**，且分别为 `YtDlpMediaAcquirer` / `LocalFileMediaAcquirer`，顺序即 `List.of` 顺序
   - 容器里**不存在**任何 `MediaAcquirer` 类型的 bean（`getBeansOfType(MediaAcquirer.class)` 为空）
2. **负向实证（必做）**：临时给 `LocalFileMediaAcquirer` 补回 `@Component` → 跑该测试 → **必须变红**（清单被替换成 1 项游离实现）→ 撤掉 → 复跑必须绿

**验证**：

```bash
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q -Dtest=VidSageApplicationTests test
```

**回滚点 C**：新增方法可单独删。

---

## 步骤 4 · 全量测试

```bash
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
# 期望：全绿，165 tests / 14 classes（164 + 1 个新增方法）
```

---

## 步骤 5 · 启动冒烟（运行时验收）

```bash
cd backend && DASHSCOPE_API_KEY=dummy DEEPSEEK_API_KEY=dummy SERVER_PORT=18080 \
  JAVA_HOME=/f/java_soft/java21 mvn -q spring-boot:run > /tmp/vidsage-eager.log 2>&1
```

- `application.yml` 现有 `spring.profiles.default: local`（已提交于 `85a2f7e`），会加载 `application-local.yml` 的**真实密钥**与工具绝对路径；dummy 环境变量不再起「避开真 key」的作用
- 冒烟内容：
  1. `curl -s http://localhost:18080/api/media/tools` → 三工具 `available=true`
  2. `POST /api/media/audio` 用**本地文件路线**（传 `localPath`）→ 成功产出；日志须出现 `委托媒体获取: acquirer=LocalFileMediaAcquirer`（证明清单里确有它）
  3. URL 路线（真实下载）视网络可达性；**不可达就如实记为「未验证」，不要写成通过**
- **收工必须按 PID 杀**（`TaskStop` 杀不掉 fork 出的子 JVM）：

```bash
PID=$(netstat -ano | grep LISTENING | grep ":18080" | awk '{print $5}' | head -1); taskkill //PID "$PID" //F
netstat -ano | grep LISTENING | grep -E ":1808[01]" || echo "已释放"
```

- **跨工具传文件别用 `/tmp`**：Git Bash ≠ Python（`F:\tmp`）≠ Windows JVM（`C:\tmp`）

---

## 步骤 6 · 规范改写（design §六清单，逐条勾）

| 文件 | 处数 | 明细 |
|---|---|---|
| `provider-pattern.md` | 3 | `:24`（硬冲突）、`:73`、`:77`（⚠️ 后两处**须业主确认后**再改） |
| `directory-structure.md` | 5 | `:42`、`:74`、`:120-124`、`:128`、`:149` provider 格加例外（并按本仓库既有做法写明理由） |

**注意**：`service/`→`@Service` 这一格**不改**——本方案不动 `MediaAcquisitionService`。

**验证**：

```bash
grep -n "当前未创建" .trellis/spec/backend/directory-structure.md        # 期望零命中
grep -n "Component" .trellis/spec/backend/directory-structure.md .trellis/spec/backend/provider-pattern.md
# 人工核对两处硬冲突行均已改写
```

**回滚点 D**：规范改动单独一个提交，便于整体 revert。

---

## 步骤 7 · 验收（design §九）

1. 全量测试 165 全绿
2. 断言负向实证（步骤 3.2）已验证
3. 运行时：`/api/media/tools` + 本地路线抽音频成功 + 日志证明清单里有 `LocalFileMediaAcquirer`
4. 规范一致性：`当前未创建` 零命中；两处硬冲突已改写
5. **零 diff 核验**：`git diff HEAD -- backend/src/main/java/com/vidsage/service/MediaAcquisitionService.java` 期望为空
6. 提交纯净性：`git show --stat HEAD` 只含本任务文件

---

## 风险文件与回滚点汇总

| 文件 | 风险 |
|---|---|
| `config/MediaAcquisitionConfiguration.java` | 新增；**只能有 1 个 `@Bean`**（给实现各自声明 `@Bean` 会使清单失效） |
| `provider/download/ytdlp/YtDlpMediaAcquirer.java` | 摘注解 + 注释改写（原句已变假） |
| `provider/download/local/LocalFileMediaAcquirer.java` | 同上 |
| `service/MediaAcquisitionService.java` | **不得有任何改动**（验收项含零 diff 核验） |
| `VidSageApplicationTests.java` | 新增方法；注入方式按现状最小改 |
| `.trellis/spec/backend/provider-pattern.md` / `directory-structure.md` | 规范改动；`:73`/`:77` 待确认 |

**回滚点**：A（步骤 1，删新文件）/ B（步骤 2）/ C（步骤 3）/ D（步骤 6，单独提交）。**步骤 2 是一步到位的切换**，拆开做（先摘后加）会启动失败。

---

## 提交切分建议

1. `refactor(config): 媒体获取清单改由 @Bean 显式产出（实现不再作 bean）`
2. `docs(provider): 改写两个实现「不作 bean」的注释`
3. `test: 装配断言（清单内容 + 容器不得有 MediaAcquirer bean）`
4. `docs(spec): Provider 装配方式与 config/ 落点规范修订`

**逐文件 `git add`。**

---

## 审查门

**每步完成后停下等业主审查，不要连续推进。** 提交、合并回 `dev`、归档逐门批准。
