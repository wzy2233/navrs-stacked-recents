#!/bin/bash
# 把仓库目录推到 GitHub（本地 git 走系统凭据管理器，不打印 token）
# 用法: bash publish.sh [repo-name]
set -e
REPO="${1:-navrs-stacked-recents}"
OWNER="wzy2233"
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

if [ ! -d .git ]; then
  git init -q -b main
  git config user.name "wzy2233"
  git config user.email "wzy2233@users.noreply.github.com"
fi

git add -A
git commit -q -m "NavRS: Rust-powered stacked recents for Android (source + docs)" || echo "nothing to commit"

git remote remove origin 2>/dev/null || true
git remote add origin "https://github.com/$OWNER/$REPO.git"
git push -u origin main

echo "pushed to https://github.com/$OWNER/$REPO"
