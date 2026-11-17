#!/usr/bin/env python3
"""Publish this repository to GitHub over the REST API (no git, no MCP).

Why: on this machine github.com (git/https) is unreachable, api.github.com is not,
and the GitHub connector has no binary/release tools. This script talks to
api.github.com directly, so it works for text, PNGs and the ~1.2 GB ROM asset.

Usage
  export GITHUB_TOKEN=ghp_xxx            # classic token, scope: repo
  python tools/publish-api.py --check    # verify token, print login, change nothing
  python tools/publish-api.py            # create repo + push every file in one commit
  python tools/publish-api.py --rom /path/to/lineage_polaris-ota.zip
                                        # ... and create v1.0.0 + upload the ROM

Idempotent: an existing repo gets a new commit on its default branch.
Only stdlib is used.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import http.client
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "api.github.com"
UPLOADS = "uploads.github.com"
OWNER_DEFAULT = "wzy2233"
REPO_DEFAULT = "navrs-stacked-recents"
TAG = "v1.0.0"
RELEASE_TITLE = "NavRS v1.0.0 — 预编译 launcher 包（Rust 堆叠后台）"

SKIP_DIRS = {".git", ".idea", ".vscode", "__pycache__", "out", "target"}
SKIP_EXT = {".bak", ".swp", ".pyc"}


def token() -> str:
    tok = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not tok:
        path = os.environ.get("GITHUB_TOKEN_FILE")
        if path and os.path.exists(path):
            tok = open(path, encoding="utf-8").read()
    if not tok:
        sys.exit("请先设置 GITHUB_TOKEN 或 GITHUB_TOKEN_FILE（classic token，勾选 repo 权限）")
    return tok.strip()


def call(method: str, path: str, body=None, tok: str | None = None, ok=(200, 201)):
    """REST call through curl.

    Python's TLS stack gets its connection aborted on multi-hundred-KB bodies on
    this machine (an egress filter), and the HTTP proxy mangles them ("malformed
    request"). curl over a direct connection handles exactly the same payload
    fine, so it is the transport here. Secrets go into a temp curl config file so
    they never appear on the command line.
    """
    import subprocess
    import tempfile

    data = None if body is None else json.dumps(body).encode()
    cfg = tempfile.NamedTemporaryFile("w", suffix=".curlrc", delete=False, encoding="utf-8")
    cfg.write('header = "Authorization: Bearer %s"\n' % (tok or token()))
    cfg.write('header = "Accept: application/vnd.github+json"\n')
    cfg.write('header = "X-GitHub-Api-Version: 2022-11-28"\n')
    cfg.write('header = "User-Agent: navrs-publish"\n')
    if data is not None:
        cfg.write('header = "Content-Type: application/json"\n')
        payload = tempfile.NamedTemporaryFile("wb", suffix=".json", delete=False)
        payload.write(data)
        payload.close()
    cfg.close()
    try:
        args = ["curl", "--noproxy", "*", "-sS", "--max-time", "300", "-K", cfg.name,
                "-X", method, "-w", "\n%{http_code}",
                "https://" + API + path]
        if data is not None:
            args += ["--data-binary", "@" + payload.name]
        last = None
        for attempt in range(4):
            try:
                out = subprocess.run(args, capture_output=True, text=True, timeout=310)
            except subprocess.TimeoutExpired:
                last = "timeout"
            else:
                text = out.stdout.rsplit("\n", 1)
                code = int(text[1]) if len(text) == 2 and text[1].strip().isdigit() else 0
                raw = text[0] if len(text) == 2 else out.stdout
                if code in ok:
                    return code, (json.loads(raw) if raw.strip() else {})
                if code == 0 or code >= 500 or code == 429 or \
                        (code == 400 and "malformed request" in raw):
                    last = "HTTP %s %s" % (code, out.stderr.strip()[:120])
                else:
                    sys.exit("HTTP %s %s %s\n%s" % (code, method, path, raw[:800]))
            time.sleep(1.5 * (attempt + 1))
        sys.exit("network failed for %s %s (%s)" % (method, path, last))
    finally:
        os.unlink(cfg.name)
        if data is not None:
            os.unlink(payload.name)


def collect(root: str):
    files = []
    for base, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in names:
            if os.path.splitext(name)[1] in SKIP_EXT:
                continue
            full = os.path.join(base, name)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            files.append((rel, full))
    return sorted(files)


def ensure_initialized(owner: str, repo: str, root: str, tok: str):
    """A brand-new repo has no ref, and the Git Data API refuses to create blobs
    in a repo with no commits ("Git Repository is empty", 409). Seed the default
    branch through the Contents API first."""
    status, _ = call("GET", "/repos/%s/%s/git/ref/heads/main" % (owner, repo),
                     tok=tok, ok=(200, 404, 409))
    if status == 200:
        return
    seed_name = "README.md"
    seed_path = os.path.join(root, seed_name)
    if not os.path.exists(seed_path):
        seed_name, seed_path = ".gitkeep", None
    payload = {"message": "initialise repository"}
    if seed_path:
        with open(seed_path, "rb") as fh:
            payload["content"] = base64.b64encode(fh.read()).decode()
    else:
        payload["content"] = ""
    print("seeding %s ..." % seed_name)
    call("PUT", "/repos/%s/%s/contents/%s" % (owner, repo, seed_name), payload, tok=tok)


def make_commit(owner: str, repo: str, root: str, message: str):
    files = collect(root)
    print("files: %d" % len(files))
    tree = []
    for rel, full in files:
        with open(full, "rb") as fh:
            payload = base64.b64encode(fh.read()).decode()
        _, blob = call("POST", "/repos/%s/%s/git/blobs" % (owner, repo), {
            "content": payload,
            "encoding": "base64",
        })
        tree.append({"path": rel, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print("  blob %-70s %s" % (rel, blob["sha"][:8]))
        time.sleep(0.2)  # GitHub's edge answers 400 "malformed request" on bursts

    # parent (if the branch already exists) keeps history instead of force-pushing
    parents = []
    status, ref = call("GET", "/repos/%s/%s/git/ref/heads/main" % (owner, repo),
                       ok=(200, 404, 409))
    if status == 200 and isinstance(ref, dict) and ref.get("object"):
        parents = [ref["object"]["sha"]]
        base_tree = None
        _, commit = call("GET", "/repos/%s/%s/git/commits/%s" % (owner, repo, parents[0]))
        base_tree = commit.get("tree", {}).get("sha")
    else:
        base_tree = None

    body = {"tree": tree}
    if base_tree:
        body["base_tree"] = base_tree
    _, new_tree = call("POST", "/repos/%s/%s/git/trees" % (owner, repo), body)

    commit_body = {"message": message, "tree": new_tree["sha"]}
    if parents:
        commit_body["parents"] = parents
    _, new_commit = call("POST", "/repos/%s/%s/git/commits" % (owner, repo), commit_body)

    if parents:
        call("PATCH", "/repos/%s/%s/git/refs/heads/main" % (owner, repo),
             {"sha": new_commit["sha"], "force": True})
    else:
        call("POST", "/repos/%s/%s/git/refs" % (owner, repo),
             {"ref": "refs/heads/main", "sha": new_commit["sha"]})
    print("commit: %s" % new_commit["sha"])
    return new_commit["sha"]


def upload_asset(owner: str, repo: str, release_id: int, path: str, tok: str):
    name = os.path.basename(path)
    size = os.path.getsize(path)
    print("uploading %s (%.2f GB) ..." % (name, size / 1e9))
    ctx = ssl.create_default_context()
    conn = http.client.HTTPSConnection(UPLOADS, timeout=3600, context=ctx)
    conn.putrequest("POST", "/repos/%s/%s/releases/%s/assets?name=%s"
                    % (owner, repo, release_id, urllib.parse.quote(name)))
    conn.putheader("Authorization", "Bearer " + tok)
    conn.putheader("Accept", "application/vnd.github+json")
    conn.putheader("Content-Type", "application/zip")
    conn.putheader("Content-Length", str(size))
    conn.endheaders()
    sent = 0
    with open(path, "rb") as fh:
        while True:
            chunk = fh.read(8 << 20)
            if not chunk:
                break
            conn.send(chunk)
            sent += len(chunk)
            if sent % (200 << 20) < (8 << 20):
                print("  %.0f%%" % (100.0 * sent / size))
    resp = conn.getresponse()
    raw = resp.read().decode("utf-8", "replace")
    conn.close()
    if resp.status not in (200, 201):
        sys.exit("asset upload failed: HTTP %s %s" % (resp.status, raw[:500]))
    data = json.loads(raw)
    print("asset: %s" % data.get("browser_download_url"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("--owner", default=OWNER_DEFAULT)
    ap.add_argument("--repo", default=REPO_DEFAULT)
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--rom", default=None)
    args = ap.parse_args()

    tok = token()
    status, me = call("GET", "/user", tok=tok)
    print("login: %s" % me.get("login"))
    if args.check:
        print("token ok; scopes:", me.get("scopes", "unknown"))
        return

    status, _ = call("GET", "/repos/%s/%s" % (args.owner, args.repo), tok=tok, ok=(200, 404))
    if status == 404:
        print("creating repo %s/%s ..." % (args.owner, args.repo))
        call("POST", "/user/repos", {
            "name": args.repo,
            "description": "纯自研第三方堆叠式最近任务（Rust + AOSP Launcher3）：Rust 负责卡片几何/弹簧/三键渲染，"
                           "Java 只做贴图与生命周期。主体功能完工，暂停维护。",
            "private": False,
            "has_issues": True,
            "has_wiki": False,
        }, tok=tok)
    else:
        print("repo exists")

    ensure_initialized(args.owner, args.repo, args.dir, tok)

    make_commit(args.owner, args.repo, args.dir,
                "NavRS: Rust-powered stacked recents for Android (source + docs)")

    if args.rom:
        body_path = os.path.join(args.dir, "docs", "RELEASE-NOTES.md")
        body = open(body_path, encoding="utf-8").read() if os.path.exists(body_path) else ""
        status, rel = call("GET", "/repos/%s/%s/releases/tags/%s" % (args.owner, args.repo, TAG),
                           tok=tok, ok=(200, 404))
        if status == 404:
            _, rel = call("POST", "/repos/%s/%s/releases" % (args.owner, args.repo), {
                "tag_name": TAG, "name": RELEASE_TITLE, "body": body,
                "draft": False, "prerelease": False,
            }, tok=tok)
        upload_asset(args.owner, args.repo, rel["id"], args.rom, tok)

    print("done: https://github.com/%s/%s" % (args.owner, args.repo))


if __name__ == "__main__":
    main()
