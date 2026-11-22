#!/bin/sh
# 离线单测跑法（在本沙箱里没有 Android 设备、也不能让 Gradle 跑 unitTest 的情况下使用）。
#
# 原理：主代码已由 Gradle 编译到 FCL/build/tmp/kotlin-classes/debug，
# 这里只把 **测试源码** 用 kotlin-compiler-embeddable 直接编译，再用一个极小的
# JUnit 桩 + MiniRunner（反射调用 @Test 方法）跑起来。
#
# 用法: sh run-tests.sh（源码目录自动识别，可用 SRC=<路径> 覆盖）
#   跑两批：
#     ① Kotlin 单测（MiniRunner 反射跑 @Test）—— 依赖 run-compile.sh 的主代码产物
#     ② 运行时脚本自检（dsh-launcher-poc/scripts/test-scripts-posix.sh）—— 用真 dash 跑
#        `assets/dsh/scripts/*.sh`，防"shebang 与实际调用方式不一致"这类回归。
#        ⚠️ 这批同时验证脚本是 **POSIX sh 且在电脑/手机两种布局下都能打包**，别漏跑。
set -u

# 本仓库根 = 脚本所在目录的上一级（脚本已移入 tools/，不再硬编码 /workspace）
EA=$(cd "$(dirname "$0")/.." && pwd)
# 源码目录自动识别：电脑=本目录；手机=子目录 dsh-fcl-android-launcher
if [ -z "${SRC:-}" ]; then if [ -f "$EA/settings.gradle.kts" ]; then WS="$EA"; else WS="$EA/dsh-fcl-android-launcher"; fi; else WS="$SRC"; fi
[ -d "$WS/FCL" ] || { echo "!! 找不到源码仓库：$WS（用 SRC=<路径> 指定）"; exit 1; }
HARNESS=/tmp/dsh-test-harness
OUT="$HARNESS/out"
TESTOUT="$HARNESS/testout"
MOD="$WS/FCL"
# 测试源码目录：默认 <模块>/src/test/java/com/dsh；TEST_SRC=<路径> 可覆盖。
# 用途：并发改主代码时，用它只跑"自己这摊"的测试子集（把 *.kt 拷到临时目录再指定），
# 免得别人的在途测试把整批编译带红。跑全量时**不设**这个变量，行为与以前完全一致。
TEST_SRC="${TEST_SRC:-$MOD/src/test/java/com/dsh}"
[ -d "$TEST_SRC" ] || { echo "!! 测试源码目录不存在：$TEST_SRC"; exit 1; }
# Gradle 依赖缓存：手机沙箱在 /root/.gradle，电脑在用户目录下 —— 两处都试
if [ -d /root/.gradle/caches/modules-2/files-2.1 ]; then
  CACHE=/root/.gradle/caches/modules-2/files-2.1
else
  CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
fi

jar1() { find "$1" -name "$2" 2>/dev/null | grep -v sources | head -1; }

STDLIB=$(jar1 "$CACHE/org.jetbrains.kotlin/kotlin-stdlib/2.4.10" "kotlin-stdlib-2.4.10.jar")
REFLECT=$(jar1 "$CACHE/org.jetbrains.kotlin/kotlin-reflect/2.0.21" "kotlin-reflect-2.0.21.jar")
COMPILER=$(jar1 "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.10" "kotlin-compiler-embeddable-2.4.10.jar")
COROUT=$(jar1 "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "kotlinx-coroutines-core-jvm-*.jar")
GSON=$(jar1 "$CACHE/com.google.code.gson/gson/2.11.0" "gson-2.11.0.jar")
ANNOT=$(jar1 "$CACHE/org.jetbrains/annotations/23.0.0" "annotations-23.0.0.jar")
ANDROID_JAR=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}/platforms/android-35/android.jar
# 电脑上 SDK 路径写在仓库根 local.properties 的 sdk.dir（该文件不进 Git），也认一下
if [ ! -f "$ANDROID_JAR" ] && [ -f "$WS/local.properties" ]; then
  SDK_DIR=$(sed -n 's/^sdk\.dir=//p' "$WS/local.properties" | tr -d '\r' | head -1)
  [ -n "$SDK_DIR" ] && ANDROID_JAR="$SDK_DIR/platforms/android-35/android.jar"
