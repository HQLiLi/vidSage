#!/usr/bin/env bash
# trellis-worktree.sh —— Trellis 并行批次 worktree 仪式脚本
#
# 接口规格（正典：.trellis/spec/guides/dispatch-protocol.md §5.3，协议只锁接口）：
#   tools/trellis-worktree.sh <batch-name>          对该批次 git worktree add + 树内 npm ci
#   tools/trellis-worktree.sh --clean <batch-name>  删除该批次 worktree
#
# 硬约束：
#   - 幂等：重复执行安全（树已存在则跳过 add；依赖指纹未变则跳过全量安装）；
#   - 非交互：任何执行路径不依赖交互输入；
#   - Git Bash 兼容：本项目运行环境为 Windows / Git Bash。
#
# 实现约定（规格未锁定处）：
#   - worktree 落点 = 主仓库 .git/trellis-worktrees/<batch-name>：
#     放在 .git 内 => 永不进 git status，也无需改 .gitignore；
#   - 每批分支 = batch/<batch-name>，基于主仓库当前 HEAD（上游批次须先提交）；
#   - npm 工程根 = 树内 frontend/（本仓库唯一 npm 工程，含 package-lock.json）。

set -euo pipefail

# ---------- 输出工具 ----------

info() { echo "[trellis-worktree] $*"; }
die()  { echo "[trellis-worktree] 错误：$*" >&2; exit 1; }

usage() {
  cat >&2 <<'EOF'
用法:
  tools/trellis-worktree.sh <batch-name>          为批次创建 worktree 并安装依赖（幂等）
  tools/trellis-worktree.sh --clean <batch-name>  删除该批次 worktree（幂等）
EOF
  exit 1
}

# ---------- 入参解析 ----------

MODE="add"
case "${1:-}" in
  --clean)   MODE="clean"; BATCH="${2:-}" ;;
  -h|--help) usage ;;
  "")        usage ;;
  *)         BATCH="$1" ;;
esac

[ -n "$BATCH" ] || usage
# 批次名只允许安全字符，避免路径穿越 / 嵌套目录
echo "$BATCH" | grep -qE '^[A-Za-z0-9._-]+$' \
  || die "批次名 \"$BATCH\" 含非法字符（只允许 A-Za-z0-9._-）"
case "$BATCH" in .|..) die "批次名不能为 . 或 .." ;; esac

