#!/usr/bin/env bash
# 墨阅黑盒测试辅助函数（入库脚本）。
# 用法（仓库根目录，bash/zsh）：
#   . scripts/blackbox.sh
# 本脚本会自动加载 scripts/dev-env.sh；随后可使用 start_emulator/wait_boot/
# install_app/push_fixtures。模拟器与夹具仍放在仓库内被 gitignore 的目录中。

_moyue_bb_script_path() {
    if [ -n "${BASH_SOURCE:-}" ]; then
        printf '%s\n' "${BASH_SOURCE[0]}"
    elif [ -n "${ZSH_VERSION:-}" ]; then
        eval 'printf "%s\n" "${(%):-%x}"'
    else
        printf '%s\n' "$0"
    fi
}

_moyue_bb_root="$(cd "$(dirname "$(_moyue_bb_script_path)")/.." && pwd -P)"

if ! . "$_moyue_bb_root/scripts/dev-env.sh"; then
    printf '墨阅黑盒环境：无法加载 scripts/dev-env.sh。\n' >&2
    return 1 2>/dev/null || exit 1
fi

AVD_NAME="${MOYUE_AVD_NAME:-moyue_api35}"
APK="$_moyue_bb_root/app/build/outputs/apk/debug/app-debug.apk"
FIXTURES="${MOYUE_FIXTURES_DIR:-$_moyue_bb_root/.toolchain/blackbox-fixtures/data}"
PKG="com.moyue.reader.debug"
ACTIVITY="$PKG/com.moyue.reader.MainActivity"
EMULATOR_LOG="${TMPDIR:-/tmp}"
EMULATOR_LOG="${EMULATOR_LOG%/}/moyue-emulator.log"

_moyue_require_emulator() {
    if [ ! -x "$ANDROID_HOME/emulator/emulator" ]; then
        printf '未找到 Android Emulator：%s/emulator/emulator\n' "$ANDROID_HOME" >&2
        return 1
    fi
}

_moyue_require_adb() {
    if ! command -v adb >/dev/null 2>&1; then
        printf '未找到 adb；请确认 ANDROID_HOME/platform-tools 存在并由 dev-env.sh 加入 PATH。\n' >&2
        return 1
    fi
}

# 启动模拟器（GUI 窗口）；日志写入临时目录，不写仓库。
start_emulator() {
    _moyue_require_emulator || return 1
    nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" -no-boot-anim -gpu host -no-snapshot-save \
        >"$EMULATOR_LOG" 2>&1 &
    printf '模拟器启动中（AVD: %s），日志：%s\n' "$AVD_NAME" "$EMULATOR_LOG"
}

# 等待 adb 设备上线并完成开机；最多约 180 秒，避免无限挂起。
wait_boot() {
    _moyue_require_adb || return 1
    adb wait-for-device || return 1
    local waited=0
    while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
        if [ "$waited" -ge 180 ]; then
            printf '等待模拟器开机超时（180 秒）。\n' >&2
            return 1
        fi
        sleep 2
        waited=$((waited + 2))
    done
    printf '开机完成：Android %s (API %s)\n' \
        "$(adb shell getprop ro.build.version.release | tr -d '\r')" \
        "$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
}

# 安装 Debug APK 并启动应用。
install_app() {
    _moyue_require_adb || return 1
    if [ ! -f "$APK" ]; then
        printf '未找到 APK：%s；请先执行 ./gradlew assembleDebug。\n' "$APK" >&2
        return 1
    fi
    adb install -r "$APK" || return 1
    adb shell am start -n "$ACTIVITY"
}

# 推送黑盒测试数据到 /sdcard/Download。
push_fixtures() {
    _moyue_require_adb || return 1
    if [ ! -d "$FIXTURES" ]; then
        printf '未找到夹具目录：%s；请先运行夹具生成脚本。\n' "$FIXTURES" >&2
        return 1
    fi
    adb shell mkdir -p /sdcard/Download/边界文件 || return 1
    adb push "$FIXTURES/剑来 作者：烽火戏诸侯.txt" /sdcard/Download/ || return 1
    adb push "$FIXTURES/第一章测试.txt" /sdcard/Download/ || return 1
    adb push "$FIXTURES/测试书.epub" /sdcard/Download/ || return 1
    adb push "$FIXTURES/无封面测试书.epub" /sdcard/Download/ || return 1
    adb push "$FIXTURES/文本版.pdf" /sdcard/Download/ || return 1
    adb push "$FIXTURES/扫描版.pdf" /sdcard/Download/ || return 1
    adb push "$FIXTURES/长夜行 百万字.txt" /sdcard/Download/ || return 1
    adb push "$FIXTURES/边界文件/." /sdcard/Download/边界文件/ || return 1
    adb shell ls -l /sdcard/Download/
}

unset _moyue_bb_script_path 2>/dev/null || true