fi
# ★ 路径归一（本次修 TC-03 时实测出来的坑，不修会让「补 androidx」时好时坏）：
#   `ANDROID_SDK_ROOT` / `local.properties` 给的是 **Windows 形态**（`D:\Android\Sdk`），
#   而 `CP` 用 `:` 当分隔符 —— JVM/kotlinc 看到 `...:D:\Android\Sdk\platforms\...` 会按 `:`
#   切成两段（`D` 和 `\Android\Sdk\...`），android.jar 其实**没上 classpath**。
#   症状极其误导：`warning: classpath entry points to a non-existent location: D`，
#   然后 `unresolved reference 'android'` —— 看起来像"源码写错了"，其实是路径被拆了。
#   为什么以前没炸：`\Android\Sdk\...` 是**盘符相对**路径，cwd 在 D: 盘时它恰好解析回
#   `D:\Android\Sdk\...`，所以"在仓库根跑"一直是好的、换个 cwd 就红。
#   用 cygpath 转成 POSIX 形态（`/d/Android/Sdk/...`）后不再含 `:`，与 CP 的分隔符不冲突。
#   cygpath 只在 MSYS/Cygwin 存在，手机沙箱（真 POSIX）没有 → 用 command -v 守住。
if command -v cygpath >/dev/null 2>&1; then
  ANDROID_JAR=$(cygpath -u "$ANDROID_JAR")
fi
MAIN_KT="$MOD/build/tmp/kotlin-classes/debug"
MAIN_JAVA="$MOD/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
RJAR="$MOD/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar"

