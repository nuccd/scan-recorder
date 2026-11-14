param(
  [string]$Formatter = 'clang-format',
  [switch]$Fix
)

$ErrorActionPreference = 'Stop'
$receiverDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'windows-receiver'
$formatterPath = (Get-Command $Formatter -CommandType Application).Source
$version = & $formatterPath --version
if ($LASTEXITCODE -ne 0 -or $version -notmatch '\bclang-format version 18\.1\.8(\s|$)') {
  throw "Expected clang-format 18.1.8 to match CI. Found: $version"
}

$files = Get-ChildItem -LiteralPath $receiverDir -Recurse -File |
  Where-Object { $_.Extension -in '.cc', '.cpp', '.h', '.hpp' } |
  Sort-Object FullName

$stylePath = Join-Path $receiverDir '.clang-format'
$arguments = @("--style=file:$stylePath")
if ($Fix) {
  $arguments += '-i'
} else {
  $arguments += '--dry-run', '--Werror'
}

& $formatterPath @arguments $files.FullName
if ($LASTEXITCODE -ne 0) {
  throw 'Windows C++ formatting failed. Run scripts/format-windows.ps1 -Fix with the same formatter.'
}
