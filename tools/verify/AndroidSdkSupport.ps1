function Get-TraceboxAndroidSdkRoot {
    param([Parameter(Mandatory = $true)][string] $RepositoryRoot)

    foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $localProperties = Join-Path $RepositoryRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($sdkLine) {
            $sdkPath = ($sdkLine -split '=', 2)[1].Replace('\\', '\').Replace('\:', ':')
            if (Test-Path -LiteralPath $sdkPath -PathType Container) {
                return (Resolve-Path -LiteralPath $sdkPath).Path
            }
        }
    }

    throw 'Android SDK root is unavailable; set ANDROID_SDK_ROOT/ANDROID_HOME or sdk.dir in local.properties'
}
