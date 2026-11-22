#!/usr/bin/env bash
# モックアップのページ（公開された HTML）に data URI で埋め込まれた画像と人物の切り抜きを、
# scripts/import-mockup.sh が読む並びのファイルに出力する。
#
# 使い方: scripts/extract-mockup-images.sh <ページの HTML> [出力先のディレクトリ]
#   ページの HTML: Artifact の read で保存された HTML（docs/mockups/editor.template.html の {{IMAGES_JSON}} などを埋めたもの）
#   出力先: 省くと art/mockup/（art/ は .gitignore にある。画像には版権があるので、リポジトリの外か art/ に置く）
#
# 出力先:
#   const IMG  = {名前: data URI}  → <出力先>/<名前>.<拡張子>
#   const INV  = {名前: data URI}  → <出力先>/inv/<名前>.<拡張子>（白が人物以外）
#   const MASK = {名前: data URI}  → <出力先>/mask/<名前>.<拡張子>（白が人物）
set -euo pipefail

HTML="${1:?ページの HTML を指定してください}"
OUT="${2:-art/mockup}"

extract() {
  local var="$1" dir="$2"
  # 1 行に「const <var> = {...};」と書かれている前提。無ければ何もしない（MASK・INV の無いモックアップもある）
  local line
  line="$(grep -m1 -E "^[[:space:]]*const $var = \{" "$HTML" || true)"
  if [ -z "$line" ]; then
    echo "$var はありません"
    return
  fi
  mkdir -p "$dir"
  printf '%s' "$line" | sed -E "s/^[[:space:]]*const $var = //; s/;[[:space:]]*$//" |
    jq -r 'to_entries[] | select(.value | type == "string" and startswith("data:image/")) | "\(.key)\t\(.value)"' |
    while IFS=$'\t' read -r name uri; do
      ext="${uri#data:image/}"
      ext="${ext%%;*}"
      [ "$ext" = "jpeg" ] && ext=jpg
      printf '%s' "${uri#*,}" | base64 -d >"$dir/$name.$ext"
      echo "書き出しました: $dir/$name.$ext"
    done
}

extract IMG "$OUT"
extract INV "$OUT/inv"
extract MASK "$OUT/mask"
