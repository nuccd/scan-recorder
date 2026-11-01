param(
  [string]$Compiler,
  [string]$AdbDirectory = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools')
)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectDir 'build/windows-receiver/0.5.2'
$tempDir = Join-Path $projectDir '.local-temp'
New-Item -ItemType Directory -Path $outputDir, $tempDir -Force | Out-Null
if (!$Compiler) { $Compiler = (Get-Command g++.exe -ErrorAction Stop).Source }
$oldTemp = $env:TEMP
$oldTmp = $env:TMP
try {
  $env:TEMP = $tempDir
  $env:TMP = $tempDir
  $flags = @('-std=c++20', '-O2', '-Wall', '-Wextra', '-Werror', '-static', '-static-libgcc',
    '-static-libstdc++', '-DUNICODE', '-D_UNICODE', '-DNOMINMAX', '-D_WIN32_WINNT=0x0A00',
    '-finput-charset=UTF-8', '-Wl,--dynamicbase', '-Wl,--nxcompat')
  & $Compiler @flags '-municode' '-mwindows' (Join-Path $projectDir 'windows-receiver/src/main.cpp') `
    '-o' (Join-Path $outputDir 'scan-recorder-receiver.exe') '-lws2_32' '-luser32' '-lcomdlg32' '-lgdi32'
  if ($LASTEXITCODE -ne 0) { throw 'Windows receiver build failed' }
  $testExe = Join-Path $projectDir 'build/keyboard/windows-protocol-test.exe'
  New-Item -ItemType Directory -Path (Split-Path -Parent $testExe) -Force | Out-Null
  & $Compiler @flags (Join-Path $projectDir 'windows-receiver/tests/protocol_test.cpp') '-o' $testExe '-luser32'
  if ($LASTEXITCODE -ne 0) { throw 'Protocol test build failed' }
  & $testExe
  if ($LASTEXITCODE -ne 0) { throw 'Protocol tests failed' }
  $adbTestExe = Join-Path $projectDir 'build/keyboard/windows-adb-test.exe'
  & $Compiler @flags '-municode' (Join-Path $projectDir 'windows-receiver/tests/adb_process_test.cpp') '-o' $adbTestExe
  if ($LASTEXITCODE -ne 0) { throw 'ADB process test build failed' }
  & $adbTestExe
  if ($LASTEXITCODE -ne 0) { throw 'ADB process tests failed' }
  $uiTestExe = Join-Path $projectDir 'build/keyboard/windows-receiver-ui-test.exe'
  & $Compiler @flags '-municode' (Join-Path $projectDir 'windows-receiver/tests/receiver_ui_test.cpp') '-o' $uiTestExe '-luser32'
  if ($LASTEXITCODE -ne 0) { throw 'Receiver UI test build failed' }
  foreach ($name in @('adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'NOTICE.txt')) {
    $source = Join-Path $AdbDirectory $name
    if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $outputDir -Force }
  }
  Copy-Item -LiteralPath (Join-Path $projectDir 'windows-receiver/README.txt') -Destination $outputDir -Force
  Copy-Item -LiteralPath (Join-Path $projectDir 'LICENSE') -Destination (Join-Path $outputDir 'LICENSE.txt') -Force
  $packageNames = @('scan-recorder-receiver.exe', 'README.txt', 'adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll',
    'NOTICE.txt', 'LICENSE.txt')
  $packageFiles = Get-ChildItem -LiteralPath $outputDir -File | Where-Object { $_.Name -in $packageNames }
  Compress-Archive -LiteralPath $packageFiles.FullName -DestinationPath (Join-Path $projectDir 'build/windows-receiver/scan-recorder-receiver-0.5.2-win-x64.zip') -Force
  Write-Host "Windows package: $outputDir"
} finally {
  $env:TEMP = $oldTemp
  $env:TMP = $oldTmp
}
