$ErrorActionPreference = 'Stop'

$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$installDir = Join-Path $sdkRoot 'platform-tools'
$tempRoot = Join-Path ([IO.Path]::GetFullPath($env:TEMP)) ('rms-platform-tools-' + [guid]::NewGuid().ToString('N'))
$archive = Join-Path $tempRoot 'platform-tools.zip'
$extracted = Join-Path $tempRoot 'extracted'

try {
    New-Item -ItemType Directory -Path $tempRoot, $extracted -Force | Out-Null
    Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $archive
    Expand-Archive -LiteralPath $archive -DestinationPath $extracted -Force
    $source = Join-Path $extracted 'platform-tools'
    if (-not (Test-Path -LiteralPath (Join-Path $source 'adb.exe'))) {
        throw 'The official archive did not contain platform-tools/adb.exe.'
    }

    New-Item -ItemType Directory -Path $installDir -Force | Out-Null
    Get-ChildItem -LiteralPath $source | Copy-Item -Destination $installDir -Recurse -Force

    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $entries = @($userPath -split ';' | Where-Object { $_ })
    if (-not ($entries | Where-Object { $_.TrimEnd('\') -ieq $installDir.TrimEnd('\') })) {
        [Environment]::SetEnvironmentVariable('Path', (($entries + $installDir) -join ';'), 'User')
    }
    if (-not (($env:Path -split ';') | Where-Object { $_.TrimEnd('\') -ieq $installDir.TrimEnd('\') })) {
        $env:Path += ';' + $installDir
    }

    & (Join-Path $installDir 'adb.exe') version
    Write-Host "Installed Android platform-tools at $installDir"
    Write-Host 'Open a new PowerShell window to use adb from PATH.'
}
finally {
    $tempBase = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
    $resolvedTemp = [IO.Path]::GetFullPath($tempRoot)
    if ($resolvedTemp.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedTemp)) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
