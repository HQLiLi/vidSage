# 包结构与命名

## 按功能分包，不按技术分层

本项目采用 **package-by-feature**：每个业务能力一个包，包内自带它的接口、实现、配置与 DTO。**不要**建 `controller/`、`service/`、`repository/` 这类按技术分层的顶层包。

当前结构（`backend/src/main/java/com/vidsage/`）：

```
com.vidsage
├── VidSageApplication.java        # 唯一的启动类，放在根包
└── asr/                           # 「语音识别」这个功能包
    ├── ASRProvider.java           # 接口（对外契约）
    ├── DashScopeAsrProvider.java  # 云端实现
    ├── DashScopeProperties.java   # 该实现专用的配置属性
    ├── TranscriptionCommand.java  # 入参契约
    ├── TranscriptionResult.java   # 出参契约
    └── TranscriptionSegment.java  # 出参契约的子结构
```

参照文件：`backend/src/main/java/com/vidsage/asr/`

新增功能（如 `translate`、`summarize`、`index`）时，同样在 `com.vidsage` 下开一个平级包，而不是往 `asr/` 里塞或建分层包。

## 启动类

- 只有一个：`com.vidsage.VidSageApplication`
- 固定注解组合：`@SpringBootApplication` + `@ConfigurationPropertiesScan`

`@ConfigurationPropertiesScan` 让 `@ConfigurationProperties` 类无需逐个 `@EnableConfigurationProperties` 就能被扫描到。新增配置属性类时**不需要**改启动类。

参照：`backend/src/main/java/com/vidsage/VidSageApplication.java`

## 命名约定

| 类型 | 约定 | 例子 |
|---|---|---|
| 接口 | 用业务名 + 角色后缀，**不加 `I` 前缀** | `ASRProvider` |
| 实现类 | 实现方特征前缀 + 接口名 | `DashScopeAsrProvider`（未来：`LocalFunAsrProvider`） |
| 配置属性 | 实现方名 + `Properties` | `DashScopeProperties` |
| 契约 | 动作名 + `Command` / `Result` | `TranscriptionCommand` / `TranscriptionResult` |
| 契约子结构 | 语义名，不叫 `DTO` / `VO` | `TranscriptionSegment` |
| Spring 组件 | 实现类用 `@Component`，**不用 `@Service`** | `DashScopeAsrProvider` |

> `ASRProvider` 是全大写缩写开头，与 Spring 常见的 `AsrProvider` 写法不同。**跟随现有代码**，不要"顺手改正"。

## 契约类的位置

对外契约（`Command` / `Result` / `Segment`）放在**功能包根下**，与接口同层，不单独建 `dto/` 或 `model/` 子包。

参照：`backend/src/main/java/com/vidsage/asr/` 下六个文件平铺，无子目录。

## 测试包结构

测试包与主包**镜像对应**：`backend/src/test/java/com/vidsage/asr/DashScopeAsrProviderTest.java` 对应 `.../main/java/com/vidsage/asr/DashScopeAsrProvider.java`。

- 测试类名 = 被测类名 + `Test`
- 另有 `VidSageApplicationTests` 作为上下文加载冒烟测试

## 不适用

`database-guidelines.md` 与 `logging-guidelines.md` 已删除，原因见 [index.md](./index.md)。
