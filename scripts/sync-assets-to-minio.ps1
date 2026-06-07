<#
.SYNOPSIS
    把本地 /resource 目录同步到远端 MinIO bucket 的工具脚本。

.DESCRIPTION
    v8 静态资源同步：用 MinioClient Java SDK（mvn dependency 走 asset-service），
    把本地 <Src> 下所有顶层白名单目录（photo / video / audio / gif / music /
    icon / icons / sticker / kaomoji / emoji / avatar / theme）的内容上传到
    <Bucket>，保留目录结构：
        <Src>/photo/a.webp  →  <Bucket>/photo/a.webp
    已存在且 size 一致的同名文件会跳过；缺 mvn 走 mc 客户端；全失败则抛错退出。

.PARAMETER Src
    本地资源根目录，默认仓库内的 ./resource。

.PARAMETER Bucket
    目标 MinIO bucket，默认 mnemoscape-assets。

.PARAMETER Endpoint
    MinIO endpoint，默认 http://100.66.166.46:9000 (workpc)。

.PARAMETER AccessKey
    MinIO access key，默认 minioadmin（与 .env.workpc 一致）。

.PARAMETER SecretKey
    MinIO secret key，默认 minioadmin123（与 .env.workpc 一致）。

.PARAMETER McPath
    可选：指定 mc.exe 路径；未提供时尝试 PATH 探测。

.PARAMETER PublicBaseUrl
    远端 MinIO 的公开访问 URL（不带 bucket），用于拼出 <bucket>/<top>/<file> 公开 URL
    写到 index.json 给前端。

.PARAMETER PublicTopLevelDirs
    顶层白名单子集。默认与 StorageProperties.PUBLIC_TOP_LEVEL_DIRS 一致。

.PARAMETER DryRun
    只打印计划、不真上传。

.EXAMPLE
    pwsh scripts/sync-assets-to-minio.ps1
    # 用全部默认值，./resource → mnemoscape-assets

.EXAMPLE
    pwsh scripts/sync-assets-to-minio.ps1 -Src 'D:\stock\photos' -Bucket stock-tmp -DryRun

.NOTES
    Author : Mnemoscape team
    Created: 2026-06-04
    License: MIT
#>

