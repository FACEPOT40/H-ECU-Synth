@echo off
REM Builds the desktop app for GitHub Releases: dist\H-ECU-Synth-Desktop-<version>-win64.zip
REM (a folder with "H-ECU Synth.exe" and its own small Java runtime: no Java install needed).
REM Needs a JDK 17+ with jpackage / jlink (Eclipse Adoptium JDK 25 on this PC).
setlocal
cd /d "%~dp0"

set "JDK=C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot"
if not exist "%JDK%\bin\jpackage.exe" (
    echo jpackage not found in "%JDK%": set JDK in this file to a JDK 17+ folder.
    exit /b 1
)
if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

for /f "tokens=3" %%v in ('findstr /c:"version = " desktop\build.gradle.kts') do set VER=%%~v

call "%~dp0gradlew.bat" -q :desktop:installDist || exit /b 1

if exist build\desktop-runtime rmdir /s /q build\desktop-runtime
if exist build\desktop-image rmdir /s /q build\desktop-image

REM a slim Java with only what the app uses (Swing, sound, clipboard)
"%JDK%\bin\jlink.exe" --add-modules java.desktop --strip-debug --no-header-files --no-man-pages ^
    --compress zip-6 --output build\desktop-runtime || exit /b 1

"%JDK%\bin\jpackage.exe" --type app-image --name "H-ECU Synth" --app-version %VER% --vendor FACEPOT40 ^
    --description "Synthetic engine and turbo sounds (desktop simulator and tuner)" ^
    --input desktop\build\install\H-ECU-Synth\lib --main-jar desktop-%VER%.jar ^
    --main-class com.pai.hecusynth.desktop.MainKt --runtime-image build\desktop-runtime ^
    --icon assets\icon.ico --dest build\desktop-image || exit /b 1

if not exist dist mkdir dist
if exist "dist\H-ECU-Synth-Desktop-%VER%-win64.zip" del "dist\H-ECU-Synth-Desktop-%VER%-win64.zip"
powershell -NoProfile -Command "Compress-Archive -Path 'build\desktop-image\H-ECU Synth' -DestinationPath 'dist\H-ECU-Synth-Desktop-%VER%-win64.zip'" || exit /b 1

echo.
echo Done: dist\H-ECU-Synth-Desktop-%VER%-win64.zip
