#!/bin/bash
set -e
ADB=/c/Users/wzy/AppData/Local/Android/Sdk/platform-tools/adb.exe
LOCAL="C:/Users/wzy/Documents/Qoder/2026-11-01/29ef2f7a/out15"
export MSYS_NO_PATHCONV=1
$ADB root >/dev/null 2>&1 || true
sleep 1
$ADB shell "mount -o rw,remount /system_ext 2>/dev/null || true"
$ADB push "$LOCAL/Launcher3QuickStep.apk" /system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk
$ADB push "$LOCAL/Launcher3QuickStep.odex" /system_ext/priv-app/Launcher3QuickStep/oat/arm64/Launcher3QuickStep.odex
$ADB push "$LOCAL/Launcher3QuickStep.vdex" /system_ext/priv-app/Launcher3QuickStep/oat/arm64/Launcher3QuickStep.vdex
$ADB shell "chmod 644 /system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk /system_ext/priv-app/Launcher3QuickStep/oat/arm64/Launcher3QuickStep.odex /system_ext/priv-app/Launcher3QuickStep/oat/arm64/Launcher3QuickStep.vdex"
$ADB shell "restorecon -R /system_ext/priv-app/Launcher3QuickStep"
$ADB shell "am force-stop com.android.launcher3"
sleep 1
$ADB shell "input keyevent KEYCODE_HOME"
echo deployed
