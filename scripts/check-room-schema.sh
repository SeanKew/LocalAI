#!/usr/bin/env bash
# Room schema 守卫：确认"导出的表结构 JSON 已随代码提交"。
#
# 在构建之后运行（构建会重新导出当前版本的 JSON）。会检查：
#   1. ArchivistDB 的 @Database(version = N) 对应的 app/schemas/<数据库类全名>/N.json 已生成；
#   2. app/schemas 下没有未提交的新增或修改（实体变了却没提交新 JSON，或 JSON 被改动）；
#   3. 1..N 每个版本的 JSON 都已提交（以后写迁移测试需要完整的版本历史）。
#
# 本地也可运行： bash scripts/check-room-schema.sh
set -euo pipefail

DB_FILE="${DB_FILE:-app/src/main/java/com/seankew/localai/memory/ArchivistDB.kt}"
SCHEMA_ROOT="${SCHEMA_ROOT:-app/schemas}"
DB_CLASS="${DB_CLASS:-com.seankew.localai.memory.ArchivistDB}"
SCHEMA_DIR="$SCHEMA_ROOT/$DB_CLASS"

fail() {
  echo "::error::$*" >&2
  exit 1
}

[[ -f "$DB_FILE" ]] || fail "找不到数据库定义文件：$DB_FILE"

# 只在 @Database(...) 注解到 "abstract class" 之间找 version，避免命中注释；注解可以跨行
version="$(
  awk '/@Database\(/{found=1} found{print} /abstract class/{if (found) exit}' "$DB_FILE" \
    | tr '\n' ' ' \
    | grep -oE '(^|[^A-Za-z_])version *= *[0-9]+' \
    | head -n 1 \
    | grep -oE '[0-9]+$' || true
)"
[[ -n "$version" ]] || fail "无法从 $DB_FILE 解析 @Database 的 version"

current="$SCHEMA_DIR/$version.json"
if [[ ! -f "$current" ]]; then
  fail "Room 没有导出 v$version 的 schema（缺少 $current）。请确认 @Database 的 exportSchema = true，并且 app/build.gradle.kts 里配置了 ksp 的 room.schemaLocation。"
fi

# 未提交的新增 / 修改 / 删除
pending="$(git status --porcelain -- "$SCHEMA_ROOT")"
if [[ -n "$pending" ]]; then
  echo "app/schemas 下有未提交的改动：" >&2
  echo "$pending" >&2
  git diff --stat -- "$SCHEMA_ROOT" >&2 || true
  fail "Room schema 与仓库不一致。若是新增 / 修改了实体：升 version、写迁移，并提交构建生成的 $SCHEMA_ROOT。可从本次运行的 LocalAI-Room-Schemas 产物下载后提交。"
fi

# 完整的版本历史都已纳入版本控制
for ((v = 1; v <= version; v++)); do
  file="$SCHEMA_DIR/$v.json"
  git ls-files --error-unmatch -- "$file" > /dev/null 2>&1 \
    || fail "缺少已提交的历史 schema：$file（迁移测试需要每个版本的 JSON）。"
done

echo "Room schema OK：v$version 已导出并已提交（$current）。"
