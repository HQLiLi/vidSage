# 包结构与命名

## 按功能分包，不按技术分层

本项目采用 **package-by-feature**：每个业务能力一个包，包内自带它的接口、实现、配置与 DTO。**不要**建 `controller/`、`service/`、`repository/` 这类按技术分层的顶层包。

当前结构（`backend/src/main/java/com/vidsage/`）：

```
com.vidsage
├── VidSageApplication.java        # 唯一的启动类，放在根包
└── asr/                           # 「语音识别」这个功能包
    ├── ASRProvider.java           # 接口（对外契约），留在功能包根
    ├── TranscriptionService.java  # 服务层：组装命令、委托当前配置的 Provider
    ├── model/                     # 实现方中立的数据契约
    │   ├── AudioSource.java       # sealed：AudioUrl / AudioFilePath
    │   ├── TranscriptionCommand.java
    │   ├── TranscriptionResult.java
    │   └── TranscriptionSegment.java
    └── provider/                  # 一个实现一个子包，新增实现开新子包
        └── dashscope/
            ├── DashScopeAsrProvider.java
            └── DashScopeProperties.java
    └── web/                       # HTTP 入口子包，包内自带请求体 record
        └── AsrController.java
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
| Spring 组件 | 实现类用 `@Component`，**不用 `@Service`** | `DashScopeAsrProvider`、`TranscriptionService` |
| Controller | 业务名 + `Controller`，放功能包的 `web/` 子包 | `AsrController` |

> `ASRProvider` 是全大写缩写开头，与 Spring 常见的 `AsrProvider` 写法不同。**跟随现有代码**，不要"顺手改正"。

## 契约类的位置

功能包内部按职责分子包：**对外接口（`XxxProvider`）留在功能包根**；数据契约（`Command` / `Result` / 子结构）放 `model/`；**实现类与该实现专用的 `*Properties` 放 `provider/<实现短名>/` 子包**（如 `provider/dashscope/`），新增实现开新子包、不重排已有代码。不建 `dto/`、`util/`、`config/` 这类按技术命名的顶层分包。

参照：`backend/src/main/java/com/vidsage/asr/`（接口在包根，服务层紧随其后，`model/` 中立契约，`provider/dashscope/` 云端实现与配置，`web/` HTTP 入口）。

## 服务层与 HTTP 入口

- **服务层（`XxxService`）放功能包根**：对上屏蔽 Provider 装配细节，组装契约后委托接口，不出现实现类与 SDK 类型
- **Controller 放 `web/` 子包**：依赖服务层，不直接依赖 Provider；请求体 record 定义在 Controller 内部
- `TranscriptionService` 用 `@Component`，Controller 只做「接收 → 校验 → 委托 → 返回」，不写业务逻辑

## 测试包结构

测试包与主包**镜像对应**：`backend/src/test/java/com/vidsage/asr/provider/dashscope/DashScopeAsrProviderTest.java` 对应 `.../main/java/com/vidsage/asr/provider/dashscope/DashScopeAsrProvider.java`。

- 测试类名 = 被测类名 + `Test`
- 另有 `VidSageApplicationTests` 作为上下文加载冒烟测试

## 不适用

`database-guidelines.md` 与 `logging-guidelines.md` 已删除，原因见 [index.md](./index.md)。




