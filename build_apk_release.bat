@echo off
REM Builds the signed phone app for GitHub Releases: dist\H-ECU-Synth-<version>.apk
REM Signed with the same release key as H-ECU Logger (its android\keystore.properties, or a
REM keystore.properties next to this file). The key and its password are never printed or committed.
setlocal
cd /d "%~dp0"
if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

call "%~dp0gradlew.bat" -q testDebugUnitTest assembleRelease || exit /b 1

for /f "tokens=3" %%v in ('findstr /c:"versionName = " app\build.gradle.kts') do set VER=%%~v
if not exist dist mkdir dist
copy /y app\build\outputs\apk\release\app-release.apk "dist\H-ECU-Synth-%VER%.apk" >nul || exit /b 1

echo.
echo Done: dist\H-ECU-Synth-%VER%.apk
echo Remember: raise versionCode in app\build.gradle.kts before the next release.
