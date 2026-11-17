param(
    [switch]$Install,
    [switch]$SkipNative
)

$ErrorActionPreference = "Stop"
$proj = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$buildTools = Join-Path $sdk "build-tools\36.1.0"
$androidJar = Join-Path $sdk "platforms\android-34\android.jar"
$ndk = Join-Path $sdk "ndk\27.2.12479018"
$adb = Join-Path $sdk "platform-tools\adb.exe"

$env:ANDROID_HOME = $sdk
$env:ANDROID_NDK_HOME = $ndk
$env:Path = "$env:USERPROFILE\.cargo\bin;$env:Path"

$outDir = Join-Path $proj "build\apk"
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$unaligned = Join-Path $outDir "nav-unaligned.apk"
$aligned = Join-Path $outDir "nav-aligned.apk"
$final = Join-Path $proj "NavRS.apk"

Remove-Item $unaligned, $aligned, $final -Force -ErrorAction SilentlyContinue

if (-not $SkipNative) {
    Write-Host "Building Rust library..."
    Push-Location $proj
    try {
        & cargo ndk -t arm64-v8a --platform 29 build --release --lib
        if ($LASTEXITCODE -ne 0) { throw "cargo ndk build failed" }
    } finally {
        Pop-Location
    }
    $so = Join-Path $proj "target\aarch64-linux-android\release\libnav_rs.so"
    if (-not (Test-Path $so)) { throw "native library not found: $so" }
    Write-Host "Native: $so ($([math]::Round((Get-Item $so).Length / 1MB, 2)) MB)"
}

Write-Host "Linking manifest..."
& (Join-Path $buildTools "aapt2.exe") link `
    -o $unaligned `
    -I $androidJar `
    --manifest (Join-Path $proj "android\AndroidManifest.xml") `
    -A (Join-Path $proj "android\assets") `
    --min-sdk-version 29 `
    --target-sdk-version 34 `
    --version-code 1 `
    --version-name "0.1.0"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$stubDir = Join-Path $proj "build\stubs"
$classesDir = Join-Path $proj "build\classes"
$dexDir = Join-Path $proj "build\dex"
Remove-Item -Recurse -Force $stubDir, $classesDir, $dexDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $stubDir, $classesDir, $dexDir -Force | Out-Null

Write-Host "Compiling Xposed stubs (compile-only)..."
$stubFiles = Get-ChildItem (Join-Path $proj "stubs") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac --release 17 -encoding UTF-8 -d $stubDir $stubFiles
if ($LASTEXITCODE -ne 0) { throw "javac stubs failed" }

Write-Host "Compiling module..."
$javaFiles = Get-ChildItem (Join-Path $proj "android\java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac --release 17 -encoding UTF-8 -classpath "$androidJar;$stubDir" -d $classesDir $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac module failed" }

$classFiles = Get-ChildItem $classesDir -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& (Join-Path $buildTools "d8.bat") --min-api 29 --lib $androidJar --output $dexDir $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unaligned, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $dexBytes = [System.IO.File]::ReadAllBytes((Join-Path $dexDir "classes.dex"))
    $e = $zip.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
    $s = $e.Open(); $s.Write($dexBytes, 0, $dexBytes.Length); $s.Dispose()

    if (-not $SkipNative) {
        $soBytes = [System.IO.File]::ReadAllBytes($so)
        $e = $zip.CreateEntry("lib/arm64-v8a/libnav_rs.so", [System.IO.Compression.CompressionLevel]::NoCompression)
        $s = $e.Open(); $s.Write($soBytes, 0, $soBytes.Length); $s.Dispose()
    }
} finally {
    $zip.Dispose()
}

Write-Host "Aligning..."
& (Join-Path $buildTools "zipalign.exe") -f 4 $unaligned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$keystore = "$env:USERPROFILE\.android\debug.keystore"
if (-not (Test-Path $keystore)) {
    & keytool -genkeypair -v -keystore $keystore -storepass android `
        -alias androiddebugkey -keypass android `
        -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

Write-Host "Signing..."
& (Join-Path $buildTools "apksigner.bat") sign `
    --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --out $final $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "APK: $final ($([math]::Round((Get-Item $final).Length / 1MB, 2)) MB)"

if ($Install) {
    Write-Host "Installing..."
    & $adb install -r $final
    if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
}
