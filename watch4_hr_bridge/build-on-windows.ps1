$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$apk = Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk'
$studioJdk = Join-Path ${env:ProgramFiles} 'Android\Android Studio\jbr'
if ((-not $env:JAVA_HOME) -and (Test-Path (Join-Path $studioJdk 'bin\java.exe'))) {
    $env:JAVA_HOME = $studioJdk
}
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    throw '找不到 JDK 17。请先安装新版 Android Studio（含 JBR），或设置 JAVA_HOME。'
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path (Join-Path $sdk 'platforms\android-35\android.jar'))) {
    throw ('找不到 Android SDK Platform 35：' + $sdk + '。请在 Android Studio SDK Manager 安装 Android 15 (API 35)。')
}
$env:ANDROID_HOME = $sdk
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
$tooling = Join-Path $project '.tooling'
$gradleDir = Join-Path $tooling 'gradle-8.9'
$gradleExe = Join-Path $gradleDir 'bin\gradle.bat'
if (-not (Test-Path $gradleExe)) {
    New-Item -ItemType Directory -Force -Path $tooling | Out-Null
    $zipPath = Join-Path $tooling 'gradle-8.9-bin.zip'
    Write-Host '首次使用：下载 Gradle 8.9（官方）……'
    Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' -OutFile $zipPath
    Write-Host '解压 Gradle……'
    Expand-Archive -LiteralPath $zipPath -DestinationPath $tooling -Force
    Remove-Item -LiteralPath $zipPath
}
Push-Location $project
try {
    Write-Host '正在构建 debug APK，首次需要从 Google/Maven 下载 Android Gradle Plugin……'
    & $gradleExe --no-daemon assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败，错误码 $LASTEXITCODE" }
    if (-not (Test-Path $apk)) { throw '编译命令结束，但找不到 APK。' }
    $output = Join-Path $project 'Watch4HR-test.apk'
    Copy-Item -LiteralPath $apk -Destination $output -Force
    Write-Host ('构建成功：' + $output)
} finally {
    Pop-Location
}
