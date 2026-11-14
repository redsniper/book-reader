#!/usr/bin/env bash
# 墨阅本机开发环境加载器。
# 用法（仓库根目录，bash/zsh）：
#   . scripts/dev-env.sh
#
# 设计说明：
# - 本机路径的唯一来源是仓库根目录的 .env.local（不入库），模板是 .env.example。
# - 本脚本只导出环境变量并同步 local.properties，不安装工具、不启动模拟器。
# - 必须 source 到当前 shell，否则导出的 JAVA_HOME/ANDROID_HOME 不会影响后续 ./gradlew。

_moyue_script_path() {
    if [ -n "${BASH_SOURCE:-}" ]; then
        printf '%s\n' "${BASH_SOURCE[0]}"
    elif [ -n "${ZSH_VERSION:-}" ]; then
        # zsh 没有 BASH_SOURCE；%x 是当前 source 的文件路径。eval 只在 zsh 分支解析。
        eval 'printf "%s\n" "${(%):-%x}"'
    else
        # 兼容其他 shell；从仓库根目录 source 时 $0 通常可用。
        printf '%s\n' "$0"
    fi
}

_moyue_root="$(cd "$(dirname "$(_moyue_script_path)")/.." && pwd -P)"
MOYUE_ROOT="$_moyue_root"
export MOYUE_ROOT
_moyue_env_file="$MOYUE_ROOT/.env.local"

_moyue_error() {
    printf '墨阅开发环境：%s\n' "$1" >&2
    return 1
}

if [ -f "$_moyue_env_file" ]; then
    # .env.local 是本机可信文件，按 shell 语法加载；只放路径，不要放密钥。
    set -a
    # shellcheck disable=SC1090
    . "$_moyue_env_file"
    set +a
fi

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    _moyue_error "未找到有效的 JAVA_HOME。请执行 cp .env.example .env.local，填写 JDK 21 根目录（macOS 为 .../Contents/Home），然后重试：. scripts/dev-env.sh"
    return 1 2>/dev/null || exit 1
fi

if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME" ]; then
    _moyue_error "未找到有效的 ANDROID_HOME。请执行 cp .env.example .env.local，填写 Android SDK 根目录，然后重试：. scripts/dev-env.sh"
    return 1 2>/dev/null || exit 1
fi

# 规范化路径，避免结尾斜杠或符号链接导致 local.properties 与 PATH 不一致。
JAVA_HOME="$(cd "$JAVA_HOME" && pwd -P)"
ANDROID_HOME="$(cd "$ANDROID_HOME" && pwd -P)"
export JAVA_HOME ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# 本机 Android 用户目录、AVD 与 Gradle 缓存留在仓库内，避免写受限的用户主目录。
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$MOYUE_ROOT/.android-home}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$ANDROID_USER_HOME/.android/avd}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$MOYUE_ROOT/.gradle-home}"

_moyue_prepend_path() {
    case ":$PATH:" in
        *":$1:"*) ;;
        *) PATH="$1:$PATH" ;;
    esac
}

for _moyue_dir in \
    "$JAVA_HOME/bin" \
    "$ANDROID_HOME/platform-tools" \
    "$ANDROID_HOME/emulator" \
    "$ANDROID_HOME/build-tools/35.0.0" \
    "$ANDROID_HOME/cmdline-tools/latest/bin"; do
    if [ -d "$_moyue_dir" ]; then
        _moyue_prepend_path "$_moyue_dir"
    fi
done
export PATH

# 组件缺失只告警，不阻断构建；AGP 会在真正需要时给出更具体的错误。
for _moyue_req in \
    "$ANDROID_HOME/platforms/android-35" \
    "$ANDROID_HOME/build-tools/35.0.0" \
    "$ANDROID_HOME/platform-tools"; do
    if [ ! -d "$_moyue_req" ]; then
        printf '墨阅开发环境：警告：缺少 %s\n' "$_moyue_req" >&2
    fi
done

# 让 AGP/Android Studio 继续使用标准 local.properties，但 sdk.dir 只从 .env.local 派生，
# 避免同一 SDK 路径在多个文件里手工维护。local.properties 已 gitignore。
_moyue_sync_local_properties() {
    _moyue_props="$MOYUE_ROOT/local.properties"
    _moyue_tmp="$_moyue_props.tmp.$$"
    _moyue_header="# 由 scripts/dev-env.sh 根据 .env.local 的 ANDROID_HOME 生成；仅本机使用，禁止提交。"
    if [ -f "$_moyue_props" ]; then
        # 保留 ndk.dir 等 IDE 可能写入的其他键，只替换 sdk.dir。
        MOYUE_SDK_DIR="$ANDROID_HOME" awk '
            BEGIN { found = 0 }
            /^[[:space:]]*sdk\.dir[[:space:]]*=/ {
                print "sdk.dir=" ENVIRON["MOYUE_SDK_DIR"]
                found = 1
                next
            }
            { print }
            END {
                if (!found) {
                    print "sdk.dir=" ENVIRON["MOYUE_SDK_DIR"]
                }
            }
        ' "$_moyue_props" > "$_moyue_tmp" || return 1
        if cmp -s "$_moyue_props" "$_moyue_tmp"; then
            rm -f "$_moyue_tmp"
        else
            mv "$_moyue_tmp" "$_moyue_props" || return 1
        fi
    else
        {
            printf '%s\n' "$_moyue_header"
            printf 'sdk.dir=%s\n' "$ANDROID_HOME"
        } > "$_moyue_tmp" || return 1
        mv "$_moyue_tmp" "$_moyue_props" || return 1
    fi
}

if ! _moyue_sync_local_properties; then
    _moyue_error "无法写入 local.properties；请检查仓库目录权限。"
    return 1 2>/dev/null || exit 1
fi

# 清理一次性变量与函数，避免污染交互式 shell；保留导出的环境变量和 MOYUE_ROOT。
unset _moyue_root _moyue_dir _moyue_req _moyue_props _moyue_tmp _moyue_header _moyue_env_file
unset -f _moyue_script_path _moyue_error _moyue_prepend_path _moyue_sync_local_properties 2>/dev/null || true
