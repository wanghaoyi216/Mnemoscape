# E2E integration test script for Mnemoscape platform (Windows Native)
$Base = "http://localhost:8080/api/v1"
$global:Pass = 0
$global:Fail = 0

function Green-Log ($msg) {
    Write-Host "  PASS" -ForegroundColor Green -NoNewline
    Write-Host " $msg"
    $global:Pass++
}

function Red-Log ($msg, $err) {
    Write-Host "  FAIL" -ForegroundColor Red -NoNewline
    Write-Host " $msg — $err"
    $global:Fail++
}

function Assert-Status ($desc, $expected, $actual, $body) {
    if ($actual -eq $expected) {
        Green-Log $desc
    } else {
        Red-Log $desc "HTTP $actual — $body"
    }
}

function Assert-Contains ($desc, $pattern, $body) {
    if ($body -match $pattern) {
        Green-Log $desc
    } else {
        Red-Log $desc "response missing '$pattern'"
    }
}

function Invoke-SafeRequest ($Uri, $Method = "Get", $Headers = @{}, $Body = $null, $ContentType = $null) {
    $params = @{
        Uri = $Uri
        Method = $Method
        Headers = $Headers
        UseBasicParsing = $true
    }
    if ($Body) { $params["Body"] = $Body }
    if ($ContentType) { $params["ContentType"] = $ContentType }

    try {
        $response = Invoke-WebRequest @params
        return [PSCustomObject]@{
            StatusCode = $response.StatusCode
            Content = $response.Content
        }
    } catch {
        if ($_.Exception.Response) {
            $resp = $_.Exception.Response
            $stream = $resp.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $content = $reader.ReadToEnd()
            $code = [int]$resp.StatusCode
            return [PSCustomObject]@{
                StatusCode = $code
                Content = $content
            }
        } else {
            return [PSCustomObject]@{
                StatusCode = 500
                Content = $_.Exception.Message
            }
        }
    }
}

Write-Host "=== Mnemoscape Native E2E Tests ===" -ForegroundColor Cyan
Write-Host ""

$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$E2E_USER = "e2euser-$timestamp"
$E2E_EMAIL = "e2e-$timestamp@test.com"

# 1. Auth: Register
Write-Host "[Auth]" -ForegroundColor Yellow
$regBody = @{
    username = $E2E_USER
    email = $E2E_EMAIL
    password = "Test1234!"
} | ConvertTo-Json -Compress

$res = Invoke-SafeRequest -Uri "$Base/auth/register" -Method Post -Body $regBody -ContentType "application/json"
Assert-Status "Register new user" 201 $res.StatusCode $res.Content
Assert-Contains "Register returns id" '"code":201' $res.Content

# 2. Auth: Login
$loginBody = @{
    username = $E2E_USER
    password = "Test1234!"
} | ConvertTo-Json -Compress

$res = Invoke-SafeRequest -Uri "$Base/auth/login" -Method Post -Body $loginBody -ContentType "application/json"
Assert-Status "Login" 200 $res.StatusCode $res.Content

$TOKEN = $null
if ($res.StatusCode -eq 200) {
    try {
        $json = ConvertFrom-Json $res.Content
        $TOKEN = $json.data.accessToken
    } catch {}
}

if ($TOKEN) {
    Green-Log "Extract access token"
} else {
    Red-Log "Extract access token" "token not found in response"
}

if (-not $TOKEN) {
    Write-Host "No token, stopping tests." -ForegroundColor Red
    exit 1
}

$Headers = @{
    Authorization = "Bearer $TOKEN"
}

# 3. Auth: Profile
$res = Invoke-SafeRequest -Uri "$Base/auth/profile" -Headers $Headers
Assert-Status "Get profile" 200 $res.StatusCode $res.Content

Write-Host ""

# 4. Memory: Create
Write-Host "[Memory]" -ForegroundColor Yellow
$memBody = @{
    title = "Summer Evening"
    description = "A warm summer evening walk along the beach with friends watching the sunset over the ocean horizon"
    memoryYear = 2010
    privacyLevel = "PRIVATE"
} | ConvertTo-Json -Compress

$res = Invoke-SafeRequest -Uri "$Base/memories" -Method Post -Headers $Headers -Body $memBody -ContentType "application/json"
Assert-Status "Create memory" 201 $res.StatusCode $res.Content

