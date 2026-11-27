
@echo off
cd /d "%~dp0"
if not exist backend\out mkdir backend\out
javac -source 8 -target 8 -d backend\out backend\BookingServer.java
if errorlevel 1 exit /b 1
java -cp backend\out BookingServer