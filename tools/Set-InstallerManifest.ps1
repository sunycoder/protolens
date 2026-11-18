param(
    [Parameter(Mandatory = $true)][string]$InstallerPath
)

$ErrorActionPreference = 'Stop'
$resolvedInstaller = (Resolve-Path -LiteralPath $InstallerPath).Path
if ([IO.Path]::GetExtension($resolvedInstaller) -ne '.exe') {
    throw 'InstallerPath must point to the generated installer EXE.'
}

# 只修改构建出的安装器资源，不执行安装、不修改系统ACL。分析工作台启动EXE不经过本脚本。
# 保留MSI当前用户范围和原有升级标识；安装器以完整令牌启动，以处理卷根目录的回滚文件权限。
Add-Type @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

public static class ProtoLensInstallerManifest {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr BeginUpdateResource(string file, bool deleteExistingResources);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool UpdateResource(IntPtr update, IntPtr type, IntPtr name,
        ushort language, byte[] data, uint size);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool EndUpdateResource(IntPtr update, bool discard);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr LoadLibraryEx(string file, IntPtr reserved, uint flags);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr FindResource(IntPtr module, IntPtr name, IntPtr type);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LoadResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LockResource(IntPtr resource);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint SizeofResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32.dll")]
    private static extern bool FreeLibrary(IntPtr module);

    public static void Write(string file, byte[] manifest) {
        IntPtr update = BeginUpdateResource(file, false);
        if (update == IntPtr.Zero) throw new Win32Exception();
        bool committed = false;
        try {
            if (!UpdateResource(update, new IntPtr(24), new IntPtr(1), 1033,
                    manifest, (uint)manifest.Length)) throw new Win32Exception();
            if (!EndUpdateResource(update, false)) throw new Win32Exception();
            committed = true;
        } finally {
            if (!committed) EndUpdateResource(update, true);
        }
    }

    // LOAD_LIBRARY_AS_DATAFILE只读资源，禁止加载时执行安装器入口。
    public static string Read(string file) {
        IntPtr module = LoadLibraryEx(file, IntPtr.Zero, 2);
        if (module == IntPtr.Zero) throw new Win32Exception();
        try {
            IntPtr resource = FindResource(module, new IntPtr(1), new IntPtr(24));
            if (resource == IntPtr.Zero) throw new Win32Exception();
            uint size = SizeofResource(module, resource);
            IntPtr data = LockResource(LoadResource(module, resource));
            if (data == IntPtr.Zero) throw new Win32Exception();
            byte[] bytes = new byte[size];
            Marshal.Copy(data, bytes, 0, bytes.Length);
            return Encoding.UTF8.GetString(bytes);
        } finally {
            FreeLibrary(module);
        }
    }
}
'@

$manifest = @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
  <assemblyIdentity type="win32" name="Stupidbird.ProtoLens.Installer" version="1.0.0.0" processorArchitecture="*" />
  <description>protolens installer</description>
  <trustInfo xmlns="urn:schemas-microsoft-com:asm.v3">
    <security>
      <requestedPrivileges>
        <requestedExecutionLevel level="requireAdministrator" uiAccess="false" />
      </requestedPrivileges>
    </security>
  </trustInfo>
</assembly>
'@

[ProtoLensInstallerManifest]::Write($resolvedInstaller, [Text.UTF8Encoding]::new($false).GetBytes($manifest))
[xml]$verifiedManifest = [ProtoLensInstallerManifest]::Read($resolvedInstaller)
$executionLevel = $verifiedManifest.SelectSingleNode("//*[local-name()='requestedExecutionLevel']")
if ($executionLevel.level -ne 'requireAdministrator' -or $executionLevel.uiAccess -ne 'false') {
    throw 'Installer execution level verification failed.'
}
Write-Output "Installer manifest verified: requireAdministrator; $resolvedInstaller"
