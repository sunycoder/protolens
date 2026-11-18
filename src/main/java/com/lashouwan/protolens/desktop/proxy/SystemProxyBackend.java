package com.lashouwan.protolens.desktop.proxy;

import java.io.IOException;

/** 当前用户系统代理的最小读写边界；生产通过 WinINet，测试注入内存实现，不修改真实网络。 */
public interface SystemProxyBackend {
    boolean supported();

    ProxySettings read() throws Exception;

    void write(ProxySettings settings) throws Exception;

    /** 在写入前核验当前快照；原生适配将核验与写入放入同一调用，缩短外部软件介入的间隔。 */
    default void compareAndWrite(ProxySettings expected, ProxySettings settings) throws Exception {
        if (!read().equals(expected)) {
            throw new IOException("系统代理已变化，未覆盖当前配置");
        }
        write(settings);
    }
}
