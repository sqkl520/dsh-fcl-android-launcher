#!/bin/sh
# fix-tag-sources.sh —— 让每个 fix tag 指向的源码里 dshFixLabel 等于该 tag 号
#
# 背景：22 个 tag 全部指向「label 比 tag 号大 4」的提交（历史改写遗留）。
# 做法：为每个 tag 建一个新提交——源码树与该 tag 原本指向的提交完全相同，
#       只把 gradle.properties 的 dshFixLabel / dshVersionCode 改成该版本应有的值。
# 这样 tag 与源码自洽，且不动任何代码。
set -eu
MAIN=/d/Projects/dsh-fcl-android-launcher
cd "$MAIN"

# 新编号 → versionCode（实测自线上 APK）
codes="01:107 02:108 03:109 04:110 05:111 06:116 07:117 08:118 09:119 10:120 11:121 12:122 13:123 14:124 15:125 16:126 17:127 18:128 19:129 20:130 21:131 22:132"

for pair in $codes; do
  n=$(echo "$pair" | cut -d: -f1)
  code=$(echo "$pair" | cut -d: -f2)
  tag="v0.1.6-SNAPSHOT-fix$n"
  old=$(git rev-parse "refs/tags/$tag^{commit}")

  # 用临时 index 构造新树：父树 = 原提交的树，只替换 gradle.properties
  export GIT_INDEX_FILE="/tmp/idx-$n"
  rm -f "$GIT_INDEX_FILE"
  git read-tree "$old"
  blob=$(git show "$old:gradle.properties" \
    | sed "s/^dshFixLabel=.*/dshFixLabel=fix$n/; s/^dshVersionCode=.*/dshVersionCode=$code/" \
    | git hash-object -w --stdin)
  git update-index --cacheinfo 100644,"$blob",gradle.properties
  tree=$(git write-tree)
  unset GIT_INDEX_FILE

  # 用原提交的日期，保持提交图的可读性
  d=$(git log -1 --format='%aI' "$old")
  new=$(GIT_AUTHOR_DATE="$d" GIT_COMMITTER_DATE="$d" \
        GIT_AUTHOR_NAME="sqkl520" GIT_AUTHOR_EMAIL="sqkl520@users.noreply.github.com" \
        GIT_COMMITTER_NAME="sqkl520" GIT_COMMITTER_EMAIL="sqkl520@users.noreply.github.com" \
        git commit-tree "$tree" -p "$old" -m "chore: 对齐 fix$n 的源码版本标记

tag $tag 原先指向的提交里 dshFixLabel 写的是更高的版本号（历史改写遗留）。
本提交只改 gradle.properties 的 dshFixLabel/dshVersionCode 两行，
使 tag 与源码自洽。代码零改动。")

  git tag -d "$tag" >/dev/null 2>&1 || true
  GIT_COMMITTER_DATE="2026-10-11T09:$((23 + 10#$n)):00Z" \
  GIT_COMMITTER_EMAIL="sqkl520@users.noreply.github.com" \
  git -c user.email="sqkl520@users.noreply.github.com" tag -a "$tag" "$new" -m "fix$n"

  printf "fix%-3s %s → %s  label=fix%s code=%s\n" "$n" "${old:0:8}" "${new:0:8}" "$n" "$code"
done
echo "== 完成 =="
