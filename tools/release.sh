#!/bin/bash
# 打发布包到 dist/：wotingbudong-<版本>.apk + SHA256SUMS（版本号在 app/build.gradle.kts 的 versionName）
#
#   tools/release.sh              正式包：从最新提交构建，工作区有没提交的改动就拒绝
#   tools/release.sh --snapshot   试打包：按"现在 git add -A 会提交的样子"构建，文件名带 -snapshot
#
# 隔离：源码一律从 git 取（git archive），在临时目录里构建，没进 git 的东西（.private/、local.properties、
# 编译缓存、调试包）到不了包里。签名是唯一例外：keystore.properties 和它指向的密钥库只在构建时
# 拷进临时目录，构建完随临时目录删掉，不进 git、不进产物。没有就用 debug 密钥签（自己装可以，公开发不行）。
# 打完用 tools/privacy_scan.py 扫 APK 里每个文件，有私人信息或密钥就中止、不留产物。
set -eu
cd "$(dirname "$0")/.."
SNAPSHOT=0; [ "${1:-}" = "--snapshot" ] && SNAPSHOT=1
if [ "$SNAPSHOT" = 0 ] && [ -n "$(git status --porcelain)" ]; then
    echo "工作区有没提交的改动，正式包只从提交构建。先提交，或者用 --snapshot 试打包："
    git status --short | head -20
    exit 1
fi
STAGE="$(mktemp -d)"; trap 'rm -rf "$STAGE"' EXIT
if [ "$SNAPSHOT" = 1 ]; then
    IDX="$STAGE/index"   # 临时索引：真正的暂存区不动
    GIT_INDEX_FILE="$IDX" git read-tree HEAD
    GIT_INDEX_FILE="$IDX" git add -A
    REF="$(GIT_INDEX_FILE="$IDX" git write-tree)"
else
    REF=HEAD
fi
SRC="$STAGE/src"; mkdir -p "$SRC"
git archive "$REF" | tar -x -C "$SRC"
VER="$(sed -nE 's/^\s*versionName = "([^"]+)".*/\1/p' "$SRC/app/build.gradle.kts")"
TAG="$VER"; [ "$SNAPSHOT" = 1 ] && TAG="$VER-snapshot"

# 构建环境：SDK 位置、JDK 17（系统 java 25 跑不动 Gradle 8.13），都只是本机设置，不进产物
if [ -z "${ANDROID_HOME:-}" ] && [ -f local.properties ]; then
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' local.properties)"; export ANDROID_HOME
fi
[ -n "${ANDROID_HOME:-}" ] || { echo "找不到 Android SDK：设 ANDROID_HOME 或在 local.properties 里写 sdk.dir"; exit 1; }
if [ -z "${JAVA_HOME:-}" ]; then
    J="$(find ~/.gradle/jdks -type f -name java -path '*/bin/java' 2>/dev/null | head -1)"
    [ -n "$J" ] && { JAVA_HOME="$(dirname "$(dirname "$J")")"; export JAVA_HOME; }
fi

SIGNED="debug 密钥（只适合自己装）"
if [ -f keystore.properties ]; then
    cp keystore.properties "$SRC/"
    STORE="$(sed -n 's/^storeFile=//p' keystore.properties)"
    case "$STORE" in /*) ;; *) mkdir -p "$SRC/$(dirname "$STORE")"; cp "$STORE" "$SRC/$STORE" ;; esac
    SIGNED="keystore.properties 里的正式密钥"
fi

(cd "$SRC" && ./gradlew -q --console=plain lintDebug assembleRelease) || { echo "构建失败（见上）"; exit 1; }
OUT="$STAGE/out"; mkdir -p "$OUT"
cp "$SRC/app/build/outputs/apk/release/app-release.apk" "$OUT/wotingbudong-$TAG.apk"

python3 tools/privacy_scan.py "$OUT" || { echo "成品里有私人信息或密钥（见上），没有输出到 dist/"; exit 1; }
(cd "$OUT" && sha256sum -- * > SHA256SUMS)
rm -rf dist; mkdir -p dist; cp -p "$OUT"/* dist/
echo "dist/（源码：$([ "$SNAPSHOT" = 1 ] && echo "工作区快照" || git rev-parse --short HEAD)，签名：$SIGNED）:"
ls -la dist | awk 'NR>3{printf "  %-40s %s\n", $NF, $5}'
