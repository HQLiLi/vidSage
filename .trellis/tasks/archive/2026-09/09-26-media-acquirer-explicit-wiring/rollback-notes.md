# 回滚留档：本任务未实施（2026-09-26）

> **状态**：**代码与规范已全部回滚到 HEAD**（`44ea3ec`），工作树除本任务目录外干净，测试回到基线 164 / 14 类 / 0 失败。
> **业主决定**：先用容器自动装配（`@Component` + `List<MediaAcquirer>` 注入）；本方案**暂不实施**，以下结论留档备重启。

`prd.md` / `design.md` / `implement.md` 描述的是**未实施的计划**，不是现状。

---

## 一、回滚范围（都已回到 `44ea3ec`）

| 文件 | 回滚后状态 |
|---|---|
| `config/MediaAcquisitionConfiguration.java` | **已删除**（该文件从未入库） |
| `YtDlpMediaAcquirer.java` / `LocalFileMediaAcquirer.java` | `@Component` 与 import 恢复 |
| `MediaAcquirer.java` | 恢复到 `f5e511c` 版本；其中「两个实现同时待在容器里」一句**重新成立** |
| `VidSageApplicationTests.java` | 断言方法已移除，回到只有 `contextLoads()` |
| `.trellis/spec/backend/provider-pattern.md`、`directory-structure.md` | 规范改动全部撤销 |

## 二、四个形态（本次讨论的完整决策空间）

| 形态 | 实现是 bean | 清单归你 | Service 要动 | 依赖未公开优先级 | 撞规范 |
|---|---|---|---|---|---|
| **甲**（当前采用） | ✗ | ✓ | 不用 | 是 | 2 |
| **乙**（两 `@Bean` + `List` bean，注入点无 qualifier） | ✓ | **✗ 死代码** | 不用 | 是 | 2 |
| **丙**（Service 的 `@Bean` 内 `List.of`，参数写具体类型） | ✓ | ✓ | 摘 `@Service` | 否 | 3 |
| **丁**（两 `@Bean` + `List` bean + 注入点 `@Qualifier("mediaAcquirers")`） | ✓ | ✓ | 加 1 个注解 | **否** | 2 |

- **乙 必须排除**：清单是死代码（见三-1）
- **丙 被 丁 完全覆盖**（同样收益、撞规范更多）
- **甲 与 丁 是可选的两个**：甲 更省（不碰 Service），丁 让实现也是 bean（可单独注入/加切面）且不依赖未公开优先级

## 三、本次实测出来的事实（重启时的起点，别重新推）

### 1. 容器怎么处理 `List<T>` 依赖（字节码 + 实测，`spring-beans-7.0.9` / Boot 4.1.1）

```
doResolveDependency:
  依赖类型 == List/Collection/Set → resolveMultipleBeanCollection(...)
  依赖类型 == Map              → resolveMultipleBeanMap(...)
  依赖类型.isArray()            → 按 componentType 收集

resolveMultipleBeanCollection:
  elementType = asCollection().resolveGeneric(0)
  candidates  = findAutowireCandidates(elementType)     // 只看【元素类型】的 bean
  if (candidates.isEmpty()) return null;                // 落回单 bean 匹配
  return 用 candidates 拼成的 List
```

推论（**实测验证过**）：只要容器里有 `T` 类型的 bean，任何同类型的 `@Bean List<T>` **都会被无视**；只有候选为空时才落回单 bean 匹配去用它。**顺序**取容器收集顺序，实测与书写顺序不同（配置类写 `[YtDlp, Local]`，实际注入 `[Local, YtDlp]`）。

### 2. `@Qualifier` 能锁定显式 List bean（**本次最有价值的实测**）

```java
public MediaAcquisitionService(@Qualifier("mediaAcquirers") List<MediaAcquirer> acquirers) { ... }
```

| 容器里 `MediaAcquirer` 候选数 | 不带 qualifier | 带 `@Qualifier("mediaAcquirers")` |
|---|---|---|
| 1 | 1 项（被顶掉） | **2 项 ✓** |
| 2（两个实现都是 bean） | 2 项但**顺序反了** | **2 项 ✓** |

**结论**：qualifier 走的是"候选过滤 → 落回单 bean 匹配 → 按 bean 名命中"，因此**即使实现是 bean，显式 List bean 依然生效**。这否掉了"`@Bean List` 必然被无视"的直觉。

**复现方法**：临时给实现补 `@Component`，同时在测试里加一个 `@Autowired @Qualifier("<listBeanName>") List<X>` 字段并断言项数；跑 `-Dtest=VidSageApplicationTests`。

> ⚠️ 解析 surefire 报告时注意：判断用例是否失败要看**有没有 `<failure>` 子元素**（或数 `<failure` 出现次数），**不要**用 `<testcase .../>` 是否自闭合来推断——通过用例也可能被序列化成带显式开标签的形式，本次因此误判过两次。

### 3. 丁 的两个保留意见（若将来重启）

- **"清单归你"只对带 qualifier 的那个注入点成立**：丁 下容器里确有 2 个实现 bean，将来新增的 `List<MediaAcquirer>` 注入点若忘了 qualifier，会拿到容器收集的那份（顺序可能不同）。断言要断言"容器里的实现 bean 集合正好是这两个"，把边界钉住
- **与 `provider-pattern.md:195` 反模式条字面摩擦**：那条写「用 `@Primary` + `@Qualifier` 选实现」，管的是"多实现里钦定一个"，与"钦定那份清单 bean"不是一回事 → 采用丁 时需给那条补一句限定

### 4. 一处对验证代理结论的修正

验证代理称甲「清单会被静默旁路、应用照常启动」——**对甲不准确**。甲 的元素候选数为 0，若 Spring 改了"候选为空时落回同类型 bean"这条，结果是 `NoSuchBeanDefinitionException`（**启动失败，响亮**）。"静默改内容"只属于**乙**。

### 5. 验证代理另行核实通过的（若重启可省一次）

- 泛型被保留：`getBeanNamesForType(List<String>)` → 0、`List<?>` → 1，**不会误配给别的 `List<X>` 注入点**
- `@Bean` 方法参数写具体类型无副作用：只是把创建时机提前，拓扑未变
- 新增 `@Configuration` 不被 `@WebMvcTest` 切片拉入，切片行为不变
- 全仓 `List<...>` 注入点只有 `MediaAcquisitionService` 构造器（+ 当时新增的测试字段）

## 四、重启该任务时从哪继续

1. 读 `design.md` §二（判据）与本文三-1/三-2；四形态选一个（甲 或 丁）
2. 若选丁：按本文三-3 补两个保留意见的处理，再改 `prd.md` 的 R3（Service 从"零改动"改为"加一个 `@Qualifier`"）
3. `implement.md` 的步骤 1–5 可直接复用（丁 需要把步骤 1 的配置类换成"3 个 `@Bean`"版本）

## 五、与本任务无关、但确凿的另一件事

`directory-structure.md:42`（以及 `:128` 附近）写 `config/`「**未来落点，当前未创建**」——**这是事实错误**：`config/WorkspaceProperties.java` 已存在且已入库。该陈述与本次装配方案无关，回滚后依然错。
