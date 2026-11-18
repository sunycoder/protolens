param([ValidateSet('Read','Write','CompareWrite')][string]$Action = 'Read')
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
# 只管理当前用户默认 LAN；不修改 VPN、路由、TUN 或 WinHTTP，不需要管理员权限。
$source = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'WindowsSystemProxy.cs'), [Text.Encoding]::UTF8)
Add-Type -TypeDefinition $source
function Assert-ProxySnapshot($value) {
    if ($null -eq $value -or $null -eq $value.flags -or
        $value.server -isnot [string] -or $value.bypass -isnot [string] -or $value.autoConfigUrl -isnot [string]) {
        throw 'Invalid proxy snapshot; no system setting was changed.'
    }
}
if ($Action -ne 'Read') {
    $inputValue = [Console]::In.ReadToEnd() | ConvertFrom-Json
    $settings = $inputValue
    if ($Action -eq 'CompareWrite') {
        Assert-ProxySnapshot $inputValue.expected
        $settings = $inputValue.settings
        $current = [ProtoLensSystemProxy]::Read()
        foreach ($key in @('flags','server','bypass','autoConfigUrl')) {
            if ([string]$current[$key] -cne [string]$inputValue.expected.$key) {
                [Console]::Error.Write('PROXY_CHANGED')
                exit 3
            }
        }
    }
    Assert-ProxySnapshot $settings
    [ProtoLensSystemProxy]::Write([int]$settings.flags,[string]$settings.server,[string]$settings.bypass,[string]$settings.autoConfigUrl)
}
[Console]::Write(([ProtoLensSystemProxy]::Read() | ConvertTo-Json -Compress))
