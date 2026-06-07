@REM Maven Wrapper startup script for Windows
@REM ----------------------------------------------------------------------------
@echo off
setlocal

set "MAVEN_PROJECTBASEDIR=%~dp0"
set "MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%"
set "MAVEN_WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar"

if "%JAVA_HOME%"=="" (
  set "JAVA_EXEC=java"
) else (
  set "JAVA_EXEC=%JAVA_HOME%\bin\java.exe"
)

set "MAVEN_OPTS=-Xmx64m -Xms64m %MAVEN_OPTS%"

"%JAVA_EXEC%" %MAVEN_OPTS% -Dmaven.multiModuleProjectDirectory="%MAVEN_PROJECTBASEDIR%" -classpath "%MAVEN_WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
