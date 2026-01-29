@echo off
set DIR=%~dp0
set "JAVA=%JAVA_HOME%\bin\java.exe"
if "%JAVA_HOME%"=="" set "JAVA=java"
set CLASSPATH=%DIR%gradle\wrapper\gradle-wrapper.jar
"%JAVA%" -cp "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
