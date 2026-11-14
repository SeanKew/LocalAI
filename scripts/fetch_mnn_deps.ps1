$ErrorActionPreference = "Stop"

# MNNChat 0.8.3 APK ships an arm64-v8a libMNN.so with LLM enabled.
# MNN 3.5.0 public headers are downloaded from the official Alibaba/MNN tag.
# Confirm the exact engine version/ABI before shipping; never mix arbitrary headers and binaries.
$repoRoot = Split-Path -Parent $PSScriptRoot
$workDir = Join-Path $env:TEMP "LocalAI-MNN-deps"
$apkPath = Join-Path $workDir "mnn_chat_0_8_3.apk"
$zipPath = Join-Path $workDir "MNN-3.5.0.zip"
$apkZipPath = Join-Path $workDir "mnn_chat_0_8_3.zip"
$apkExtract = Join-Path $workDir "apk"
$sourceExtract = Join-Path $workDir "source"
$mainDir = Join-Path $repoRoot "app\src\main"
$libDir = Join-Path $mainDir "jniLibs\arm64-v8a"
$includeDir = Join-Path $mainDir "cpp\include"

New-Item -ItemType Directory -Force -Path $workDir, $apkExtract, $sourceExtract, $libDir, $includeDir, (Join-Path $mainDir "cpp\lib") | Out-Null

$apkUrl = "https://meta.alicdn.com/data/mnn/apks/mnn_chat_0_8_3.apk"
$sourceUrl = "https://github.com/alibaba/MNN/archive/refs/tags/3.5.0.zip"

Write-Host "[1/5] Downloading official MNNChat Android APK..."
Invoke-WebRequest -Uri $apkUrl -OutFile $apkPath
if (-not (Test-Path $apkPath) -or (Get-Item $apkPath).Length -lt 1000000) {
    throw "APK download failed or file is unexpectedly small: $apkUrl"
}

Write-Host "[2/5] Extracting lib/arm64-v8a/libMNN.so..."
Copy-Item $apkPath $apkZipPath -Force
Expand-Archive -LiteralPath $apkZipPath -DestinationPath $apkExtract -Force
$apkSo = Join-Path $apkExtract "lib\arm64-v8a\libMNN.so"
if (-not (Test-Path $apkSo)) {
    throw "The APK did not contain lib/arm64-v8a/libMNN.so. Check the APK URL/version."
}
Copy-Item $apkSo (Join-Path $libDir "libMNN.so") -Force

Write-Host "[3/5] Downloading official MNN 3.5.0 headers..."
Invoke-WebRequest -Uri $sourceUrl -OutFile $zipPath
Expand-Archive -LiteralPath $zipPath -DestinationPath $sourceExtract -Force
$sourceRoot = Join-Path $sourceExtract "MNN-3.5.0"
$coreHeaders = Join-Path $sourceRoot "include\MNN"
$llmHeaders = Join-Path $sourceRoot "transformers\llm\engine\include\llm"
if (-not (Test-Path $coreHeaders) -or -not (Test-Path (Join-Path $llmHeaders "llm.hpp"))) {
    throw "Expected MNN 3.5.0 headers were not found in the source archive."
}
Copy-Item $coreHeaders (Join-Path $includeDir "MNN") -Recurse -Force
Copy-Item $llmHeaders (Join-Path $includeDir "llm") -Recurse -Force

Write-Host "[4/5] Writing dependency provenance..."
$provenance = @"
MNN Android native dependency
==============================
Shared library: libMNN.so
ABI: arm64-v8a
Binary source: $apkUrl
Header source: $sourceUrl
Note: official MNNChat packages the LLM runtime in libMNN.so; there is no separate libllm.so in this package.
Verify binary/header compatibility and license terms before distribution.
"@
Set-Content -LiteralPath (Join-Path $libDir "DEPENDENCY_PROVENANCE.txt") -Value $provenance -Encoding UTF8

Write-Host "[5/5] Checking required files..."
$required = @(
    (Join-Path $libDir "libMNN.so"),
    (Join-Path $includeDir "MNN\MNNDefine.h"),
    (Join-Path $includeDir "llm\llm.hpp")
)
foreach ($file in $required) {
    if (-not (Test-Path $file)) { throw "Dependency setup incomplete: $file" }
}
Get-Item $required | Select-Object FullName, Length | Format-Table -AutoSize
Write-Host "Dependency files prepared. Review the MNN binary/header version match before building."
Write-Host "The script intentionally does not commit files or download model weights."