$MEM_ID = $null
if ($res.StatusCode -eq 201) {
    try {
        $memJson = ConvertFrom-Json $res.Content
        $MEM_ID = $memJson.data.id
    } catch {}
}

if ($MEM_ID) {
    Green-Log "Extract memory ID: $MEM_ID"
} else {
    Red-Log "Extract memory ID" "id not found"
}

# 5. Memory: List
$res = Invoke-SafeRequest -Uri "$Base/memories" -Headers $Headers
Assert-Status "List memories" 200 $res.StatusCode $res.Content

# 6. Memory: Get detail
if ($MEM_ID) {
    $res = Invoke-SafeRequest -Uri "$Base/memories/$MEM_ID" -Headers $Headers
    Assert-Status "Get memory detail" 200 $res.StatusCode $res.Content
    
    # 7. Memory: Drift
    $res = Invoke-SafeRequest -Uri "$Base/memories/$MEM_ID/drift" -Headers $Headers
    Assert-Status "Get drift state" 200 $res.StatusCode $res.Content
    Assert-Contains "Drift has fadeLevel" '"fadeLevel"' $res.Content
}

Write-Host ""

# 8. AI: Reconstruct
Write-Host "[AI]" -ForegroundColor Yellow
$reconBody = @{
    description = "A summer evening"
} | ConvertTo-Json -Compress

$res = Invoke-SafeRequest -Uri "$Base/reconstruct" -Method Post -Headers $Headers -Body $reconBody -ContentType "application/json"
Assert-Status "Reconstruct scene" 200 $res.StatusCode $res.Content
Assert-Contains "Scene has objects" '"objects"' $res.Content

Write-Host ""

# 9. Resonance: Search
Write-Host "[Resonance]" -ForegroundColor Yellow
if ($MEM_ID) {
    $res = Invoke-SafeRequest -Uri "$Base/resonances/search?memoryId=$MEM_ID" -Headers $Headers
    Assert-Status "Search resonances" 200 $res.StatusCode $res.Content
    
    # 10. Resonance: Create space
    $spaceBody = @{
        memoryId1 = $MEM_ID
        memoryId2 = "mock-memory-1"
    } | ConvertTo-Json -Compress
    
    $res = Invoke-SafeRequest -Uri "$Base/resonances/spaces" -Method Post -Headers $Headers -Body $spaceBody -ContentType "application/json"
    Assert-Status "Create resonance space" 201 $res.StatusCode $res.Content
    
    $SPACE_ID = $null
    if ($res.StatusCode -eq 201) {
        try {
            $spaceJson = ConvertFrom-Json $res.Content
            $SPACE_ID = $spaceJson.data.id
        } catch {}
    }

    if ($SPACE_ID) {
        Green-Log "Extract space ID: $SPACE_ID"
        
        # 11. Resonance: Get space
        $res = Invoke-SafeRequest -Uri "$Base/resonances/spaces/$SPACE_ID" -Headers $Headers
        Assert-Status "Get resonance space" 200 $res.StatusCode $res.Content
        
        # 12. Resonance: Place note
        $noteBody = @{
            content = "Beautiful shared memory"
            mood = "warm"
            position = @{ x = 1.0; y = 1.8; z = -2.0 }
        } | ConvertTo-Json -Compress
        
        $res = Invoke-SafeRequest -Uri "$Base/resonances/spaces/$SPACE_ID/notes" -Method Post -Headers $Headers -Body $noteBody -ContentType "application/json"
        Assert-Status "Place note" 201 $res.StatusCode $res.Content
    } else {
        Red-Log "Extract space ID" "id not found"
    }
}

Write-Host ""

# 13. Asset: Upload (endpoint check)
Write-Host "[Asset]" -ForegroundColor Yellow
$res = Invoke-SafeRequest -Uri "$Base/assets/upload" -Method Post -Headers $Headers
if ($res.StatusCode -ne 502) {
    Green-Log "Asset service reachable (HTTP $($res.StatusCode))"
} else {
    Red-Log "Asset service reachable" "HTTP $($res.StatusCode)"
}

Write-Host ""
Write-Host "=== Results: $global:Pass passed, $global:Fail failed ===" -ForegroundColor Cyan

if ($global:Fail -gt 0) {
    exit 1
}

Write-Host "All E2E tests passed!" -ForegroundColor Green
