# 原型执行台账

| # | 派发 | 子代理 | 覆盖步骤 | 开始 epoch | 结束 epoch | 耗时 | usage 三件套 |
|---|---|---|---|---|---|---|---|
| 1 | 布局骨架批次 | trellis-implement | S1–S3 | ~1792562340 | ~1792563000 | ~660s | host 未报告 / host 未报告 / host 未报告 |
| 2 | 关键词/主题 pane 批次 | trellis-implement | S4 | ~1792563420 | ~1792563720 | ~300s | host 未报告 / host 未报告 / host 未报告 |
| 3 | 3:2 分栏 + 字幕行编辑 | trellis-implement | S5 | ~1792564020 | ~1792564440 | ~420s | host 未报告 / host 未报告 / host 未报告 |
| 4 | 垂直关键词列表 + 镂空图标 | trellis-implement | S4/S5 修订 + S7 滚动收边 | ~1792565160 | ~1792565440 | ~280s | host 未报告 / host 未报告 / host 未报告 |
| 5 | 悬浮自动隐藏滚动条 | trellis-mechanic | S7 滚动条外观 | ~1792589700 | ~1792589850 | ~150s | host 未报告 / host 未报告 / host 未报告 |
| 6 | 关键词限高滚动 + 去背景 | trellis-mechanic | S4/S7 修订 | ~1792590240 | ~1792590390 | ~150s | host 未报告 / host 未报告 / host 未报告 |
| 7 | 返回文案 + 固定播放器 + 去分割线 | trellis-mechanic | S3/S4/S7 修订 | ~1792590600 | ~1792590780 | ~180s | host 未报告 / host 未报告 / host 未报告 |
| 8 | URL 子态入口 | trellis-mechanic | S7 | ~1792590990 | ~1792591170 | ~180s | host 未报告 / host 未报告 / host 未报告 |
| 9 | 第一轮就绪态静态检查 | trellis-check | S8 | ~1792591400 | ~1792591500 | ~100s | host 未报告 / host 未报告 / host 未报告 |

## 批次备注

- 工作区：主仓库 `prototype/`，该目录被根 `.gitignore` 排除，无提交。
- 产出：mock 契约模块、毫秒时间口径、左右分栏与左列三块骨架。
- 验证：`npm run type-check` 通过；ESLint 定向与三个改动文件 oxlint 定向通过；浏览器检查 1280×800、1920×1080、900×800。
- 已知环境问题：根 `npm run lint` 的 oxlint 因 `/prototype/` 被 gitignore 而报 “No files found to lint”；原型不受正式 lint 硬门槛约束。
- 第二批按所有者评论调整：关键词输入框固定在上，已有关键词 chips 在下；主题区只显示 theme。
- 第三批按所有者评论调整：左侧视频区 / 右侧字幕区 = 3:2；字幕行右上角 hover 显示修改图标并进入行级编辑。
- 第四批按所有者评论调整：关键词改为输入框在上、垂直列表在下；按钮改为「重新生成」；修改图标改为纯镂空样式；取消关键词区内部滚动，由左列统一滚动。
- 第四批子代理最终返回 429，但文件编辑已完成；主线重新执行 type-check、build-only 和浏览器复验后收口。
- 第五批新增 `.overlay-scrollbar`：默认透明，仅悬停 / 聚焦时显示当前区域细滑块；主线浏览器确认默认不可见、右侧悬停时出现细条。
- 第六批删除指纹不一致提示；关键词/主题去背景仅留分割线；关键词列表 `max-h-60 overflow-y-auto`。子代理浏览器验证被 origin approval 阻断，主线以 type-check 和 Vite 模块 200 响应收口，待用户刷新页面肉眼验收。
- 第七批将顶栏「重新上传」改为「返回」；播放器改为固定 `aspect-video`，新增关键词不再改变播放器高度；移除关键词/主题区块级分割线。
- 第八批增加 `?emptyKeywords=1`、`?degraded=1`、`?noTranslation=1` 三个原型子态入口；隐藏译文时搜索只匹配可见原文。
- 第九批静态检查发现并修复播放器 `h-full` 与 `aspect-video` 冲突、主题区无效内部滚动；ESLint、type-check、build 均通过。
- 第十批新增翻译语种 mock（与 `GET /api/languages` 同形）、`TranslationBar.vue` 双 select / 互换 / 翻译 CTA，以及约 2 秒本地翻译模拟；完成日语双语切换、同语种禁用、重新生成失效后重译的浏览器验证。
- 第十一批按所有者要求把就绪页改为默认不翻译：初始 `translatedLanguage = null`、目标语种英语，字幕只显示原文；刷新后浏览器确认无译文行，点击翻译后双语字幕仍正常出现。
