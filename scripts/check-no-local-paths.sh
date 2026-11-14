#!/usr/bin/env bash
# 检查受 Git 跟踪及未忽略的新文件中是否出现本机绝对路径。
# 用法：scripts/check-no-local-paths.sh
# 退出码：0 干净；1 发现可疑路径。
#
# 被 gitignore 的 .env.local、local.properties、.toolchain/ 等本机文件不在检查范围内；
# 这是有意为之：本机路径只允许存在于这些不提交的文件中。

set -u

_moyue_root="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$_moyue_root" || exit 1

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    printf '当前目录不是 Git 工作区：%s\n' "$_moyue_root" >&2
    exit 1
fi

# 用字符类拆开敏感词，避免脚本自身命中这些模式。
_moyue_pattern='/User[s]/|/hom[e]/[a-z]+/|/Syste[m]/|C:[\\]'

if git grep --untracked -n -I -E "$_moyue_pattern"; then
    printf '\n发现本机绝对路径。请移到 .env.local（已 gitignore），并从受跟踪文件中删除。\n' >&2
    exit 1
fi

exit 0