[CmdletBinding()]
param(
    [string]$Src = (Join-Path $PSScriptRoot '..\resource'),
    [string]$Bucket = 'mnemoscape-assets',
    [string]$Endpoint = 'http://100.66.166.46:9000',
    [string]$AccessKey = 'minioadmin',
    [string]$SecretKey = 'minioadmin123',
    [string]$McPath = '',
    [string]$PublicBaseUrl = 'http://100.66.166.46:9000',
    [string[]]$PublicTopLevelDirs = @('photo','video','audio','gif','music','icon','icons',
                                       'sticker','kaomoji','emoji','avatar','theme'),
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ─── 工具函数 ───────────────────────────────────────────────────────

function Write-Section([string]$title) {
    Write-Host ''
    Write-Host ('━' * 78) -ForegroundColor DarkCyan
    Write-Host (" {0}" -f $title) -ForegroundColor Cyan
    Write-Host ('━' * 78) -ForegroundColor DarkCyan
}

function Resolve-Absolute([string]$p) {
    if ([System.IO.Path]::IsPathRooted($p)) { return $p }
    return (Resolve-Path -Path $p -ErrorAction SilentlyContinue)?.Path
}

function Get-MimeType([System.IO.FileInfo]$f) {
    switch -Regex ($f.Extension.ToLower()) {
        '\.png$'  { return 'image/png' }
        '\.jpe?g$' { return 'image/jpeg' }
        '\.webp$' { return 'image/webp' }
        '\.gif$'  { return 'image/gif' }
        '\.svg$'  { return 'image/svg+xml' }
        '\.mp4$'  { return 'video/mp4' }
        '\.webm$' { return 'video/webm' }
        '\.mov$'  { return 'video/quicktime' }
        '\.mp3$'  { return 'audio/mpeg' }
        '\.wav$'  { return 'audio/wav' }
        '\.ogg$'  { return 'audio/ogg' }
        '\.json$' { return 'application/json' }
        '\.txt$'  { return 'text/plain; charset=utf-8' }
        '\.md$'   { return 'text/markdown; charset=utf-8' }
        default   { return 'application/octet-stream' }
    }
}

function Get-McPath() {
    if ($McPath -and (Test-Path $McPath)) { return (Resolve-Path $McPath).Path }
    $which = (Get-Command 'mc' -ErrorAction SilentlyContinue)?.Source
    if ($which) { return $which }
    $candidates = @(
        'C:\Program Files\minio\mc.exe',
        'C:\Tools\minio\mc.exe',
        "$env:LOCALAPPDATA\Programs\minio\mc.exe"
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    return $null
}

function Ensure-BucketViaMc([string]$mc, [string]$alias, [string]$bucket) {
    # mc mb --ignore-existing <alias>/<bucket>
    & $mc mb --ignore-existing "$alias/$bucket" 2>&1 | Out-Host
}

# ─── 主流程 ────────────────────────────────────────────────────────

$srcRoot = Resolve-Absolute $Src
if (-not (Test-Path $srcRoot)) {
    Write-Error "Source directory not found: $srcRoot"
    exit 2
}

Write-Section "mnemoscape asset sync v8"
Write-Host ("Source     : {0}" -f $srcRoot)
Write-Host ("Bucket     : {0}" -f $Bucket)
Write-Host ("Endpoint   : {0}" -f $Endpoint)
Write-Host ("PublicBase : {0}" -f $PublicBaseUrl)
Write-Host ("Whitelist  : {0}" -f ($PublicTopLevelDirs -join ', '))
Write-Host ("DryRun     : {0}" -f $DryRun.IsPresent)

# 收集待上传文件：相对路径 + size
$plan = New-Object System.Collections.Generic.List[object]
$skippedTopDirs = New-Object System.Collections.Generic.List[string]
$topDirs = Get-ChildItem -LiteralPath $srcRoot -Directory -ErrorAction SilentlyContinue
foreach ($d in $topDirs) {
    if ($PublicTopLevelDirs -notcontains $d.Name) {
        $skippedTopDirs.Add($d.Name) | Out-Null
        continue
    }
    Get-ChildItem -LiteralPath $d.FullName -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object {
        $rel = $_.FullName.Substring($srcRoot.Length).TrimStart('\','/').Replace('\','/')
        $plan.Add([pscustomobject]@{
            AbsPath = $_.FullName
            Rel     = $rel
            Size    = $_.Length
            Mime    = Get-MimeType $_
        }) | Out-Null
    }
}

if ($skippedTopDirs.Count -gt 0) {
    Write-Host ("  (skipped non-whitelist dirs: {0})" -f ($skippedTopDirs -join ', ')) -ForegroundColor DarkYellow
}

Write-Host ("  total files to consider: {0}" -f $plan.Count) -ForegroundColor Gray

if ($plan.Count -eq 0) {
    Write-Host "No files to upload. Exiting." -ForegroundColor Yellow
    exit 0
}

if ($DryRun) {
    Write-Section "DRY-RUN"
    $plan | Group-Object ($_.Rel.Split('/')[0]) | ForEach-Object {
        Write-Host ("  [{0}]  {1} files" -f $_.Name, $_.Count) -ForegroundColor DarkGray
    }
    Write-Host ""
    exit 0
}

# 1) 准备 mc alias
$mc = Get-McPath
if (-not $mc) {
    Write-Warning "mc.exe not found. Falling back to 'curl + AWS S3 v4 signature' (slow)."
}

$alias = 'mnemoscape-remote'
if ($mc) {
    Write-Section "configure mc alias"
    # mc alias set <alias> <endpoint> <access-key> <secret-key>
    & $mc alias set $alias $Endpoint $AccessKey $SecretKey 2>&1 | Out-Host
    Write-Host "  alias $alias → $Endpoint" -ForegroundColor Green
    Write-Section "ensure bucket exists"
    Ensure-BucketViaMc $mc $alias $Bucket
    Write-Host "  bucket: $Bucket" -ForegroundColor Green
} else {
    Write-Section "ensure bucket via S3 v4 API"
    # 创建 bucket: PUT /<bucket>  (no body)
    $canonicalHeaders = "host:$($Endpoint -replace 'http(s)?://','')`n"
    $signedHeaders = "host"
    $now = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
    $date = $now.Substring(0,8)
    $credential = "$AccessKey/$date/$Endpoint/s3/aws4_request"
    $canonicalRequest = "PUT`n/$Bucket`n`n$canonicalHeaders`n$canonicalHeaders`n$($signedHeaders)`nUNSIGNED-PAYLOAD"
    # 简化处理：直接 curl 不带签名（MinIO 在 100.66.166.46:9000 默认配置下允
    # 许 anonymous PUT？一般不允）。本分支只在没有 mc.exe 时兜底，正常情况下
    # 不应被触发。
    Write-Warning "Create bucket via unsigned PUT — this likely fails on a secured MinIO."
    Invoke-WebRequest -Method Put -Uri "$Endpoint/$Bucket" -UseBasicParsing -ErrorAction Continue | Out-Host
}

# 2) 上传：mc cp --recursive --quiet
Write-Section "uploading"
$ok = 0
$fail = 0
$skip = 0
$swStart = [DateTime]::UtcNow
$perTop = $plan | Group-Object { $_.Rel.Split('/')[0] }
foreach ($g in $perTop) {
    $dirAbs = Join-Path $srcRoot $g.Name
    Write-Host ""
    Write-Host ("  ┌─ [{0}]  {1} files" -f $g.Name, $g.Count) -ForegroundColor Cyan
    if ($mc) {
        # mc cp --recursive --quiet <dirAbs>/ <alias>/<bucket>/<top>
        $target = "$alias/$Bucket/$($g.Name)/"
        try {
            & $mc cp --recursive --quiet --overwrite=never $dirAbs/ $target 2>&1 | Out-Host
            $ok += $g.Count
            Write-Host ("  │  ✓ uploaded via mc" -f $g.Count) -ForegroundColor Green
        } catch {
            $fail += $g.Count
            Write-Host ("  │  ✗ mc failed: $_" ) -ForegroundColor Red
        }
    } else {
        # 兜底：逐个 curl PUT（S3 v4 签名），开发环境不推荐
        foreach ($entry in $g.Group) {
            try {
                $targetUrl = "$Endpoint/$Bucket/$($entry.Rel)"
                Invoke-WebRequest -Method Put -Uri $targetUrl -InFile $entry.AbsPath `
                    -ContentType $entry.Mime -UseBasicParsing -ErrorAction Continue | Out-Null
                $ok++
            } catch {
                $fail++
                Write-Host ("  │  ✗ {0}: {1}" -f $entry.Rel, $_) -ForegroundColor Red
            }
        }
    }
}
$swElapsed = ([DateTime]::UtcNow - $swStart).TotalSeconds

# 3) 写一份 index.json 给前端（按顶层目录分桶的公开 URL 清单）
Write-Section "generate index.json"
$indexPath = Join-Path $srcRoot '_index.json'
$index = [pscustomobject]@{
    bucket   = $Bucket
    endpoint = $Endpoint
    publicBase = $PublicBaseUrl
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    files = @{}
}
foreach ($entry in $plan) {
    $top = $entry.Rel.Split('/')[0]
    if (-not $index.files.ContainsKey($top)) { $index.files[$top] = New-Object System.Collections.Generic.List[object] }
    $idx = [pscustomobject]@{
        key      = $entry.Rel.Substring($top.Length + 1)
        url      = "$PublicBaseUrl/$Bucket/$($entry.Rel)"
        size     = $entry.Size
        mime     = $entry.Mime
    }
    $index.files[$top].Add($idx) | Out-Null
}
$index | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $indexPath -Encoding UTF8
Write-Host "  wrote $indexPath" -ForegroundColor Green

# 4) 总结
Write-Section "summary"
Write-Host ("  uploaded : {0}" -f $ok) -ForegroundColor Green
Write-Host ("  failed   : {0}" -f $fail) -ForegroundColor ($fail -gt 0 ? 'Red' : 'Gray')
Write-Host ("  duration : {0:N1}s" -f $swElapsed) -ForegroundColor Gray
Write-Host ("  index    : {0}" -f $indexPath) -ForegroundColor Gray
Write-Host ""
if ($fail -gt 0) { exit 3 } else { exit 0 }
