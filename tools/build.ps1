# SPDX-License-Identifier: GPL-3.0-or-later
# Builds the mod jars with a plain JDK (no Gradle, no network): javac against the
# loader/Minecraft jars that are already installed on this machine, then jar.
#
# Two targets share the same functional sources (src/main): only the mod entry point,
# the loader metadata and the Minecraft/loader compile classpath differ.
#
#   pwsh tools\build.ps1                       # 1.20.1 / Forge 47.4.10
#   pwsh tools\build.ps1 -Target fabric1201    # 1.20.1 / Fabric (loader 0.19.5)
#   pwsh tools\build.ps1 -Target fabric1211    # 1.21.1 / Fabric
#   pwsh tools\build.ps1 -Target neoforge1211  # 1.21.1 / NeoForge 21.1.x
#   pwsh tools\build.ps1 -Target all           # all three + the sources jar
param(
  [ValidateSet('forge1201', 'fabric1201', 'fabric1211', 'neoforge1211', 'all')]
  [string]$Target = 'forge1201'
)

$ErrorActionPreference = 'Stop'
$root = 'D:\DSH\WATERMeDIA Android Bridge'
$mc = 'D:\tools\PCL2\.minecraft'
$version = '1.0.5'
$modId = 'watermedia_android_bridge'

