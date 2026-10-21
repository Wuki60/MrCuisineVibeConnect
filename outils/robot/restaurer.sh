#!/system/bin/sh
# Rend la main à l'appli Monsieur Cuisine d'origine (à lancer en root sur le robot).
P=de.silpion.mc2
BB=""
for b in /data/adb/magisk/busybox /sbin/.magisk/busybox/busybox /system/xbin/busybox /system/bin/busybox; do
  if [ -x "$b" ]; then BB="$b"; break; fi
done
am force-stop app.toque
pm enable "$P" >/dev/null 2>&1
for c in $(dumpsys package "$P" | $BB grep -oE '[A-Za-z0-9_./$]*HardwareMachineService' | $BB sort -u); do
  case "$c" in
    */*) pm enable "$c" ;;
    *) pm enable "$P/$c" ;;
  esac
done
pm disable app.toque >/dev/null 2>&1
echo "Appli d'origine rétablie. Redémarrez le robot."
