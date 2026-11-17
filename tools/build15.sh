#!/bin/bash
cd /root/android/lineage
source build/envsetup.sh >/dev/null 2>&1
breakfast polaris >/dev/null 2>&1
m -j14 Launcher3QuickStep privapp_whitelist_com.android.launcher3 2>&1 | tail -25
