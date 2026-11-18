using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;

// 当前用户默认 LAN 的 WinINet 适配；查询得到的字符串使用 GlobalFree，写入缓冲区自行释放。
public static class ProtoLensSystemProxy {
    private const uint PerConnectionOptions = 75;
    private const uint SettingsChanged = 39;
    private const uint Refresh = 37;
    [StructLayout(LayoutKind.Explicit)]
    public struct OptionValue {
        [FieldOffset(0)] public uint Number;
        [FieldOffset(0)] public IntPtr Text;
        [FieldOffset(0)] public long FileTime;
    }
    [StructLayout(LayoutKind.Sequential)]
    public struct Option { public uint Id; public OptionValue Value; }
    [StructLayout(LayoutKind.Sequential)]
    public struct OptionList {
        public uint Size;
        public IntPtr Connection;
        public uint Count;
        public uint Error;
        public IntPtr Options;
    }
    [DllImport("wininet.dll", EntryPoint="InternetQueryOptionW", SetLastError=true)]
    private static extern bool Query(IntPtr internet, uint option, ref OptionList list, ref uint length);
    [DllImport("wininet.dll", EntryPoint="InternetSetOptionW", SetLastError=true)]
    private static extern bool SetList(IntPtr internet, uint option, ref OptionList list, uint length);
    [DllImport("wininet.dll", EntryPoint="InternetSetOptionW", SetLastError=true)]
    private static extern bool Notify(IntPtr internet, uint option, IntPtr buffer, uint length);
    [DllImport("kernel32.dll")] private static extern IntPtr GlobalFree(IntPtr memory);

    public static Dictionary<string,object> Read() {
        int size = Marshal.SizeOf(typeof(Option));
        IntPtr memory = Marshal.AllocHGlobal(size * 4);
        for (int i=0;i<4;i++) {
            Marshal.StructureToPtr(new Option { Id=(uint)(i+1) }, IntPtr.Add(memory,i*size), false);
        }
        var list = new OptionList { Size=(uint)Marshal.SizeOf(typeof(OptionList)), Count=4, Options=memory };
        uint length=list.Size;
        bool queried=false;
        try {
            if (!Query(IntPtr.Zero,PerConnectionOptions,ref list,ref length)) { throw new Win32Exception(Marshal.GetLastWin32Error()); }
            queried=true;
            var values = new Dictionary<string,object>();
            string[] names={"flags","server","bypass","autoConfigUrl"};
            for (int i=0;i<4;i++) {
                var item=(Option)Marshal.PtrToStructure(IntPtr.Add(memory,i*size),typeof(Option));
                values[names[i]]=i==0 ? (object)(int)item.Value.Number : (Marshal.PtrToStringUni(item.Value.Text) ?? "");
            }
            return values;
        } finally {
            if (queried) {
                for (int i=1;i<4;i++) {
                    var item=(Option)Marshal.PtrToStructure(IntPtr.Add(memory,i*size),typeof(Option));
                    if (item.Value.Text!=IntPtr.Zero) { GlobalFree(item.Value.Text); }
                }
            }
            Marshal.FreeHGlobal(memory);
        }
    }
    public static void Write(int flags,string server,string bypass,string autoConfigUrl) {
        int size=Marshal.SizeOf(typeof(Option));
        IntPtr memory=Marshal.AllocHGlobal(size*4);
        IntPtr[] strings={IntPtr.Zero,IntPtr.Zero,IntPtr.Zero};
        try {
            strings[0]=Marshal.StringToHGlobalUni(server ?? "");
            strings[1]=Marshal.StringToHGlobalUni(bypass ?? "");
            strings[2]=Marshal.StringToHGlobalUni(autoConfigUrl ?? "");
            for (int i=0;i<4;i++) {
                var item=new Option { Id=(uint)(i+1) };
                if (i==0) { item.Value.Number=(uint)flags; } else { item.Value.Text=strings[i-1]; }
                Marshal.StructureToPtr(item,IntPtr.Add(memory,i*size),false);
            }
            var list=new OptionList { Size=(uint)Marshal.SizeOf(typeof(OptionList)),Count=4,Options=memory };
            if (!SetList(IntPtr.Zero,PerConnectionOptions,ref list,list.Size)) { throw new Win32Exception(Marshal.GetLastWin32Error()); }
            if (!Notify(IntPtr.Zero,SettingsChanged,IntPtr.Zero,0)) { throw new Win32Exception(Marshal.GetLastWin32Error()); }
            if (!Notify(IntPtr.Zero,Refresh,IntPtr.Zero,0)) { throw new Win32Exception(Marshal.GetLastWin32Error()); }
        } finally {
            foreach (var pointer in strings) { if (pointer!=IntPtr.Zero) { Marshal.FreeHGlobal(pointer); } }
            Marshal.FreeHGlobal(memory);
        }
    }
}