# ---------- 定位主仓库根目录 ----------
# 用 --git-common-dir 找主 .git：即使从某个链接 worktree 内调用，也能回到主仓库。
GIT_COMMON_DIR="$(git rev-parse --git-common-dir)" || die "当前不在 git 仓库内"
# 绝对化（git 可能返回相对路径 .git）
case "$GIT_COMMON_DIR" in
  /*|[A-Za-z]:/*) ;;                       # 已是绝对路径
  *) GIT_COMMON_DIR="$(pwd)/$GIT_COMMON_DIR" ;;
esac
MAIN_ROOT="$(dirname "$GIT_COMMON_DIR")"

WORKTREE_PATH="$GIT_COMMON_DIR/trellis-worktrees/$BATCH"
BRANCH="batch/$BATCH"
NPM_SUBDIR="frontend"                        # npm 工程根（树内相对路径）
STAMP_FILE="node_modules/.trellis-deps-stamp"

# 依赖指纹 = package.json + package-lock.json 内容哈希；无 lockfile 输出 no-lockfile
dep_stamp() {
  local tree="$1"
  local pkg="$tree/$NPM_SUBDIR/package.json"
  local lock="$tree/$NPM_SUBDIR/package-lock.json"
  if [ -f "$pkg" ] && [ -f "$lock" ]; then
    ( cd "$tree/$NPM_SUBDIR" && cat package.json package-lock.json | sha256sum | awk '{print $1}' )
  else
    echo "no-lockfile"
  fi
}

# ---------- add 模式：git worktree add + 树内 npm ci（幂等） ----------

do_add() {
  if [ -f "$WORKTREE_PATH/.git" ]; then
    # 链接 worktree 的标志是树内 .git 文件 => 树已存在，跳过 add
    info "worktree 已存在，跳过 add：$WORKTREE_PATH"
  elif [ -e "$WORKTREE_PATH" ]; then
    die "目录已存在但不是注册的 worktree，请人工检查：$WORKTREE_PATH"
  else
    # 目录缺失：先修剪可能的陈旧元数据，保证重跑安全
    git -C "$MAIN_ROOT" worktree prune
    if git -C "$MAIN_ROOT" show-ref --verify --quiet "refs/heads/$BRANCH"; then
      # 批次分支已存在（上次 --clean 后的残留）=> 复用继续本批，不重置基点
      info "分支 $BRANCH 已存在，复用其既有提交继续（不重置到 HEAD）"
      git -C "$MAIN_ROOT" worktree add "$WORKTREE_PATH" "$BRANCH"
    else
      info "创建 worktree：$WORKTREE_PATH（分支 $BRANCH，基于主仓库 HEAD）"
      git -C "$MAIN_ROOT" worktree add -b "$BRANCH" "$WORKTREE_PATH" HEAD
    fi
  fi

  # ---- 树内 npm ci（幂等：依赖指纹未变则跳过全量安装）----
  local pkg="$WORKTREE_PATH/$NPM_SUBDIR/package.json"
  local lock="$WORKTREE_PATH/$NPM_SUBDIR/package-lock.json"
  if [ ! -f "$pkg" ]; then
    info "树内无 $NPM_SUBDIR/package.json，跳过 npm ci"
    return 0
  fi
  [ -f "$lock" ] || die "$NPM_SUBDIR/package-lock.json 缺失（npm ci 必须有锁文件），请先在 $NPM_SUBDIR/ 生成并提交锁文件"

  local want have
  want="$(dep_stamp "$WORKTREE_PATH")"
  have=""
  [ -f "$WORKTREE_PATH/$NPM_SUBDIR/$STAMP_FILE" ] && have="$(cat "$WORKTREE_PATH/$NPM_SUBDIR/$STAMP_FILE")"
  if [ "$want" != "no-lockfile" ] && [ "$want" = "$have" ] \
     && [ -d "$WORKTREE_PATH/$NPM_SUBDIR/node_modules" ]; then
    info "依赖指纹未变（$want），跳过 npm ci"
    return 0
  fi

  info "树内执行 npm ci：$WORKTREE_PATH/$NPM_SUBDIR"
  ( cd "$WORKTREE_PATH/$NPM_SUBDIR" && npm ci )
  printf '%s\n' "$want" > "$WORKTREE_PATH/$NPM_SUBDIR/$STAMP_FILE"
  info "依赖安装完成，指纹已记录：$want"
  info "批次就绪：$WORKTREE_PATH（分支 $BRANCH；批次完成即 commit）"
}

# ---------- clean 模式：删除该批次 worktree（幂等） ----------

do_clean() {
  if [ -f "$WORKTREE_PATH/.git" ]; then
    # 先拒绝脏树：防止误删未提交工作（node_modules 已被 gitignore，不计入脏）
    if [ -n "$(git -C "$WORKTREE_PATH" status --porcelain)" ]; then
      die "worktree 有未提交改动，拒绝删除（先提交或手动处理）：$WORKTREE_PATH"
    fi
    info "移除 worktree：$WORKTREE_PATH"
    if ! git -C "$MAIN_ROOT" worktree remove "$WORKTREE_PATH"; then
      # Windows 下 node_modules 偶发文件占用导致 git 删不动；树已确认干净，删目录 + 修剪元数据
      info "git worktree remove 失败（文件占用？），改用 rm -rf + worktree prune"
      rm -rf "$WORKTREE_PATH"
      git -C "$MAIN_ROOT" worktree prune
    fi
    info "已删除：$WORKTREE_PATH（分支 $BRANCH 保留，合并与删分支由主线处理）"
  elif [ -e "$WORKTREE_PATH" ]; then
    # 目录在但不是注册 worktree（残留壳目录）=> 清目录 + 修剪元数据
    info "目录存在但非注册 worktree，删除目录并修剪元数据：$WORKTREE_PATH"
    rm -rf "$WORKTREE_PATH"
    git -C "$MAIN_ROOT" worktree prune
  else
    # 树已不存在（幂等重跑）=> 仅修剪可能的陈旧元数据
    info "无该批次 worktree（可能已删除）：$BATCH"
    git -C "$MAIN_ROOT" worktree prune
  fi
}

case "$MODE" in
  add)   do_add ;;
  clean) do_clean ;;
esac