# --- androidx 运行时 jar（TC-03 / RV220-1）------------------------------------
# 为什么必须有：测试源码里 `R31Fix221Test` 反射 `Class.forName("...DshUIManager$init$1")`，
# 而那个匿名类**继承** `ViewPager2$OnPageChangeCallback` —— JVM 定义子类时必须先加载父类，
# 于是测试在**运行时**需要 androidx。原来的 `CP` 一个 androidx 都没有（`grep -c androidx` = 0），
# 导致全量固定 `FAILED=5`（全是 `NoClassDefFoundError: androidx/viewpager2/widget/ViewPager2`），
# 把每个 Agent 的验收基线都污染成「有 5 条红的」—— 谁都得额外论证「这 5 条不是我的」。
#
# ★ aar 不是 jar：Gradle 缓存里 viewpager2 / recyclerview / core / customview 全是 **.aar**，
#   真正的类在 aar 内的 `classes.jar` 里。所以这里必须解包，不能把 .aar 直接塞进 -cp
#   （塞进去只会得到 "classpath entry points to a non-existent location" 一类噪音，
#   测试仍然是 NoClassDefFoundError）。解包产物放 $HARNESS/ax/（临时目录，不进仓库）。
#
# ★ 为什么是这 5 个：viewpager2 的字节码里引用了 recyclerview / customview / core / collection
#   的符号（`javap` 实测），少一个就会在类加载或首次调用时 NoClassDefFound。
#   实测最小集只要 viewpager2 就能让当前全量变绿，但只补一个等于把坑留给下一个写 UI 测试的人。
#
# ★ 顺序：AXJARS 拼在 CP 的**末尾**（在 android.jar 之后）。androidx 的 core 里带
#   `android/support/v4/**` 兼容类，而 android.jar 里一个都没有，所以不会被它 shadow；
#   这 5 个 jar 里也**没有** `java/**` / `javax/**`，不会重演 TC-01 那种 shadow java.base 的事故。
# ★ 解包产物放 $HARNESS/ax/（临时目录，不进仓库），并且**按内容缓存、原子发布**：
#   本仓常有多个 Agent 并发跑这个脚本（共用 /tmp/dsh-test-harness），第一版写成
#   `rm -rf $AXDIR; mkdir -p $AXDIR; unzip > $AXDIR/x.jar` —— 实测被兄弟进程的 rm 撞掉，
#   报 `line 102: /tmp/dsh-test-harness/ax/customview-1.1.0.jar: No such file or directory`
#   然后整个全量中止。现在改成「先写 <out>.tmp.$$ 再 mv 过去」：mv 是原子的，读方
#   要么看到完整文件、要么看不到；两个进程同时解包也只是重复写同一份内容，结果恒等。
AXDIR="$HARNESS/ax"
mkdir -p "$AXDIR"
# axjar <Gradle 组目录名> <文件名通配>：echo 出一个可上 classpath 的 jar 路径
axjar() {
  # sort -V 取最高版本（缓存里可能有多版本）。手机上的 BusyBox sort 不一定支持 -V。
  # ★ 先把候选收进变量再排序，不写成 `find ... | { sort -V || sort; }`：后者的第二个 sort
  #   要从**同一个已流走一半的 stdin** 继续读 —— 若第一个 sort 在报错前已经消费了输入，
  #   回退就悄悄少掉几行（"取最高版本"变成"取部分候选里最高的"）。变量形态没有这个风险。
  _cands=$(find "$CACHE/$1" -name "$2" 2>/dev/null | grep -v sources)
  f=$(printf '%s\n' "$_cands" | sort -V 2>/dev/null | tail -1)
  [ -n "$f" ] || f=$(printf '%s\n' "$_cands" | sort | tail -1)   # 无 sort -V 时退回字典序
  if [ -z "$f" ]; then
    echo "!! 缺少 androidx 依赖：$CACHE/$1/$2" >&2
    echo "   （Gradle 缓存里没有；先跑一次会解析该依赖的 Gradle 任务，如 :FCL:compileDebugKotlin）" >&2
    return 1
  fi
  case "$f" in
    *.jar) echo "$f" ;;
    *.aar)
      out="$AXDIR/$(basename "$f" .aar).jar"
      if [ ! -s "$out" ]; then   # 已有完整解包产物就直接复用（并发时后到者省一次解包）
        tmp="$out.tmp.$$"
        if command -v unzip >/dev/null 2>&1; then
          unzip -p "$f" classes.jar > "$tmp" 2>/dev/null
        else
          # 没有 unzip 时用 JDK 自带 jar（Windows 沙箱里常见）
          rm -rf "$tmp.d"; mkdir -p "$tmp.d" && (cd "$tmp.d" && jar xf "$f" classes.jar) \
            && mv -f "$tmp.d/classes.jar" "$tmp"
        fi
        if [ ! -s "$tmp" ]; then
          rm -f "$tmp"
          echo "!! aar 解包失败：$f 里没有 classes.jar（或 unzip/jar 都不可用）" >&2
          return 1
        fi
        mv -f "$tmp" "$out" || return 1
      fi
      echo "$out"
      ;;
  esac
}
AXJARS=""
# 缺哪些 androidx 构件（见下方循环）。必须显式初始化：脚本开了 set -u，
# 第一次展开 "${AXMISSING}" 会直接报 unbound variable 并中止。
AXMISSING=""
# ★ 逐个显式两参数调用，不写成 `for spec in "a b"` 再 `axjar $spec`：
#   未加引号的 $spec 会再走一遍**通配符展开**，仓库根若恰好有 `viewpager2-1.1.0.aar`
#   这类文件，参数就会被本地文件替换掉 —— 依赖查找静默指向错误目标，是典型的「看起来配了、其实没配」。
for _ax in \
  "androidx.viewpager2|viewpager2-*.aar" \
  "androidx.recyclerview|recyclerview-*.aar" \
  "androidx.customview|customview-[0-9]*.aar" \
  "androidx.core|core-[0-9]*.aar" \
  "androidx.collection|collection-[0-9]*.jar"
do
  # ★ 降级而不是中止（verify-r31-239 的 N2）：缺 androidx 时整轮 exit 1，会让
  #   连**不需要** androidx 的 760+ 条测试也跑不了 —— 比修复前（照跑、只是那 5 条红）
  #   可用性更差。这里改成"少一个就少一个"，把缺的记下来，让需要 androidx 的那几条
  #   按原本的 NoClassDefFoundError 变红，其余照常跑；跑完再汇总提示一次。
  if j=$(axjar "${_ax%%|*}" "${_ax#*|}"); then
    AXJARS="${AXJARS:+$AXJARS:}$j"
  else
    AXMISSING="${AXMISSING}${AXMISSING:+, }${_ax%%|*}"
  fi
done
if [ -n "$AXMISSING" ]; then
  echo "!! androidx 依赖不完整，缺：$AXMISSING" >&2
  echo "   依赖 androidx 的测试会以 NoClassDefFoundError 变红，其余测试照常跑。" >&2
  echo "   补齐办法：先跑一次 :FCL:compileDebugKotlin 让 Gradle 把这些依赖下载到缓存。" >&2
