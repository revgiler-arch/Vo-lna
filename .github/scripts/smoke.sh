#!/bin/bash
# Прогон «как пользователь»: главная → поиск → воспроизведение → плеер → библиотека.
O=test-out; mkdir -p $O; R=$O/report.txt; : > $R
log() { echo "$*" | tee -a $R; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml $O/ui.xml >/dev/null 2>&1; }
shot() { adb exec-out screencap -p > $O/$1.png; dump; cp $O/ui.xml $O/$1.xml; log "[скрин] $1"; }
tap() { # tap "текст" [номер]
  dump; xy=$(python3 .github/scripts/ui.py $O/ui.xml "$1" ${2:-0})
  if [ -n "$xy" ]; then adb shell input tap $xy; log "[тап] $1 -> $xy"; else log "[НЕ НАЙДЕНО] $1"; fi
}
media() { adb shell dumpsys media_session | grep -m1 -oE "state=PlaybackState \{state=[A-Z_0-9]+, position=[0-9]+" ; }

log "== устройство: $(adb shell getprop ro.product.model) Android $(adb shell getprop ro.build.version.release)"
adb install -r Volna.apk 2>&1 | tail -1 | tee -a $R
adb shell pm grant com.volna.music android.permission.POST_NOTIFICATIONS 2>/dev/null
adb logcat -c
adb shell am start -n com.volna.music/.MainActivity >/dev/null
sleep 30; shot 01_home

# поиск
tap "Поиск"; sleep 2
tap "@edit"; sleep 1
adb shell input text "Imagine%sDragons%sBeliever"; adb shell input keyevent 66
sleep 20; shot 02_search

# включить первый результат (строка с кнопкой «Меню»)
dump; xy=$(python3 .github/scripts/ui.py $O/ui.xml "Меню" 0)
if [ -n "$xy" ]; then set -- $xy; adb shell input tap 300 $2; log "[тап] первый результат y=$2"; else log "[НЕ НАЙДЕНО] результаты поиска"; fi
sleep 25; shot 03_playing
log "[плеер через 25с] $(media)"
sleep 15
log "[плеер через 40с] $(media)"

# полный плеер
dump; xy=$(python3 .github/scripts/ui.py $O/ui.xml "Следующий" 0)
if [ -n "$xy" ]; then set -- $xy; adb shell input tap 300 $2; log "[тап] мини-плеер"; fi
sleep 3; shot 04_full_player
tap "Очередь"; sleep 5; shot 05_queue
tap "Следующий"; sleep 20; shot 06_next_track
log "[после «следующий»] $(media)"
tap "Свернуть"; sleep 2

# фон: свернуть приложение, музыка должна играть
adb shell input keyevent 3; sleep 15
log "[в фоне 15с] $(media)"
adb shell cmd statusbar expand-notifications; sleep 3; adb exec-out screencap -p > $O/07_notification.png; log "[скрин] 07_notification"
adb shell cmd statusbar collapse
adb shell am start -n com.volna.music/.MainActivity >/dev/null; sleep 3

# главная после истории, библиотека, вкладка YouTube
tap "Главная"; tap "Обновить"; sleep 25; shot 08_home_after
tap "Библиотека"; sleep 2; tap "История"; sleep 2; shot 09_history
tap "YouTube"; sleep 2; shot 10_youtube_tab
tap "Войти через Google"; sleep 15; shot 11_google_login
adb shell input keyevent 4; sleep 2

# свайп по обложке и оформления
open_player() { dump; xy=$(python3 .github/scripts/ui.py $O/ui.xml "Следующий" 0); if [ -n "$xy" ]; then set -- $xy; adb shell input tap 300 $2; log "[тап] мини-плеер"; fi; sleep 3; }
tap "Главная"; sleep 2
open_player; shot 12_player_dark
adb shell input swipe 900 700 150 700 300; sleep 3; shot 13_after_swipe
adb shell input keyevent 4; sleep 2
tap "Оформление"; sleep 1; tap "Синяя"; sleep 3; shot 14_home_blue
open_player; shot 15_player_blue; adb shell input keyevent 4; sleep 2
tap "Оформление"; sleep 1; tap "Изумрудная"; sleep 3; shot 16_home_emerald
open_player; shot 17_player_emerald; adb shell input keyevent 4; sleep 2
tap "Оформление"; sleep 1; tap "Тёмная"; sleep 2
adb shell input swipe 540 600 540 1500 400; sleep 2; shot 18_pull_refresh

adb logcat -d > $O/logcat.txt
log "== вылеты:"; grep -E "FATAL EXCEPTION|AndroidRuntime" -A15 $O/logcat.txt | head -60 | tee -a $R
log "== ошибки приложения:"; grep -iE "volna|newpipe|ExoPlayer|PlaybackException|MediaCodec.*error" $O/logcat.txt | grep -E " [EW] " | head -60 | tee -a $R
exit 0
