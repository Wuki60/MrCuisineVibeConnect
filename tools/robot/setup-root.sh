#!/system/bin/sh
# Run as root on the robot (tools\install-robot.ps1 does it after installing Toque).
# 1. Allows Toque in Magisk, without the on-screen prompt: that prompt closes by itself after
#    10 s and Magisk then refuses Toque for good (the robot has no way to reach Magisk's settings).
# 2. Makes sure Magisk's busybox is in /data/adb/magisk: Toque needs it for the serial port.
U=$(stat -c %u /data/data/app.toque 2>/dev/null)
[ -n "$U" ] || { echo "Toque is not installed"; exit 1; }
magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($U,2,0,0,0)" \
  && echo "Toque allowed in Magisk"
if [ ! -x /data/adb/magisk/busybox ]; then
  for b in /data/app/com.topjohnwu.magisk*/lib/arm*/libbusybox.so; do
    [ -f "$b" ] || continue
    mkdir -p /data/adb/magisk
    cp "$b" /data/adb/magisk/busybox && chmod 755 /data/adb/magisk/busybox
    chcon u:object_r:magisk_file:s0 /data/adb/magisk/busybox 2>/dev/null
    echo "Magisk busybox installed"
    break
  done
fi
[ -x /data/adb/magisk/busybox ] || echo "WARNING: no busybox in /data/adb/magisk (install the Magisk app: adb install Magisk-vXX.apk)"
