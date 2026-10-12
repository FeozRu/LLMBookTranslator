@echo off
rem Запуск переводчика книг (Windows). JDK 17+ должен быть в PATH.
cd /d "%~dp0"
gradlew.bat :desktop:run
