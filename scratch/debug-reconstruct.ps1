# Debug script for /reconstruct
$Base = "http://localhost:8080/api/v1"

# Generate fresh credentials or use a static test user
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$username = "debuguser-$timestamp"
$email = "debug-$timestamp@test.com"

# Register
$regBody = @{
    username = $username
    email = $email
    password = "Test1234!"
} | ConvertTo-Json -Compress
$reg = Invoke-WebRequest -Uri "$Base/auth/register" -Method Post -Body $regBody -ContentType "application/json" -UseBasicParsing

# Login
$loginBody = @{
    username = $username
    password = "Test1234!"
} | ConvertTo-Json -Compress
$login = Invoke-WebRequest -Uri "$Base/auth/login" -Method Post -Body $loginBody -ContentType "application/json" -UseBasicParsing
$token = (ConvertFrom-Json $login.Content).data.accessToken

$headers = @{
    Authorization = "Bearer $token"
}

# Reconstruct
$reconBody = @{
    description = "A summer evening"
} | ConvertTo-Json -Compress
$recon = Invoke-WebRequest -Uri "$Base/reconstruct" -Method Post -Headers $headers -Body $reconBody -ContentType "application/json" -UseBasicParsing

Write-Host "Reconstruct Response Content:"
Write-Host $recon.Content