fi

for f in "$STDLIB" "$REFLECT" "$COMPILER" "$COROUT" "$GSON" "$ANNOT" "$ANDROID_JAR" "$RJAR"; do
  if [ ! -f "$f" ]; then echo "缺少依赖: $f"; exit 1; fi
done
# AXJARS 每个分量都由 axjar 当场校验过存在（找不到 / 解不出就 return 1 → exit），
# 所以这里不再拆开复查 —— 拆开就得按 ':' 切路径，遇到含空格的 HOME 反而会误判。
# ★ 把这 5 个 jar 打出来：本脚本历史上最大的坑就是"以为 CP 里有、其实没有"（TC-03）。
#   不打印的话，下次再出现 androidx 类加载失败时，没人能一眼确认是"没配"还是"配错了"。
echo "== androidx 运行时 jar（aar 已解包 classes.jar）=="
echo "$AXJARS" | tr ':' '\n' | sed 's/^/   /'
if [ ! -d "$MAIN_KT" ]; then echo "缺少主代码产物 $MAIN_KT（先跑 run-compile.sh）"; exit 1; fi

# --- 产物新鲜度检查（R24 / E5-6）---------------------------------------------
# 为什么必须有这一步：本脚本用的是 **debug** 产物，而 build-apk.sh / release.sh 只编 **release**。
# 改了 Kotlin 之后直接跑本脚本，会拿**旧的** class 去编译/执行测试 —— 症状有两种：
#   ① 新符号找不到 → "测试编译失败"（看起来像环境坏了）；
#   ② 更糟：符号没变、行为变了 → 测试**静默用旧实现跑绿**，给出假的安全感。
# 本轮就踩过 ①（改完 release 编译后脚本报 unresolved reference）。
# 判据：任何一个 .kt/.java 源文件比**主代码产物里最新的 .class** 还新 ⇒ 产物过期。
# ★ 不能拿源文件和产物**目录**的 mtime 比：目录 mtime 只在增删文件时更新，改内容不更新，
#   那样会把一个刚编译完的产物判成"过期"（本轮实测误报，所有源文件都被列为更新）。
# ★ TC-04：必须同时看 MAIN_KT（Kotlin 产物）与 MAIN_JAVA（javac 产物）。
#   只看 MAIN_KT 时，纯 Java 改动会拿到两种错结果：
#     · 若 Java 产物比 Kotlin 产物新 → 拿 .java 源的 mtime 去比 Kotlin 产物的 mtime，
#       于是一个「刚编译完」的状态被误判成过期（实测误报）；
#     · 反过来更糟：改了 .java 但没重编，守卫拿 Kotlin 产物做基准，可能反而判"不过期"，
#       测试静默用旧的 Java class 跑绿 —— 那正是这条守卫要防的事。
#
# 实现说明（性能与可移植性，**四次**踩坑后才定型）：
#   · 不用 `find -printf %T@` —— GNU 扩展，macOS/BSD 的 find 不支持（R25/E5-07）。
#   · 不用「逐文件 stat 再取最大值」—— 主代码 class 上万个，逐次 fork stat 要跑几分钟。
#   · 不用「目录 mtime」当基准 —— **实测证明它不可靠**：Gradle 的
#     `compileDebugKotlin`/`compileDebugJavaWithJavac` 是**原地覆盖** .class，
#     父目录 mtime 不更新。实测目录 mtime = 05:45:15，而目录内 class 的 mtime = 06:24:39，
#     相差 39 分钟 —— 拿目录当基准会把刚编译完的状态误判成"过期"（`fix-r31-250` 与主 Agent
#     各踩一次）。这一条推翻了我先前写在注释里的"目录 mtime 更稳"的错误判断。
#   · 最终方案：**两级**。
#     ① 快速预检：拿两个产物目录中较新的那个当基准跑一次 `find -newer`（毫秒级）。
#        没有源文件比它新 ⇒ **一定**不过期（因为真实 class 只会比目录更新），直接放行。
#     ② 慢速精查：只有预检发现"有源文件比目录新"时，才迭代找真正最新的 .class 再比一次。
#        迭代是必要的：单轮 `find -newer` 只能找到"比基准新的某一个"，不是最新的那个。
#     正常情况（刚编译完）走 ①，5 秒；可疑时才付 ②，20~40 秒。
#     **宁可慢，不可漏** —— 漏报会让测试静默跑旧 class，那是这条守卫存在的全部理由。
BASE="$MAIN_KT"
if [ -d "$MAIN_JAVA" ] && [ -d "$MAIN_KT" ]; then
  # 取两者中较新的目录（`-d A -newer B` 为真表示 A 比 B 新）
  if [ -n "$(find "$MAIN_JAVA" -maxdepth 0 -newer "$MAIN_KT" 2>/dev/null)" ]; then
    BASE="$MAIN_JAVA"
  fi
