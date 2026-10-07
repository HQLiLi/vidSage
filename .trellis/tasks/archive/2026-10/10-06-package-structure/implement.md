# 实施计划：包结构整理

## R0 开工前置（必须先解决，否则 diff 会混）

`backend/src/main/java/com/vidsage/application/task/TaskProgressMessage.java` 工作区里**有用户未提交的改动**（删掉类与字段上的 `@Schema`）。包移动会碰同一个文件，两条改动混在一个 diff 里就分不清了。开工前按用户指示二选一：先在 `dev` 上单独提交，或明确「随本任务一起带进 feature 分支」。

## 批次（每批：移动 → 改 package → 改引用者 import → `mvn clean test` 绿了再下一批）

| # | 批次 | 内容 |
|---|---|---|
| 1 | 基线 | 跑一次当前测试取本次基线数（**不要引用历史数字**，基线随任务增长） |
| 2 | `domain/task` + `domain/media` + `domain/transcription` + `domain/shared` | 四包桶浅（`model/`、`port/`、`policy/`），一批做完 |
| 3 | `domain/subtitle` | 22 类，含 `model/` 二层（cleanup / translation / term / common） |
| 4 | `application/task` + `application/media` + `application/subtitle` + `application/transcription` + `application/diagnostics` | `pipeline/`、`port/` 落位 |
| 5 | spec 对账 | `directory-structure.md` 阈值句（`:162`）改写成新判据 + 7 份 backend spec 的路径引用 |
| 6 | `package-info.java` 重写 | 受影响的包（`domain/*` 五个为主，`application/*` 视内容是否失效） |

测试包镜像随对应批次一起移动（第 2、3、4 批各带一批测试类）。第 2–4 批**零行为变化**，任何测试断言/期望值的改动都是错的（那说明不小心动了行为）。

## 每批的操作顺序

```bash
# 1) 移动文件（保留历史）
git mv <旧路径> <新路径>

# 2) 改 package 声明，并改所有引用者的 import（批量前缀替换 + 人工核对）

# 3) 编译 + 全量测试（clean 必须）
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean test
```

## 验证命令

```bash
# 测试计数（先看时间戳，只信本次那批；clean 已顺带清掉旧报告，未 clean 时手动 rm -rf target/surefire-reports）
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' backend/target/surefire-reports/*.xml

# 单个用例是否失败看有没有 <failure> 元素，不看标签是否自闭合
```

**AC2 落位机械核对**（每个桶只含单一角色，按桶断言）：

```bash
cd backend/src/main/java/com/vidsage
grep -L 'public \(sealed \)\?interface' domain/*/port/*.java        # port/ 下必须全部是接口 → 无输出
grep -L 'public final class' domain/*/policy/*.java               # policy/ 下必须全是 final class → 无输出
grep -rL '^package ' --include=*.java . | head                     # 反向抽查：不该有缺 package 的文件
```

**AC3 零行为核对**：

```bash
git diff -M --stat                                  # 只应有重命名 + 少量增删行
git diff -M -- backend/src/main/java/com/vidsage/domain/task/model/TaskStatus.java   # 逐类抽查：只应有 package 行变化
git diff -M | grep -E '^[-+].*(class|interface|record|enum|@Override|public |log\.|throw )' | grep -v '^[-+][-+]'
```

最后一条若还有输出，逐行确认是 `package`/`import` 之外的意外改动（应当为空）。

**AC4 spec 对账核对**：

```bash
grep -rn "domain/subtitle\|domain/task\|domain/media\|domain/transcription\|domain/shared\|application/subtitle\|application/media\|application/transcription\|application/diagnostics" .trellis/spec --include=*.md
```

逐条确认：引用的是拆分后的真实路径，或已改写为不依赖具体路径的表述。

**运行时冒烟（可选，按用户是否要看）**：

```bash
cd backend && DASHSCOPE_API_KEY=dummy DEEPSEEK_API_KEY=dummy SERVER_PORT=18080 \
  JAVA_HOME=/f/java_soft/java21 mvn -q spring-boot:run > /tmp/vidsage-smoke.log 2>&1
```

两个已知坑：`TaskStop` 杀不掉 `spring-boot:run` 的子 JVM，收工必须 `netstat -ano | grep :18080` 查 PID 后 `taskkill //PID <pid> //F`；跨工具传文件时 Git Bash 的 `/tmp` ≠ Windows JVM 的 `/tmp`，一律 `cd` 到同目录用相对路径。

## 风险文件与回滚点

- **回滚点 = 每批的测试全绿**。批次内失败：`git checkout -- .` 丢弃本批改动（本批新建的目录若需清掉用 `git clean -fd`，注意它会删所有未跟踪文件）
- **`repository`/`mapper` 依赖的包不要碰**：`infrastructure/persistence/**` 不动，`MybatisVideoTaskRepository` 的 `@Mapper` 扫描方式不变
- **不要碰类名与方法签名**：本任务只动结构。任何改名冲动都属于 `10-06-naming-alignment` 的范畴
- **`domain/subtitle/package-info.java` 是逐类词汇索引**：拆包后必须重写，否则它描述的路径全是错的
- **`TaskProgressMessage.java`**：见 R0，工作区有用户未提交改动

## 完成前检查

- [ ] 六批全部完成，最后一次 `mvn clean test` 全绿
- [ ] AC2 / AC3 / AC4 三条脚本核对通过（输出为空或已逐条解释）
- [ ] 测试包镜像一致（`diff` 两侧目录树结构）
- [ ] spec 无残留旧路径；新判据能从 `directory-structure.md` 机械推导
- [ ] 停在汇报，不自行 commit / push / merge / 归档（等用户逐门批准）
