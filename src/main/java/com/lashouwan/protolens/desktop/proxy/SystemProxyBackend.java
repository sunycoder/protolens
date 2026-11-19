package com.lashouwan.protolens.desktop.proxy;

import java.io.IOException;

/** 系统代理的最小读写边界；生产按平台使用 WinINet 或 networksetup，测试不修改真实网络。 */
public interface SystemProxyBackend extends AutoCloseable {
    /** 恢复代理后释放平台会话；无持续平台资源的适配器保持空实现。 */
    @Override
    default void close() throws Exception {}

    boolean supported();

    ProxySettings read() throws Exception;

    void write(ProxySettings settings) throws Exception;

    /** macOS 授权由用户点击触发；Windows 保持原有无授权自动接管流程。 */
    default boolean requiresAuthorization() {
        return false;
    }

    /** 恢复时读取原网络服务，即使默认路由已切到另一网卡，也不改动新网络。 */
    default ProxySettings readFor(ProxySettings snapshot) throws Exception {
        return read();
    }

    default ProxySettings local(int port, ProxySettings original) {
        return ProxySettings.local(port, original);
    }

    default ProxySettings recoveryOriginal(int port, ProxySettings current) {
        return current.pointsTo(port)
                ? new ProxySettings(
                        (current.flags() & ~ProxySettings.MANUAL) | ProxySettings.DIRECT,
                        current.server(),
                        current.bypass(),
                        current.autoConfigUrl())
                : current;
    }

    default String notice(ProxySettings snapshot) throws Exception {
        return "";
    }

    /** 在写入前核验当前快照；原生适配将核验与写入放入同一调用，缩短外部软件介入的间隔。 */
    default void compareAndWrite(ProxySettings expected, ProxySettings settings) throws Exception {
        if (!read().equals(expected)) {
            throw new IOException("系统代理已变化，未覆盖当前配置");
        }
        write(settings);
    }
}
