#!/bin/bash
# 创建 Release 并上传刷机包（需要本机已安装 gh 且 `gh auth login` 过，
# 或者 export GITHUB_TOKEN=<有 repo 权限的 token>）。
#
# 用法：
#   bash publish-release.sh /path/to/lineage_polaris-ota.zip
#
set -e
OWNER="wzy2233"
REPO="navrs-stacked-recents"
TAG="v1.0.0"
ZIP="${1:-}"
NOTES="docs/RELEASE-NOTES.md"

if [ -z "$ZIP" ] || [ ! -f "$ZIP" ]; then
  echo "usage: bash publish-release.sh /path/to/lineage_<device>-ota.zip"
  exit 1
fi

if command -v gh >/dev/null 2>&1; then
  gh release create "$TAG" "$ZIP" \
    --repo "$OWNER/$REPO" \
    --title "NavRS v1.0.0 — Rust 堆叠后台 + LineageOS 22.2 整包（polaris）" \
    --notes-file "$NOTES"
else
  echo "gh 未安装，改用 REST API（需要 GITHUB_TOKEN）"
  : "${GITHUB_TOKEN:?请先 export GITHUB_TOKEN=...}"
  API="https://api.github.com/repos/$OWNER/$REPO/releases"
  BODY=$(python3 - <<'PY'
import json
print(json.dumps({"tag_name":"v1.0.0",
                  "name":"NavRS v1.0.0 — Rust 堆叠后台 + LineageOS 22.2 整包（polaris）",
                  "body":open("docs/RELEASE-NOTES.md",encoding="utf-8").read(),
                  "draft":False,"prerelease":False}))
PY
)
  ID=$(curl -sS -X POST -H "Authorization: Bearer $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github+json" \
        -d "$BODY" "$API" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
  echo "release id=$ID"
  # 大文件用 uploads.github.com，注意 Content-Length 必须由文件本身给出
  NAME=$(basename "$ZIP")
  curl -sS -X POST \
    -H "Authorization: Bearer $GITHUB_TOKEN" \
    -H "Content-Type: application/zip" \
    --data-binary @"$ZIP" \
    "https://uploads.github.com/repos/$OWNER/$REPO/releases/$ID/assets?name=$NAME" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print("asset:",d.get("browser_download_url"))'
fi

echo "done: https://github.com/$OWNER/$REPO/releases/tag/$TAG"
