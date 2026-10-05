# SPDX-License-Identifier: GPL-3.0-or-later
$ErrorActionPreference = 'Continue'
$root = 'D:\DSH\WATERMeDIA Android Bridge'
$dl = Join-Path $root 'vendor\downloads'
New-Item -ItemType Directory -Force -Path $dl | Out-Null

function Get-File($url, $out) {
  if ((Test-Path $out) -and ((Get-Item $out).Length -gt 1024)) {
    Write-Host "[skip] $out ($((Get-Item $out).Length) bytes)"
    return $true
  }
  Write-Host "[get ] $url"
  & curl.exe -sSL --fail --retry 3 --retry-delay 2 -o $out $url
  if ($LASTEXITCODE -ne 0) { Write-Host "[FAIL] $url"; return $false }
  $len = (Get-Item $out).Length
  Write-Host "[ok  ] $out ($len bytes)"
  return $true
}

Write-Host "===== WATERMeDIA core ====="
Get-File 'https://cdn.modrinth.com/data/G922NeHS/versions/yezkkXsZ/watermedia-2.1.36.jar'          "$dl\watermedia-2.1.36.jar"
Get-File 'https://cdn.modrinth.com/data/G922NeHS/versions/yezkkXsZ/watermedia-2.1.36-sources.jar'  "$dl\watermedia-2.1.36-sources.jar"
Get-File 'https://cdn.modrinth.com/data/G922NeHS/versions/fB0LmHnR/watermedia-2.1.37-sources.jar'  "$dl\watermedia-2.1.37-sources.jar"

Write-Host "===== WATERMeDIA Binaries (neoforge 1.21.1) ====="
$binJson = "$dl\watermedia-binaries-versions.json"
Get-File 'https://api.modrinth.com/v2/project/watermedia-binaries/version?loaders=%5B%22neoforge%22%5D' $binJson | Out-Null
try {
  $vers = Get-Content $binJson -Raw | ConvertFrom-Json
  foreach ($v in $vers) {
    if ($v.game_versions -contains '1.21.1') {
      $f = $v.files | Where-Object { $_.primary -eq $true } | Select-Object -First 1
      if ($f) {
        Write-Host "[info] binaries version $($v.version_number) -> $($f.filename)"
        Get-File $f.url "$dl\$($f.filename)" | Out-Null
        break
      }
    }
  }
} catch { Write-Host "[WARN] binaries parse failed: $_" }

Write-Host "===== VLC Android (libvlc-all AAR) ====="
$meta = "$dl\libvlc-all-maven-metadata.xml"
Get-File 'https://repo1.maven.org/maven2/org/videolan/android/libvlc-all/maven-metadata.xml' $meta | Out-Null
$latest = $null
if (Test-Path $meta) {
  [xml]$x = Get-Content $meta -Raw
  $latest = $x.metadata.versioning.release
  if (-not $latest) { $latest = $x.metadata.versioning.latest }
  Write-Host "[info] libvlc-all latest = $latest"
  Write-Host "[info] all versions: $($x.metadata.versioning.versions.version -join ', ')"
}
if ($latest) {
  Get-File "https://repo1.maven.org/maven2/org/videolan/android/libvlc-all/$latest/libvlc-all-$latest.aar" "$dl\libvlc-all-$latest.aar" | Out-Null
}

Write-Host "===== Forge 1.20.1 compile dependencies ====="
# tools/build.ps1 -Target forge1201 needs these three: the universal jar carries the
# event API, @Mod lives in javafmllanguage and net.minecraftforge.api.distmarker.Dist
# in mergetool-api.  The rest of that target's classpath comes from the local launcher
# libraries (LWJGL 3.3.1, eventbus 6.0.5, sponge-mixin 0.12.5, fmlcore).
$forgeMc = '1.20.1'
$forgeVersion = '47.4.10'
$forgeBase = "https://maven.minecraftforge.net/net/minecraftforge"
Get-File "$forgeBase/forge/$forgeMc-$forgeVersion/forge-$forgeMc-$forgeVersion-universal.jar" "$dl\forge-$forgeMc-$forgeVersion-universal.jar" | Out-Null
Get-File "$forgeBase/javafmllanguage/$forgeMc-$forgeVersion/javafmllanguage-$forgeMc-$forgeVersion.jar" "$dl\javafmllanguage-$forgeMc-$forgeVersion.jar" | Out-Null
Get-File "$forgeBase/mergetool/1.1.5/mergetool-1.1.5-api.jar" "$dl\mergetool-1.1.5-api.jar" | Out-Null

Write-Host "===== Fabric compile dependencies (1.20.1 + 1.21.1) ====="
# The Fabric targets need fabric-loader (the entrypoint interfaces) and the Fabric API module
# jars.  The Fabric API distribution hides every module inside META-INF/jars/, which javac
# cannot read, so each bundle is unpacked next to it; the build script then puts that whole
# directory on the classpath of the matching target.
Get-File 'https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar' "$dl\fabric-loader-0.19.5.jar" | Out-Null
# both Fabric targets are compiled against the OLDEST supported WATERMeDIA, so the 2.1.24 jar is a
# build dependency as well (tools/itest.ps1 additionally runs a runtime pass with it)
Get-File 'https://cdn.modrinth.com/data/G922NeHS/versions/HlUiSWvC/watermedia-2.1.24.jar' "$dl\watermedia-2.1.24.jar" | Out-Null

function Expand-FabricApi([string]$bundlePattern, [string]$url, [string]$targetDirectory) {
  $bundle = Get-ChildItem $dl -Filter $bundlePattern -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $bundle) {
    Get-File $url (Join-Path $dl (Split-Path $url -Leaf).Replace('%2B', '+')) | Out-Null
    $bundle = Get-ChildItem $dl -Filter $bundlePattern -ErrorAction SilentlyContinue | Select-Object -First 1
  }
  if (-not $bundle -or $bundle.Length -lt 100000) {
    Write-Host "[WARN] no $bundlePattern bundle - extract META-INF/jars/*.jar from any Fabric API jar into $targetDirectory"
    return
  }
  New-Item -ItemType Directory -Force -Path $targetDirectory | Out-Null
  try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($bundle.FullName)
    $count = 0
    foreach ($entry in $zip.Entries | Where-Object { $_.FullName -match '^META-INF/jars/.*\.jar$' }) {
      [System.IO.Compression.ZipFileExtensions]::ExtractToFile(
        $entry, (Join-Path $targetDirectory (Split-Path $entry.FullName -Leaf)), $true)
      $count++
    }
    $zip.Dispose()
    Write-Host "[ok  ] unpacked $count Fabric API modules from $($bundle.Name) into $targetDirectory"
  } catch {
    Write-Host "[FAIL] could not unpack the Fabric API modules: $_"
  }
}

Expand-FabricApi 'fabric-api-0.92*.jar' `
  'https://cdn.modrinth.com/data/P7dR8mSH/versions/8mQd2f4F/fabric-api-0.92.12%2B1.20.1.jar' `
  (Join-Path $dl 'fabric-api-modules')
Expand-FabricApi 'fabric-api-0.11*.jar' `
  'https://cdn.modrinth.com/data/P7dR8mSH/versions/Mys3P7lK/fabric-api-0.116.17%2B1.21.1.jar' `
  (Join-Path $dl 'fabric-api-modules-1.21.1')

Write-Host "===== DONE ====="
Get-ChildItem $dl | Select-Object Name, Length | Format-Table -AutoSize
