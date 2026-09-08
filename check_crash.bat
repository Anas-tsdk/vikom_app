@echo off
echo Checking for crashes on both emulators...
echo.
echo === EMULATOR 5554 CRASH LOGS ===
"C:\Users\carta\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 logcat -d -b crash *:E | tail -50
echo.
echo === EMULATOR 5556 CRASH LOGS ===
"C:\Users\carta\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5556 logcat -d -b crash *:E | tail -50
echo.
echo === APP SPECIFIC ERRORS (5554) ===
"C:\Users\carta\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 logcat -d | grep -i "tv_caller_app" | grep -i "error\|exception\|crash" | tail -20
echo.
echo === APP SPECIFIC ERRORS (5556) ===
"C:\Users\carta\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5556 logcat -d | grep -i "tv_caller_app" | grep -i "error\|exception\|crash" | tail -20
pause
