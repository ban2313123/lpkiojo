@echo off
chcp 65001 > nul
echo Запуск тестового сервера NeoForge в среде разработки...
call gradlew.bat runServer
pause
