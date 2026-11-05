@echo off
REM MineSMP Start (Windows) - Java 25 erforderlich
cd /d "%~dp0"
java -Xms4G -Xmx4G -XX:+UseG1GC -jar paper.jar --nogui
pause