# ------------------------------------------------------------------ targets
$targets = [ordered]@{
  fabric1201   = @{
    label     = 'Minecraft 1.20.1 / Fabric (loader 0.19.5+, Fabric API 0.92.x)'
    mcVersion = '1.20.1'
    packFormat = 15
    suffix    = '+mc1.20.1-fabric'
    loader    = 'fabric'
    # Fabric reads the mixin configs from fabric.mod.json, no manifest attribute needed.
    manifest  = @{}
    # compile-only intermediary stub for net.minecraft.class_310 (see tools\fabric-stubs);
    # the stub is valid for every Fabric target because intermediary names are stable
    stubs     = @('tools\fabric-stubs')
    classpath = @(
      (Join-Path $mc 'libraries\net\fabricmc\fabric-loader\0.19.5\fabric-loader-0.19.5.jar'),
      # net.fabricmc.fabric.api.* lives in Fabric API's nested module jars; the bundle jar
      # itself only contains them under META-INF/jars, which javac cannot see, so
      # tools/download-deps.ps1 (or the extraction in the README) unpacks them here.
      (Join-Path $root 'vendor\downloads\fabric-api-modules\*'),
      (Join-Path $mc 'libraries\net\fabricmc\sponge-mixin\0.12.5+mixin.0.8.5\sponge-mixin-0.12.5+mixin.0.8.5.jar'),
      (Join-Path $mc 'libraries\org\lwjgl\lwjgl\3.3.1\lwjgl-3.3.1.jar'),
      (Join-Path $mc 'libraries\org\lwjgl\lwjgl-opengl\3.3.1\lwjgl-opengl-3.3.1.jar'),
      (Join-Path $mc 'libraries\net\java\dev\jna\jna\5.14.0\jna-5.14.0.jar'),
      (Join-Path $mc 'libraries\org\apache\logging\log4j\log4j-api\2.19.0\log4j-api-2.19.0.jar'),
      # compile against the OLDEST supported WATERMeDIA: that is what proves the 2.1.24
      # lower bound of this target's dependency range
      (Join-Path $root 'vendor\downloads\watermedia-2.1.24.jar'),
      # ClientTickEvents.EndTick's parameter type, needed to compile the tick lambda
      (Join-Path $mc 'libraries\net\minecraft\client\1.20.1-20230612.114412\client-1.20.1-20230612.114412-srg.jar')
    )
  }
  fabric1211   = @{
    label     = 'Minecraft 1.21.1 / Fabric (loader 0.19.5+, Fabric API 0.116.x)'
    mcVersion = '1.21.1'
    packFormat = 34
    suffix    = '+mc1.21.1-fabric'
    loader    = 'fabric'
    manifest  = @{}
    stubs     = @('tools\fabric-stubs')
    classpath = @(
      (Join-Path $mc 'libraries\net\fabricmc\fabric-loader\0.19.5\fabric-loader-0.19.5.jar'),
      (Join-Path $root 'vendor\downloads\fabric-api-modules-1.21.1\*'),
      (Join-Path $mc 'libraries\net\fabricmc\sponge-mixin\0.15.2+mixin.0.8.7\sponge-mixin-0.15.2+mixin.0.8.7.jar'),
      (Join-Path $mc 'libraries\org\lwjgl\lwjgl\3.3.3\lwjgl-3.3.3.jar'),
      (Join-Path $mc 'libraries\org\lwjgl\lwjgl-opengl\3.3.3\lwjgl-opengl-3.3.3.jar'),
      (Join-Path $mc 'libraries\net\java\dev\jna\jna\5.14.0\jna-5.14.0.jar'),
      (Join-Path $mc 'libraries\org\apache\logging\log4j\log4j-api\2.22.1\log4j-api-2.22.1.jar'),
      # same reasoning as the 1.20.1 Fabric target: WATERMeDIA 2.1.24 is the oldest version
      # this patch supports, so the sources are compiled against it
      (Join-Path $root 'vendor\downloads\watermedia-2.1.24.jar'),
      (Join-Path $mc 'libraries\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar')
    )
  }
  forge1201    = @{
    label     = 'Minecraft 1.20.1 / Forge 47.4.10'
    mcVersion = '1.20.1'
    suffix    = '+mc1.20.1-forge'
    loader    = 'forge'
    # Forge has no [[mixins]] support in mods.toml; the config is announced through the
    # jar manifest, which is the mechanism ForgeModLoader has used since 1.16.
    manifest  = @{ MixinConfigs = "$modId.mixins.json" }
    classpath = @(
      (Join-Path $root 'vendor\downloads\forge-1.20.1-47.4.10-universal.jar'),
      # net.minecraftforge.fml.common.Mod lives in javafmllanguage, Dist in mergetool
      (Join-Path $root 'vendor\downloads\javafmllanguage-1.20.1-47.4.10.jar'),
      (Join-Path $root 'vendor\downloads\mergetool-1.1.5-api.jar'),
      (Join-Path $mc 'libraries\net\minecraftforge\fmlcore\1.20.1-47.4.13\fmlcore-1.20.1-47.4.13.jar'),
      (Join-Path $mc 'libraries\net\minecraftforge\eventbus\6.0.5\eventbus-6.0.5.jar'),
      (Join-Path $mc 'libraries\net\fabricmc\sponge-mixin\0.12.5+mixin.0.8.5\sponge-mixin-0.12.5+mixin.0.8.5.jar'),
      (Join-Path $mc 'libraries\org\lwjgl\lwjgl\3.3.1\lwjgl-3.3.1.jar'),
      (Join-Path $mc 'libraries\org\lwjgl\lwjgl-opengl\3.3.1\lwjgl-opengl-3.3.1.jar'),
      (Join-Path $mc 'libraries\net\java\dev\jna\jna\5.14.0\jna-5.14.0.jar'),
      (Join-Path $mc 'libraries\org\apache\logging\log4j\log4j-api\2.19.0\log4j-api-2.19.0.jar'),
      (Join-Path $root 'vendor\downloads\watermedia-2.1.36.jar'),
      (Join-Path $mc 'libraries\net\minecraft\client\1.20.1-20230612.114412\client-1.20.1-20230612.114412-srg.jar')
    )
  }
  neoforge1211 = @{
    label     = 'Minecraft 1.21.1 / NeoForge 21.1.235+'
    mcVersion = '1.21.1'
    suffix    = '+mc1.21.1-neoforge'
    loader    = 'neoforge'
    # NeoForge registers mixin configs through [[mixins]] in neoforge.mods.toml.
    manifest  = @{}
    classpath = @(
      (Join-Path $mc 'libraries\net\neoforged\neoforge\21.1.235\neoforge-21.1.235-universal.jar'),
      (Join-Path $mc 'libraries\net\neoforged\neoforge\21.1.235\neoforge-21.1.235-client.jar'),
      (Join-Path $mc 'libraries\net\neoforged\fancymodloader\loader\4.0.44\loader-4.0.44.jar'),
      (Join-Path $mc 'libraries\net\neoforged\bus\8.0.5\bus-8.0.5.jar'),
      (Join-Path $mc 'libraries\net\neoforged\mergetool\2.0.0\mergetool-2.0.0-api.jar'),
      (Join-Path $mc 'libraries\net\fabricmc\sponge-mixin\0.15.2+mixin.0.8.7\sponge-mixin-0.15.2+mixin.0.8.7.jar'),
      (Join-Path $mc 'libraries\org\lwjgl\lwjgl\3.3.3\lwjgl-3.3.3.jar'),
      # GL11 lives in lwjgl-opengl, not in the lwjgl core jar (VideoUpload needs it)
      (Join-Path $mc 'libraries\org\lwjgl\lwjgl-opengl\3.3.3\lwjgl-opengl-3.3.3.jar'),
      (Join-Path $mc 'libraries\net\java\dev\jna\jna\5.14.0\jna-5.14.0.jar'),
      (Join-Path $mc 'libraries\org\apache\logging\log4j\log4j-api\2.22.1\log4j-api-2.22.1.jar'),
      (Join-Path $root 'vendor\downloads\watermedia-2.1.36.jar'),
      (Join-Path $mc 'libraries\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar')
    )
  }
}

