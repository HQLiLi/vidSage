# 重构：termlookup 并入 provider/llm（保留接口），编排内联翻译流程

## 最终结构（对照上文目录树）

1. **迁移+保留接口**：`provider/termlookup/TermLookupProvider.java` → `provider/llm/TermLookupProvider.java`（接口不动，单方法 lookup）；`dashscope/DashScopeTermLookupProvider + Properties` → `provider/llm/dashscope/`（prompt/enable_search/接地校验/嵌套 DTO 原样带过去）；实现测试迁到 `provider/llm/dashscope/`。`@ConditionalOnProperty(vidsage.term-lookup.provider=dashscope)` 装配保留，application.yml 的 provider 行保留。
2. **契约保留**：`model/subtitle/TermLookupCommand|TermLookupResult` 不动（接口契约，同 asr 模式契约放 model/）。
3. **拆类型**：`SubtitleContext` 回退三字段原样；新增 `model/subtitle/VerifiedTerm`（+official/officialNote）与容器 `VerifiedTerms`（terms 产物文件读写形状）。弃权=official 空串，无三态。
4. **编排内联翻译流程**：删 `service/SubtitleTermVerifier`；`SubtitleTranslationService.translate()` 内新增私有编排（真要翻译 且 `terms-<route>-<target>.json` 缺失 → ObjectProvider 取 TermLookupProvider → 循环逐条 lookup → 组装 VerifiedTerms → 原子写盘 + 统计日志；失败词跳过下次补跑；provider 缺席=查证关闭跳过）。`SubtitleCleanupService` 完全回退（撤 verifier/translateProps 注入）。`SubtitleArtifactNames` 增 `verifiedTermsFileName(route, target)`。
5. **删**：`provider/termlookup/` 包、`SubtitleTermVerifier` 及其测试；测试改造：DashScope 实现测试迁位，翻译 service 测试补查证编排用例（stub provider），清洗 service 测试回退。
6. **数据**：素材目录 context-asr-cloud.json 还原为改动前备份（三字段版）。
7. **档案**：任务 1 design.md/prd.md 改写（查证挂翻译流程、terms 产物语义）；任务 2 PRD 的 official 引用改为读 terms 文件。

## 验收

- `mvn test` 全绿
- 真实复跑：target=en → translate 生成 `terms-asr-cloud-en.json`（结论与上轮一致：只狼→Sekiro 官方等），context 逐字节未变；重跑零搜索；证据更新 research/
- workflow 硬约束：完成即停，交你审查；不自动 commit/merge/archive

## 执行顺序

档案先行（design/prd）→ 派 trellis-implement 改码 → trellis-check 验证 → 主会话真实复跑 → 停下待审