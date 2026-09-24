# 分支工作流（硬规则）

> 2026-09-24 项目所有者确立。适用于前后端所有代码与规范改动。

---

## 规则

1. **先开分支再修改**：任何修改动手前，从最新 `master` 拉出特性分支（`feature/<短名>`），全程在分支上改。禁止直接在 `master` 上修改
2. **修改结束 → 推送 → 用户审查**：测试通过后提交并推送分支，由项目所有者审查
3. **批准后才合并**：获得项目所有者的明确批准后，才把分支合并回 `master`。AI 不得自行合并
4. **合并后删除冗余分支**：本地与远端的特性分支都删除，保持分支列表干净

## 操作序列

```bash
# 1. 从最新 master 开分支
git checkout master && git pull
git checkout -b feature/<短名>

# 2. 修改、测试通过后提交并推送
git add ... && git commit -m "..."
git push -u origin feature/<短名>

# 3. 用户审查并明确批准后，合并并清理
git checkout master && git merge --no-ff feature/<短名> && git push
git branch -d feature/<短名>
git push origin --delete feature/<短名>
```

## 边界情况

- **改动开始后才确立本规则的存量改动**：`git checkout -b` 会带着未提交改动一起切到新分支，把改动落在分支上再提交即可
- **master 与 origin/master 有分叉**：先 `git pull` 对齐再开分支
- **一次会话多个不相关改动**：各自开分支，不混在一个分支里

## 反模式

| 不要做 | 原因 |
|---|---|
| 直接在 `master` 上修改提交 | 绕过审查，master 失去「已批准」的含义 |
| 未获批准自行合并 | 审查批准权在项目所有者 |
| 合并后留下特性分支 | 冗余分支堆积，分支列表失真 |
| 一个分支塞多个不相关改动 | 审查粒度失控 |
