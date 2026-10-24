#!/sbin/sh
# Prepares the robot for Toque. Run it in TWRP, as root:
#   adb push Magisk-vXX.apk /tmp/magisk.apk
#   adb push boot-original.img /tmp/boot.img        (your backup of the original boot)
#   adb push %USERPROFILE%\.android\adbkey.pub /tmp/adbkey.pub   (optional)
#   adb push tools\robot\twrp-root.sh /tmp/
#   adb shell sh /tmp/twrp-root.sh
#   adb pull /tmp/magisk/new-boot.img boot-magisk.img
#
# 1. Patches the original boot with official Magisk -> /tmp/magisk/new-boot.img (nothing is
#    flashed: flash it into "boot" with SP Flash Tool afterwards).
# 2. Enables ADB in Android (/system/build.prop) and deletes /system/recovery-from-boot.p, which
#    would otherwise put the stock recovery back over TWRP. Copies of the originals: /tmp/orig.
# 3. Optionally authorizes the PC's ADB key, so no prompt is needed on the robot's screen.
T=${T:-/tmp}
die() { echo "ERROR: $*"; exit 1; }

[ -f $T/magisk.apk ] || die "$T/magisk.apk missing"
[ -f $T/boot.img ] || die "$T/boot.img missing (your original boot backup)"

echo "== 1. Magisk: patching the original boot"
rm -rf $T/magisk
mkdir -p $T/magisk/apk && cd $T/magisk || die "cannot create $T/magisk"
unzip -o -q $T/magisk.apk -d apk || die "cannot unzip magisk.apk"
for f in boot_patch.sh util_functions.sh stub.apk; do
  cp apk/assets/$f . || die "$f missing: is this a Magisk APK?"
done
for f in magiskboot magiskinit magisk init-ld busybox; do
  cp apk/lib/armeabi-v7a/lib$f.so $f || die "lib$f.so missing: is this a Magisk APK?"
done
rm -rf apk
cp $T/boot.img boot.img
chmod 755 *
# Android 6, data not encrypted: keep the stock encryption setting, drop dm-verity.
ASH_STANDALONE=1 KEEPFORCEENCRYPT=true KEEPVERITY=false ./busybox sh ./boot_patch.sh boot.img
[ -s new-boot.img ] || die "Magisk could not patch the boot image (nothing was changed on the robot)"
rm -f boot.img stock_boot.img kernel kernel_dtb ramdisk.cpio
ls -l $T/magisk/new-boot.img

echo "== 2. System: ADB at startup, keep TWRP"
B=$(ls -d /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name 2>/dev/null | head -n 1)
grep -q " /system " /proc/mounts || mount /system 2>/dev/null || mount -t ext4 $B/system /system
grep -q " /system " /proc/mounts || die "cannot mount /system"
mount -o remount,rw /system
[ -f /system/build.prop ] || die "/system/build.prop not found"
rm -rf $T/orig; mkdir -p $T/orig
cp -p /system/build.prop $T/orig/
[ -f /system/recovery-from-boot.p ] && cp -p /system/recovery-from-boot.p $T/orig/
[ -n "$(tail -c 1 /system/build.prop)" ] && echo >> /system/build.prop
for l in persist.service.adb.enable=1 persist.service.debuggable=1 persist.sys.usb.config=mtp,adb; do
  grep -qx "$l" /system/build.prop || echo "$l" >> /system/build.prop
done
tail -n 3 /system/build.prop
if [ -f /data/property/persist.sys.usb.config ]; then
  cp -p /data/property/persist.sys.usb.config $T/orig/
  printf 'mtp,adb' > /data/property/persist.sys.usb.config
fi
rm -f /system/recovery-from-boot.p
sync

echo "== 3. ADB key of the PC"
if [ -s $T/adbkey.pub ]; then
  mkdir -p /data/misc/adb
  [ -f /data/misc/adb/adb_keys ] && cp -p /data/misc/adb/adb_keys $T/orig/
  { cat $T/adbkey.pub; echo; } | grep -v '^$' >> /data/misc/adb/adb_keys
  chown 1000:2000 /data/misc/adb /data/misc/adb/adb_keys
  chmod 2750 /data/misc/adb
  chmod 0640 /data/misc/adb/adb_keys
  chcon u:object_r:adb_keys_file:s0 /data/misc/adb /data/misc/adb/adb_keys 2>/dev/null
  echo "PC authorized"
else
  echo "no $T/adbkey.pub: accept the ADB prompt on the robot's screen at first start"
fi
sync
echo "== Done. Next: adb pull $T/magisk/new-boot.img boot-magisk.img"
