package com.lashouwan.protolens.http;

import io.vertx.ext.web.Router;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 单实例本机唤起入口；持有控制口令才可打开浏览器，不提供命令执行或游戏操作。 */
public final class DesktopController {
    private final byte[] control;
    private final Runnable open;

    public DesktopController(String control, Runnable open) {
        this.control = control.getBytes(StandardCharsets.UTF_8);
        this.open = open;
    }

    public void register(Router router) {
        router.post("/api/desktop/open")
                .handler(
                        context -> {
                            String supplied = context.request().getHeader("X-Instance-Control");
                            if (supplied == null
                                    || !MessageDigest.isEqual(
                                            control, supplied.getBytes(StandardCharsets.UTF_8))) {
                                context.response().setStatusCode(403).end();
                                return;
                            }
                            open.run();
                            context.response().end();
                        });
    }
}
