package com.lashouwan.protolens.desktop;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;

/** 更新下载的产物选择规则；只匹配本机架构，优先安装包，不依赖 GitHub 返回的资产顺序。 */
public final class ReleaseAssets {
    private ReleaseAssets() {}

    /**
     * 从 Release 的 assets 数组中选择已上传的本机产物。
     *
     * <p>macOS 优先 DMG，其次 PKG 和原生便携包；Windows 保留 latest.exe 兼容入口。 无架构标识的旧 macOS
     * 包不能证明与本机兼容，不自动推荐；无匹配时返回 null，由页面展示发布页入口。
     */
    public static JsonNode select(JsonNode assets, RuntimePlatform platform) {
        List<String> extensions =
                switch (platform.os()) {
                    case "windows" -> List.of(".exe", ".zip");
                    case "macos" -> List.of(".dmg", ".pkg", ".tar.gz");
                    case "linux" -> List.of(".deb", ".rpm", ".tar.gz");
                    default -> throw new IllegalArgumentException("不支持的更新平台：" + platform.os());
                };
        // 先遍历格式优先级，再检查同架构文件，确保先列出的便携包不会抢占 DMG。
        for (String extension : extensions) {
            for (JsonNode asset : assets) {
                String name = asset.path("name").asText("").toLowerCase(Locale.ROOT);
                if (!name.startsWith("protolens-")
                        || !asset.path("state").asText("uploaded").equals("uploaded")
                        || (asset.has("size") && asset.path("size").asLong() <= 0)
                        || asset.path("browser_download_url").asText("").isBlank()) {
                    continue;
                }
                boolean exactPlatform = name.endsWith("-" + platform.id() + extension);
                boolean windowsLatest =
                        platform.os().equals("windows")
                                && extension.equals(".exe")
                                && name.equals("protolens-latest.exe");
                if (exactPlatform || windowsLatest) {
                    return asset;
                }
            }
        }
        return null;
    }
}
