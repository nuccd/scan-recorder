param(
  [string[]]$Tasks = @(':recorder:assembleBundledTuning'),
  [string]$JdkHome
)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$cacheDir = Join-Path $projectDir '.gradle-user-home'
$tempDir = Join-Path $projectDir '.local-temp'
New-Item -ItemType Directory -Path $cacheDir, $tempDir -Force | Out-Null

$jdkCandidates = @($JdkHome, $env:JAVA_HOME)
$javaInstallDir = Join-Path $env:ProgramFiles 'Java'
if (Test-Path -LiteralPath $javaInstallDir) {
  $jdkCandidates += Get-ChildItem -LiteralPath $javaInstallDir -Directory | Select-Object -ExpandProperty FullName
}
$selectedJdk = $null
foreach ($candidate in $jdkCandidates) {
  if ([string]::IsNullOrEmpty($candidate)) { continue }
  $javaExe = Join-Path $candidate 'bin\java.exe'
  if (!(Test-Path -LiteralPath $javaExe)) { continue }
  $versionText = (& $javaExe -version 2>&1 | Out-String)
  if ($versionText -match 'version "(?:1\.)?(\d+)' -and [int]$Matches[1] -ge 17) {
    $selectedJdk = $candidate
    break
  }
}
if (!$selectedJdk) { throw '需要 JDK 17 或更高版本。可使用 -JdkHome 指定 JDK / Android Studio jbr 路径。' }

$previousEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'GRADLE_USER_HOME', 'TEMP', 'TMP')) {
  $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$buildExitCode = 1
try {
  $env:JAVA_HOME = $selectedJdk
  $env:GRADLE_USER_HOME = $cacheDir
  $env:TEMP = $tempDir
  $env:TMP = $tempDir
  Write-Host "Gradle 缓存：$cacheDir"
  Write-Host "临时目录：$tempDir"
  Push-Location -LiteralPath $projectDir
  try {
    & (Join-Path $projectDir 'gradlew.bat') @Tasks
    $buildExitCode = $LASTEXITCODE
  } finally {
    Pop-Location
  }
} finally {
  foreach ($name in $previousEnvironment.Keys) {
    [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
  }
}
exit $buildExitCode
