# 分支工作流（硬规则）

> 2026-09-24 项目所有者确立；同日引入 `dev` 常驻集成分支（v2，替代仅 master 的 v1）。适用于前后端所有代码与规范改动。

---

## 分支角色

| 分支 | 角色 | 谁能直接提交 | 更新时机 |
|---|---|---|---|
| `master` | 发布线，永远保持可发布 | 无人 | 仅用户明确要求发布时 `dev → master` |
| `dev` | **常驻集成分支**，日常工作的基准 | 无人 | feature 分支获用户批准后合并进入 |
| `feature/<短名>` | 承载一次修改 | 开发期间自由提交 | — |

## 规则

1. **先开分支再修改**：任何修改动手前，从最新 `dev` 拉出特性分支（`feature/<短名>`），全程在分支上改。禁止直接在 `dev` 或 `master` 上修改
2. **修改结束 → 推送 → 用户审查**：测试通过后提交并推送分支，由项目所有者审查
3. **批准后才合并**：获得项目所有者的明确批准后，才把分支合并回 `dev`。AI 不得自行合并
4. **合并后删除冗余分支**：本地与远端的特性分支都删除，保持分支列表干净
5. **发布**：`dev → master` 仅在用户明确要求发布时执行，AI 不得自行发布合并

## 操作序列

```bash
# 1. 从最新 dev 开分支
git checkout dev && git pull
git checkout -b feature/<短名>

# 2. 修改、测试通过后提交并推送
git add ... && git commit -m "..."
git push -u origin feature/<短名>

# 3. 用户审查并明确批准后，合并回 dev 并清理
git checkout dev && git pull
git merge --no-ff feature/<短名> && git push
git branch -d feature/<短名>
git push origin --delete feature/<短名>

# 4. 仅当用户明确要求发布时
git checkout master && git pull
git merge --no-ff dev && git push
git checkout dev
```

## 边界情况

- **改动开始后才拉分支的存量改动**：`git checkout -b` 会带着未提交改动一起切到新分支，把改动落在分支上再提交即可
- **开发期间 dev 前进了**：合并前 `git checkout dev && git pull`，再在 feature 分支上 `git merge dev`（或 rebase），冲突在分支上解决后再推送
- **dev 与 origin/dev 有分叉**：先 `git pull` 对齐再开分支
- **一次会话多个不相关改动**：各自开分支，不混在一个分支里
- **v1 时期的在途分支**（以 master 为基准开的）：合并目标按本版追溯为 `dev`

## 反模式

| 不要做 | 原因 |
|---|---|
| 直接在 `dev` / `master` 上修改提交 | 绕过审查，集成分支失去「已批准」的含义 |
| 未获批准自行合并（feature→dev 或 dev→master） | 审查批准权与发布时机都归项目所有者 |
| 合并后留下 feature 分支 | 冗余分支堆积，分支列表失真 |
| 一个分支塞多个不相关改动 | 审查粒度失控 |
| 未经用户要求执行 `dev → master` | 发布由项目所有者决定 |
