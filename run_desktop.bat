@echo off
rem H-ECU Synth on the PC: the same sound engine as the phone, with a tuning panel.
rem Uses Android Studio's built-in Java unless JAVA_HOME is already set.
if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
cd /d "%~dp0"
call "%~dp0gradlew.bat" -q --console=plain :desktop:run
