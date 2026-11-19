package com.lashouwan.protolens.http;

import com.lashouwan.protolens.constant.NetworkConstants;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

import java.net.URI;

/** 仅允许回环 Host 和当前后端/开发页面 Origin；实际端口由应用绑定后发布。 */
public final class LocalAccessGuard implements Handler<RoutingContext> {
    private final String devOrigin;
    private volatile int port;

    public LocalAccessGuard(String devOrigin) {
        this.devOrigin = devOrigin;
    }

    public void bind(int port) {
        this.port = port;
    }

    @Override
    public void handle(RoutingContext context) {
        String host =
                context.request().authority() == null ? "" : context.request().authority().host();
        if (!(NetworkConstants.LOOPBACK_HOST.equals(host) || "localhost".equals(host))
                || !validOrigin(context.request().getHeader("Origin"))) {
            context.response().setStatusCode(403).end("来源无效");
            return;
        }
        context.response()
                .putHeader("X-Content-Type-Options", "nosniff")
                .putHeader("Cache-Control", "no-store")
                .putHeader(
                        "Content-Security-Policy",
                        "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'"
                                + " data:; object-src 'none'; frame-ancestors 'none'");
        context.next();
    }

    private boolean validOrigin(String origin) {
        if (origin == null) {
            return true;
        }
        try {
            URI uri = URI.create(origin);
            return "http".equals(uri.getScheme())
                    && (NetworkConstants.LOOPBACK_HOST.equals(uri.getHost())
                            || "localhost".equals(uri.getHost()))
                    && (uri.getPort() == port || origin.equals(devOrigin))
                    && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }
}