elif [ -d "$MAIN_JAVA" ]; then
  BASE="$MAIN_JAVA"
fi
if [ ! -d "$MAIN_KT" ] || [ ! -d "$MAIN_JAVA" ]; then
  # ★ R25/E5-07：守卫本身不能静默失效 —— 明确报错而不是让检查整个跳过。
  echo "!! 主代码产物目录缺失："
  echo "     $MAIN_KT   : $([ -d "$MAIN_KT" ] && echo 有 || echo 无)"
  echo "     $MAIN_JAVA : $([ -d "$MAIN_JAVA" ] && echo 有 || echo 无)"
  echo "   请先编译 debug 产物： ./gradlew :FCL:compileDebugKotlin :FCL:compileDebugJavaWithJavac"
  exit 1
fi
# ① 快速预检。注意：比对基准用目录，但**被比对的是源文件**，方向是安全的
#   （真实 class 比目录新 ⇒ 源文件比目录新是"过期"的必要条件）。
QUICK=$(find "$MOD/src/main/java" \( -name '*.kt' -o -name '*.java' \) -type f \
  -newer "$BASE" 2>/dev/null | head -20)
if [ -z "$QUICK" ]; then
  NEWER=""      # 没有任何源文件比产物目录新 ⇒ 必然不过期
else
  # ② 慢速精查：迭代找两个产物目录里真正最新的 .class。
  NEWEST="$BASE"
  for _d in "$MAIN_KT" "$MAIN_JAVA"; do
    for _i in 1 2 3 4 5 6 7 8; do
      _n=$(find "$_d" -name '*.class' -type f -newer "$NEWEST" 2>/dev/null | head -1)
      [ -z "$_n" ] && break
      NEWEST="$_n"
    done
  done
  NEWER=$(find "$MOD/src/main/java" \( -name '*.kt' -o -name '*.java' \) -type f \
    -newer "$NEWEST" 2>/dev/null | head -5)
fi
if [ -n "$NEWER" ]; then
  # ★ 内容哈希复核（修假红）：mtime 报警**不等于**产物真的过期。
  #   Gradle 按**内容哈希**判 up-to-date，本脚本按 **mtime** 判 —— 两者语义不同，于是
  #   只要源文件内容没变而 mtime 变新（`touch` / `git stash pop` / 编辑器保存相同内容 /
  #   checkout 同一内容），Gradle 就不重编译，而这里会误报"产物已过期"。
  #   本轮实测踩过：一度被读成"测试 flaky"，浪费了一轮排查。
  #   所以 mtime 报警后，先比一次**源码内容哈希**：
  #     · 与编译时写的戳一致 ⇒ 内容没变、产物就是最新的 ⇒ 放行（不是漏报，是纠正误报）
  #     · 不一致 / 戳不存在 ⇒ 真的改了（或无法确定）⇒ 照旧拦截（宁可慢，不可漏）
  # 戳由 run-compile.sh 在 Gradle **成功之后**写入，口径与这里必须完全一致。
  STAMP="$MAIN_KT/.source-hash"
  HASH_OK=0
  if command -v sha256sum > /dev/null 2>&1 && [ -f "$STAMP" ]; then
    NOW_HASH=$(cd "$WS" && find FCL/src/main/java \( -name '*.kt' -o -name '*.java' \) -type f \
        -print | LC_ALL=C sort | xargs sha256sum 2>/dev/null | sha256sum 2>/dev/null | awk '{print $1}')
    OLD_HASH=$(cat "$STAMP" 2>/dev/null)
    if [ -n "$NOW_HASH" ] && [ "$NOW_HASH" = "$OLD_HASH" ]; then HASH_OK=1; fi
  fi
  if [ "$HASH_OK" = "1" ]; then
    echo "== 注意：有源文件 mtime 比 .class 新，但**内容哈希与编译时一致** ⇒ 产物并未过期 =="
    echo "   （mtime 变动而内容没变：touch / git stash pop / 保存相同内容。已放行，不重编译）"
  else
    echo "!! 主代码产物已过期（以下源文件比最新 .class 还新）："
    echo "$NEWER" | sed 's/^/     /'
    echo "   请先编译 debug 产物再跑测试："
    echo "     ./gradlew :FCL:compileDebugKotlin"
    echo "   （否则测试可能拿旧 class 静默跑绿 —— 见 R24/E5-6）"
    # 有哈希戳但不等 ⇒ 明说"内容确实变了"，避免与"只是 mtime 变了"混淆
    if [ -f "$STAMP" ]; then
      echo "   （源码内容哈希已变化：$(cat "$STAMP" 2>/dev/null) -> ${NOW_HASH:-<算不出>}）"
    else
      echo "   （没有源码哈希戳 —— 先跑一次 run-compile.sh 就会生成）"
    fi
    exit 1
  fi
