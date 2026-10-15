#!/bin/bash
# RewardHook 手搓编译链：javac -> d8 -> aapt2 -> 打包 -> 签名
# 依赖（可用环境变量覆盖）：ANDROID_JAR / BUILD_TOOLS / R8_JAR
set -e
# 脚本自身目录（工程根），不依赖调用位置
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT"
# SDK 三件套：android.jar + build-tools(含 aapt2/apksigner) + r8.jar
AJ="${ANDROID_JAR:-$ROOT/sdk/android.jar}"
BT="${BUILD_TOOLS:-$ROOT/sdk/build-tools}"
R8="${R8_JAR:-$ROOT/sdk/r8.jar}"

cd "$SRC"
# keystore 放 build 外持久化（build 每次清空；重签会导致签名变化→被迫卸载重装丢 LSPosed 启用态）
mkdir -p .keystore
rm -rf build
mkdir -p build/classes build/dex build/gen

echo "[1/7] aapt2 编译资源 + 生成 R.java"
"$BT/aapt2.exe" compile --dir app/src/main/res -o build/res.zip
"$BT/aapt2.exe" link -o build/base.apk \
  -I "$AJ" \
  --manifest app/src/main/AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0 \
  -A app/src/main/assets \
  --java build/gen \
  build/res.zip

echo "[2/7] javac 编译（stub + 模块源码 + R.java）"
if ! javac -encoding UTF-8 -source 11 -target 11 -nowarn \
  -classpath "$AJ" \
  -d build/classes \
  stub/de/robv/android/xposed/*.java \
  stub/de/robv/android/xposed/callbacks/*.java \
  build/gen/com/piggyhand/fish/R.java \
  app/src/main/java/com/piggyhand/fish/*.java > build/javac.log 2>&1; then
  echo "javac FAILED:"; grep -v "bootstrap" build/javac.log; exit 1
fi
grep -v "bootstrap" build/javac.log || true

echo "[3/7] 打 hook 类 jar（排除 stub）"
jar cf build/hook.jar -C build/classes com

echo "[4/7] d8 转 dex（使用正式版 r8，build-tools 自带 d8 有双层匿名类 NPE bug）"
java -cp "$R8" com.android.tools.r8.D8 --release \
  --lib "$AJ" --min-api 26 \
  --output build/dex build/hook.jar

echo "[5/7] 合并 dex 进 APK"
python - <<'EOF'
import zipfile, shutil, os
src = 'build/base.apk'
dst = 'build/merged.apk'
zin = zipfile.ZipFile(src)
zout = zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED)
for item in zin.infolist():
    zout.writestr(item, zin.read(item.filename))
with open('build/dex/classes.dex','rb') as f:
    data = f.read()
zi = zipfile.ZipInfo('classes.dex')
zi.compress_type = zipfile.ZIP_DEFLATED
zout.writestr(zi, data)
zout.close(); zin.close()
print('merged:', os.path.getsize(dst), 'bytes')
EOF

echo "[6/7] 生成 keystore 并签名"
if [ ! -f .keystore/debug.keystore ]; then
  keytool -genkeypair -keystore .keystore/debug.keystore -alias androiddebugkey \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 \
    -validity 10000 2>/dev/null
fi
java -jar "$BT/lib/apksigner.jar" sign \
  --ks .keystore/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out RewardHook.apk build/merged.apk
java -jar "$BT/lib/apksigner.jar" verify --print-certs RewardHook.apk | head -3
echo "=== DONE: $SRC/RewardHook.apk ($(du -h RewardHook.apk | cut -f1)) ==="
