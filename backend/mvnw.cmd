@REM Maven Wrapper startup script for Windows
@REM ----------------------------------------------------------------------------
@echo off
setlocal

set "JAVA_HOME=C:\Users\WangHaoYi\.jdks\ms-17.0.17"
set "MAVEN_PROJECTBASEDIR=%~dp0"
set "MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%"
set "MAVEN_WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar"

if "%JAVA_HOME%"=="" (
  set "JAVA_EXEC=java"
) else (
  set "JAVA_EXEC=%JAVA_HOME%\bin\java.exe"
)

set "MAVEN_OPTS=-Xmx2048m -Xms512m %MAVEN_OPTS%"

"%JAVA_EXEC%" %MAVEN_OPTS% -Dmaven.multiModuleProjectDirectory="%MAVEN_PROJECTBASEDIR%" -classpath "%MAVEN_WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