fi


rm -rf "$OUT" "$TESTOUT"
mkdir -p "$OUT" "$TESTOUT"

# --- 1) JUnit 桩 + MiniRunner -------------------------------------------------
# 桩要跟着测试源码用到的 API 长。缺一个方法，症状是"测试编译失败"而不是"测试失败" ——
# 看起来像环境坏了，其实是桩不够用。所以新测试用到什么就补什么，别去改测试迁就桩。
mkdir -p "$OUT/org/junit"
cat > "$OUT/org/junit/Test.java" <<'EOF'
package org.junit;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Test {}
EOF
cat > "$OUT/org/junit/Before.java" <<'EOF'
package org.junit;
import java.lang.annotation.*;
/** 每个 @Test 方法**之前**执行一次（MiniRunner 负责调用，见那边） */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Before {}
EOF
cat > "$OUT/org/junit/After.java" <<'EOF'
package org.junit;
import java.lang.annotation.*;
/** 每个 @Test 方法**之后**执行一次（MiniRunner 负责调用；用例体抛异常时也要跑） */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface After {}
EOF
cat > "$OUT/org/junit/Assert.java" <<'EOF'
package org.junit;
import java.util.Objects;
public class Assert {
    public static void assertTrue(boolean c) { if (!c) throw new AssertionError("expected true"); }
    public static void assertTrue(String m, boolean c) { if (!c) throw new AssertionError(m); }
    public static void assertFalse(boolean c) { if (c) throw new AssertionError("expected false"); }
    public static void assertFalse(String m, boolean c) { if (c) throw new AssertionError(m); }
    public static void assertNull(Object o) { if (o != null) throw new AssertionError("expected null but was <" + o + ">"); }
    public static void assertNull(String m, Object o) { if (o != null) throw new AssertionError(m + " expected null but was <" + o + ">"); }
    public static void assertNotNull(Object o) { if (o == null) throw new AssertionError("expected not null"); }
    public static void assertNotNull(String m, Object o) { if (o == null) throw new AssertionError(m); }
    /** 引用相等（断言"同一个对象"）。assertSame 与 assertEquals 的区别在测试里是有意义的：
     *  "同一个 StateFlow 实例"必须用引用相等，equals 相等会被 equals 的实现糊弄过去。 */
    public static void assertSame(Object e, Object a) { assertSame(null, e, a); }
    public static void assertSame(String m, Object e, Object a) {
        if (e != a) throw new AssertionError((m == null ? "" : m + " ") + "expected same instance <" + e + "> but was <" + a + ">");
    }
    public static void assertNotSame(Object e, Object a) { assertNotSame(null, e, a); }
    public static void assertNotSame(String m, Object e, Object a) {
        if (e == a) throw new AssertionError((m == null ? "" : m + " ") + "expected different instances but both were <" + e + ">");
    }
    /** 引用/值不等（assertNotEquals 的语义）。fix10 的 BL-202 用例需要它：断言两种标记在
     *  日志里必须可区分 —— 那条断言是"脱敏不得抹平留痕"的守卫，缺了它整批测试都编译不过。 */
    public static void assertNotEquals(Object e, Object a) { assertNotEquals(null, e, a); }
    public static void assertNotEquals(String m, Object e, Object a) {
        if (Objects.equals(e, a)) throw new AssertionError((m == null ? "" : m + " ") + "expected different values but both were <" + a + ">");
    }
    public static void assertNotEquals(long e, long a) { assertNotEquals(null, e, a); }
    public static void assertNotEquals(String m, long e, long a) {
        if (e == a) throw new AssertionError((m == null ? "" : m + " ") + "expected different values but both were <" + a + ">");
    }
    public static void fail(String m) { throw new AssertionError(m); }
    public static void fail() { throw new AssertionError("fail() called"); }
    public static void assertEquals(Object e, Object a) { assertEquals(null, e, a); }
    public static void assertEquals(String m, Object e, Object a) {
        if (!Objects.equals(e, a)) throw new AssertionError((m == null ? "" : m + " ") + "expected <" + e + "> but was <" + a + ">");
    }
    public static void assertEquals(long e, long a) { assertEquals(null, e, a); }
    public static void assertEquals(String m, long e, long a) {
        if (e != a) throw new AssertionError((m == null ? "" : m + " ") + "expected <" + e + "> but was <" + a + ">");
    }
    public static void assertEquals(double e, double a, double d) {
        if (Math.abs(e - a) > d) throw new AssertionError("expected <" + e + "> but was <" + a + ">");
    }
}
EOF
cat > "$OUT/MiniRunner.java" <<'EOF'
import java.lang.reflect.*;