function Find-Jdk {
  $candidates = @()
  if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\javac.exe') }
  $runtimeRoot = Join-Path $env:APPDATA '.minecraft\runtime'
  foreach ($name in @('java-runtime-delta', 'java-runtime-epsilon', 'java-runtime-beta')) {
    $candidates += (Join-Path $runtimeRoot "$name\bin\javac.exe")
  }
  $onPath = Get-Command javac.exe -ErrorAction SilentlyContinue
  if ($onPath) { $candidates += $onPath.Source }
  foreach ($candidate in $candidates) {
    if ($candidate -and (Test-Path $candidate)) { return $candidate }
  }
  throw 'no javac found - install a JDK 17+ or set JAVA_HOME'
}

$javac = Find-Jdk
$jarExe = Join-Path (Split-Path $javac) 'jar.exe'
Write-Host "javac: $javac"
Write-Host "mod version: $version"
Write-Host "build target: $Target"

# ------------------------------------------------------- licence hygiene (SPDX)
$missingSpdx = @()
foreach ($file in @(Get-ChildItem (Join-Path $root 'src') -Recurse -Filter *.java) +
                   @(Get-ChildItem (Join-Path $root 'tools') -Recurse -Include *.java, *.ps1)) {
  if (-not (Select-String -Path $file.FullName -Pattern 'SPDX-License-Identifier: GPL-3.0-or-later' -List -Quiet)) {
    $missingSpdx += $file.FullName
  }
}
if ($missingSpdx.Count -gt 0) {
  throw "these sources are missing an SPDX licence header: $($missingSpdx -join ', ')"
}
Write-Host 'SPDX licence headers present on every source file'

$distDir = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $distDir | Out-Null

