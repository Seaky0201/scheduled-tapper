# 一键构建 release APK
# 本机网络情况：dl.google.com 与 plugins.gradle.org 不可达（直连被重置、走代理 000），
# 而 maven.aliyun.com / storage.googleapis.com / repo1.maven.org 可直连，
# 因此 Maven 仓库全部改用阿里云镜像（见 android/settings.gradle.kts）。
# 切勿在此设置 http_proxy/https_proxy：会导致可直连的 storage.googleapis.com 解析失败。
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$Root = $PSScriptRoot
$TC = Join-Path $Root '.toolchain'

$env:JAVA_HOME = Join-Path $TC 'jdk17'
$env:ANDROID_HOME = Join-Path $TC 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + (Join-Path $env:ANDROID_HOME 'platform-tools') + ';' + $env:PATH

$flutter = Join-Path $TC 'flutter\bin\flutter.bat'
$gradle = Join-Path $TC 'gradle-9.3.1\bin\gradle.bat'
if (-not (Test-Path $flutter)) { throw "找不到 Flutter SDK: $flutter" }
if (-not (Test-Path $gradle)) { throw "找不到 Gradle: $gradle" }
if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { throw "找不到 JDK: $env:JAVA_HOME" }

Set-Location $Root

Write-Host '[1/3] flutter pub get'
& $flutter pub get
if ($LASTEXITCODE -ne 0) { throw 'pub get 失败' }

Write-Host '[2/3] 原生单元测试 (TapMathTest：坐标换算 / 定时对齐 / 持久化)'
Set-Location (Join-Path $Root 'android')
& $gradle :app:testDebugUnitTest --console=plain
if ($LASTEXITCODE -ne 0) { throw '单元测试失败' }
Set-Location $Root

Write-Host '[3/3] flutter build apk --release'
& $flutter build apk --release
if ($LASTEXITCODE -ne 0) { throw "构建失败，exit=$LASTEXITCODE" }

$apk = Join-Path $Root 'build\app\outputs\flutter-apk\app-release.apk'
Write-Host ("APK: " + $apk + "  (" + [math]::Round((Get-Item $apk).Length/1MB,1) + " MB)")