/** 极简 JUnit 替代：实例化测试类，反射调用所有 @Test 方法（每个用例前后各跑一次 @Before/@After） */
public class MiniRunner {
    public static void main(String[] args) throws Exception {
        int total = 0, failed = 0;
        for (String cn : args) {
            Class<?> c = Class.forName(cn);
            // 收集 @Before/@After：每个用例都要用**新实例**跑一次，不能跨用例共享（那会让用例之间互相影响）
            java.util.List<Method> befores = new java.util.ArrayList<>();
            java.util.List<Method> afters = new java.util.ArrayList<>();
            for (Method m : c.getDeclaredMethods()) {
                if (m.getAnnotation(org.junit.Before.class) != null) befores.add(m);
                if (m.getAnnotation(org.junit.After.class) != null) afters.add(m);
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.getAnnotation(org.junit.Test.class) == null) continue;
                total++;
                Object inst = null;
                Throwable failure = null;
                try {
                    inst = c.getDeclaredConstructor().newInstance();
                    for (Method b : befores) { b.setAccessible(true); b.invoke(inst); }
                    m.setAccessible(true);
                    m.invoke(inst);
                } catch (InvocationTargetException e) {
                    failure = e.getTargetException();
                } catch (Throwable t) {
                    // 构造/实例化失败（含 @Before 抛异常）也要走 @After，与 JUnit 语义一致
                    failure = t;
                }
                // ★ @After 无条件执行（用例体成败都跑），语义与 JUnit 相同：
                //   seam 测试靠它清理注入的 fake / 复活全局状态，漏跑会让**下一条**用例莫名变红。
                //   单条 @After 抛异常不许覆盖原异常（否则真正的失败原因被清理噪音盖掉）。
                if (inst != null) {
                    for (Method a : afters) {
                        try {
                            a.setAccessible(true);
                            a.invoke(inst);
                        } catch (InvocationTargetException e) {
                            if (failure == null) failure = e.getTargetException();
                            else System.out.println("  (@After threw, original failure kept: " + e.getTargetException() + ")");
                        } catch (Throwable t) {
                            if (failure == null) failure = t;
                            else System.out.println("  (@After threw, original failure kept: " + t + ")");
                        }
                    }
                }
                if (failure == null) {
                    System.out.println("PASS  " + cn + "." + m.getName());
                } else {
                    failed++;
                    System.out.println("FAIL  " + cn + "." + m.getName() + "  -> " + failure);
                    failure.printStackTrace(System.out);
                }
            }
        }
        System.out.println("---------------------------------------------");
        System.out.println("TOTAL=" + total + "  FAILED=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
EOF
# ★ -encoding UTF-8：桩文件里有中文注释；Windows 的 javac 默认按 GBK 读源码，
#   会把注释里的 UTF-8 汉字判成非法字符直接编译失败（Linux 上默认就是 UTF-8 所以看不出来）。
# ★ 桩文件要**逐个列全**：漏一个（例如新加的 Before.java/After.java）不会报"少文件"，
#   而是让引用它的 MiniRunner 报"找不到符号"，看起来像源码写错了。
javac -nowarn -encoding UTF-8 -d "$OUT" \
  "$OUT/org/junit/Test.java" "$OUT/org/junit/Before.java" "$OUT/org/junit/After.java" "$OUT/org/junit/Assert.java" \
  "$OUT/MiniRunner.java" || exit 1

# --- 2) 编译测试源码 ----------------------------------------------------------
# ★ $AXJARS 放末尾（理由见上面那段注释）：测试源码本身不 `import androidx`，它只在**运行时**
#   才需要（反射触发父类链加载），所以编译期有它没它都行；放末尾是为了不改变既有的解析顺序。
CP="$MAIN_KT:$MAIN_JAVA:$RJAR:$ANDROID_JAR:$OUT:$STDLIB:$COROUT:$GSON:$REFLECT:$AXJARS"
# ★ -Xfriend-paths：主代码里的 `internal` 声明（DshInstances.writeNow 等，以及 R24 的 seam）
#   在 Gradle 单测里天然可见（AGP 会给 friendPaths），这条 harness 原先不给 ⇒ 测试一旦访问
#   `internal` 就"Gradle 绿、脚本红"。把主代码产物目录声明成 friend，两套 harness 口径一致。
java -cp "$COMPILER:$STDLIB:$REFLECT:$COROUT:$ANNOT" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 1.8 -nowarn \
  -Xfriend-paths="$MAIN_KT" \
  -cp "$CP" -d "$TESTOUT" \
  $(find "$TEST_SRC" -name '*.kt') || { echo "测试编译失败"; exit 1; }

# --- 3) 跑 -------------------------------------------------------------------
# ★ 测试类**自动扫描**，不再手写类名。
#   原来这里写死了 `com.dsh.DshCoreLogicTest com.dsh.DshResourceFormatTest`，
#   于是**新增的测试类永远不会被执行**（编译过了、绿灯亮了、其实一条没跑）——
#   这正是 ProcessPidConversionTest 加进来时暴露的坑。
#   规则：取 $TESTOUT/com/dsh 下的顶层类（跳过 `Foo$Bar.class` 这种内部类），
#   逐个交给 MiniRunner；没有 @Test 的类会被它跳过，不需要维护名单。
TESTCLASSES=$(cd "$TESTOUT" && find com/dsh -name '*.class' ! -name '*$*' \
  | sed -e 's|\.class$||' -e 's|/|.|g' | LC_ALL=C sort | tr '\n' ' ')
if [ -z "$TESTCLASSES" ]; then
  echo "!! 没扫到任何测试类（$TESTOUT/com/dsh 为空？）"; exit 1
fi
echo "== Kotlin 单测：$(echo $TESTCLASSES | wc -w) 个类 =="
echo "   $TESTCLASSES"
# -Ddsh.android.jar：个别测试要**直接解析 android.jar 的字节码**做前提校验
# （ProotChildPidTest 证明 java.lang.Process 没有 pid()，即 /proc 兜底的必要性）。
# 从这里传，省得测试自己猜 SDK 在哪。
java -cp "$TESTOUT:$OUT:$CP" -Ddsh.android.jar="$ANDROID_JAR" MiniRunner $TESTCLASSES || {
  echo "!! Kotlin 单测未通过（见上）"; exit 1; }

# --- 4) 运行时脚本自检（真 dash 跑 assets/dsh/scripts）------------------------
# 为什么放在这里：这批用例防的是"脚本在真机上必然失败"（bash 专有语法在 dash 下第 1 行就退出、
# npm 假成功被误判成安装成功、token/READY 协议漂移）。历史上它们只被手工跑，
# 结果"脚本 18/18"长期只写在文档里、没有入口 —— 现在补进来。
echo
echo "== 运行时脚本自检（POSIX sh）=="
SCRIPT_TEST="$EA/dsh-launcher-poc/scripts/test-scripts-posix.sh"
if [ -f "$SCRIPT_TEST" ]; then
  if sh "$SCRIPT_TEST"; then
    echo "== 脚本自检通过 =="
  else
    echo "!! 脚本自检未通过（见上）"
    exit 1
  fi
else
  echo "!! 找不到 $SCRIPT_TEST（快照仓文件；只 clone 源码仓时没有，跳过）"
fi
