# mvn install all services (use mvnw, ignore restricted-method warning)
$ErrorActionPreference = "Continue"
$env:MAVEN_OPTS = "-Xmx4g -XX:MaxMetaspaceSize=1g"
Set-Location m:\Study\ProjectTest\Mnemoscape\backend
$out = .\mvnw.cmd -q -DskipTests install 2>&1
$out = $out | Where-Object { $_ -notmatch "restricted method" -and $_ -notmatch "WARNING.*System has been called" -and $_ -notmatch "Allow\\.unsafe" }
$out | Select-Object -Last 25
"---"
"exit code: $LASTEXITCODE"
