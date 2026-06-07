param([string]$Root)
Get-ChildItem -Recurse -File -LiteralPath $Root |
  Where-Object { $_.FullName -notmatch '\\target\\|\\node_modules\\|\.class$|\\\.git\\' } |
  ForEach-Object { $_.FullName.Substring($Root.Length).TrimStart('\') }