# Copies a resource tree, expanding ${token} placeholders in the text resources.  Binary
# resources (the native payload, images) are copied verbatim, and a text file without any
# placeholder is written back unchanged.
function Copy-Resources([string]$from, [string]$to, [hashtable]$tokens) {
  $textExtensions = @('.json', '.toml', '.mcmeta', '.cfg', '.txt', '.properties')
  foreach ($file in Get-ChildItem $from -Recurse -File) {
    $relative = $file.FullName.Substring($from.Length).TrimStart('\', '/')
    $destination = Join-Path $to $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
    if ($textExtensions -contains $file.Extension.ToLowerInvariant()) {
      $text = [System.IO.File]::ReadAllText($file.FullName)
      $expanded = $text
      foreach ($key in $tokens.Keys) {
        $expanded = $expanded.Replace('${' + $key + '}', [string]$tokens[$key])
      }
      if ($expanded -match '\$\{[a-zA-Z]+\}') {
        throw "unexpanded placeholder in $relative : $($Matches[0])"
      }
      [System.IO.File]::WriteAllText($destination, $expanded, (New-Object System.Text.UTF8Encoding($false)))
    } else {
      Copy-Item $file.FullName $destination -Force
    }
  }
}

function Build-Target([string]$name) {
  $target = $targets[$name]
  Write-Host ''
  Write-Host "================ $name - $($target.label) ================"

  $cp = @()
  foreach ($entry in $target.classpath) {
    if (Test-Path $entry) { $cp += $entry }
    else { Write-Host "[warn] missing compile dependency: $entry" }
  }
  $cp = $cp -join ';'

  $classes = Join-Path $root "build\classes\$name"
  if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $classes | Out-Null

  # Compile the target's compile-only stubs (Fabric needs an intermediary name for the
  # client class, see tools\fabric-stubs) into their own directory: they go on the
  # classpath but must never end up inside the mod jar.
  if ($target.stubs) {
    $stubOut = Join-Path $root "build\stubs\$name"
    if (Test-Path $stubOut) { Remove-Item $stubOut -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $stubOut | Out-Null
    foreach ($stubRoot in $target.stubs) {
      $stubSources = @(Get-ChildItem (Join-Path $root $stubRoot) -Recurse -Filter *.java |
          ForEach-Object { $_.FullName })
      if ($stubSources.Count -gt 0) {
        & $javac '-encoding', 'UTF-8', '--release', '17', '-proc:none',
          '-nowarn', '-d', $stubOut @stubSources
        if ($LASTEXITCODE -ne 0) { throw "stub compilation failed for $name (exit $LASTEXITCODE)" }
        Write-Host "compiled $($stubSources.Count) compile-only stub(s) into $stubOut"
      }
    }
    $cp = "$stubOut;$cp"
  }

  $sources = @(Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java |
      ForEach-Object { $_.FullName })
  $sources += @(Get-ChildItem (Join-Path $root "src\loader\$($target.loader)\java") -Recurse -Filter *.java |
      ForEach-Object { $_.FullName })
  Write-Host "compiling $($sources.Count) source file(s) with --release 17"

  # the workspace path contains spaces, so the sources are passed as real argv entries
  # instead of through a javac argument file.
  # -proc:none: the sponge-mixin annotation processor must not run (it aborts javac when
  # ASM is not on the processor path).
  # -Xlint:-path: WATERMeDIA's manifest Class-Path names jars that only exist in a real
  # installation, which is irrelevant for compilation.
  $javacArgs = @('-encoding', 'UTF-8', '--release', '17', '-proc:none',
    '-Xlint:all', '-Xlint:-serial', '-Xlint:-path',
    '-classpath', $cp, '-d', $classes) + $sources
  & $javac @javacArgs
  if ($LASTEXITCODE -ne 0) { throw "compilation failed for $name (exit $LASTEXITCODE)" }

  # Stage the resources with per-target token expansion: the loader metadata of the Fabric
  # targets differs only in the Minecraft version and the pack format, so those files carry
  # ${mcVersion}/${packFormat} placeholders instead of being duplicated per Minecraft version.
  $tokens = @{
    modId       = $modId
    modVersion  = $version
    mcVersion   = $target.mcVersion
    packFormat  = $target.packFormat
    loader      = $target.loader
  }
  Copy-Resources (Join-Path $root 'src\main\resources') $classes $tokens
  Copy-Resources (Join-Path $root "src\loader\$($target.loader)\resources") $classes $tokens
  Write-Host "classes + resources staged in $classes"

  $jarName = "$modId-$version$($target.suffix).jar"
  $jarPath = Join-Path $distDir $jarName
  if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
  $manifest = Join-Path $root "build\MANIFEST-$name.MF"
  $manifestLines = @(
    'Manifest-Version: 1.0',
    'Implementation-Title: WATERMeDIA: Android Bridge',
    "Implementation-Version: $version"
  )
  foreach ($key in $target.manifest.Keys) {
    $manifestLines += "${key}: $($target.manifest[$key])"
  }
  Set-Content -Path $manifest -Encoding ASCII -Value $manifestLines
  & $jarExe --create --file $jarPath --manifest $manifest -C $classes .
  if ($LASTEXITCODE -ne 0) { throw "jar creation failed for $name (exit $LASTEXITCODE)" }

  Write-Host ("built {0} ({1:N1} MiB)" -f $jarPath, ((Get-Item $jarPath).Length / 1MB))
  return $jarPath
}

# ---------------------------------------------------------------- sources jar
function Build-SourcesJar {
  $sourceJar = Join-Path $distDir "$modId-$version-sources.jar"
  if (Test-Path $sourceJar) { Remove-Item $sourceJar -Force }
  # The source archive must not contain the ~127 MiB native payload; tools/pack-payload.ps1
  # rebuilds it from the official VLC APKs.
  $srcStage = Join-Path $root 'build\sources-stage'
  if (Test-Path $srcStage) { Remove-Item $srcStage -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $srcStage | Out-Null
  Copy-Item (Join-Path $root 'src\main\java') -Destination $srcStage -Recurse -Force
  foreach ($loader in @('forge', 'fabric', 'neoforge')) {
    $dest = Join-Path $srcStage "loader\$loader"
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item (Join-Path $root "src\loader\$loader\java") -Destination $dest -Recurse -Force
    Copy-Item (Join-Path $root "src\loader\$loader\resources") -Destination $dest -Recurse -Force
  }
  New-Item -ItemType Directory -Force -Path (Join-Path $srcStage 'resources') | Out-Null
  Copy-Item (Join-Path $root 'src\main\resources\META-INF') -Destination (Join-Path $srcStage 'resources') -Recurse -Force
  Copy-Item (Join-Path $root 'src\main\resources\watermedia_android_bridge.mixins.json') -Destination (Join-Path $srcStage 'resources') -Force
  # the Fabric target needs this compile-only intermediary stub to build at all
  New-Item -ItemType Directory -Force -Path (Join-Path $srcStage 'tools') | Out-Null
  Copy-Item (Join-Path $root 'tools\fabric-stubs') -Destination (Join-Path $srcStage 'tools') -Recurse -Force
  Copy-Item (Join-Path $root 'LICENSE') -Destination $srcStage -Force
  & $jarExe --create --file $sourceJar -C $srcStage .
  if ($LASTEXITCODE -ne 0) { throw 'source jar creation failed' }
  Write-Host ''
  Write-Host "built $sourceJar"
}

$selected = if ($Target -eq 'all') { @('forge1201', 'fabric1201', 'fabric1211', 'neoforge1211') } else { @($Target) }
$built = @()
foreach ($name in $selected) { $built += (Build-Target $name) }
Build-SourcesJar

Write-Host ''
foreach ($jar in $built) {
  $hash = (Get-FileHash $jar -Algorithm SHA256).Hash
  Write-Host ("{0}`n  {1}  ({2:N0} B)" -f $jar, $hash, (Get-Item $jar).Length)
}
Write-Host 'done'
