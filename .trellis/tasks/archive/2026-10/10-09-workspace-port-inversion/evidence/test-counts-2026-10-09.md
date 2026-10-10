# 测试计数对照（2026-10-09）

| 时点 | tests | fail | err | skip | 命令 |
|---|---|---|---|---|---|
| 基线（批次 1 前） | 482 | 0 | 0 | 1 | `mvn -q test` |
| 终态（批次 4 后） | 482 | 0 | 0 | 1 | `mvn -q clean test` |

- 验收口径（implement.md）：基线用例数是下限，只许涨不许跌 —— 终态 482 = 基线 482，持平 ✓
- 拆分核对：`WorkspaceServiceTest` 9 例 → `NioWorkspaceDirectoryManagerTest` 4 例 + `WorkspaceKeyGeneratorTest` 5 例，零丢失 ✓
- 6 个夹具类只换构造与桩，断言值未放宽（两处断言的「形态」随被测对象平移：`verify(never()).workspaceFor` → `verify(never()).ensureWorkspaceDirectory`；`workspaceFor(in)` 重建断言 → generator+manager 两行组合，比对值逐字相同）
