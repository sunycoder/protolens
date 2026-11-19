package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.lashouwan.protolens.desktop.ReleaseAssets;
import com.lashouwan.protolens.desktop.RuntimePlatform;

import org.junit.jupiter.api.Test;

/** 发布资产混排时仍应优先安装包，并拒绝错误或不明确的 CPU 架构。 */
class ReleaseAssetsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /** 先返回的压缩包和另一架构 DMG 均不能抢占本机 DMG。 */
    @Test
    void macDownloadsPreferNativeDmgForBothArchitectures() {
        ArrayNode assets =
                assets(
                        "ProtoLens-0.1.6-macos-arm64.tar.gz",
                        "ProtoLens-0.1.6-macos-x64.tar.gz",
                        "ProtoLens-0.1.6-macos-x64.dmg",
                        "ProtoLens-0.1.6-macos-arm64.dmg");
        for (String arch : new String[] {"arm64", "x64"}) {
            assertEquals(
                    "ProtoLens-0.1.6-macos-" + arch + ".dmg",
                    ReleaseAssets.select(assets, new RuntimePlatform("macos", arch))
                            .path("name")
                            .asText());
        }
    }

    /** 无匹配时交回发布页；不能把 Intel、无架构旧包或相似文件名误推荐给 Apple Silicon。 */
    @Test
    void refusesAmbiguousWrongArchitectureAndIncompleteAssets() {
        ArrayNode assets =
                assets(
                        "ProtoLens-0.1.6-macos-x64.dmg",
                        "ProtoLens-0.1.5-macos.tar.gz",
                        "ProtoLens-0.1.6-macos-arm64-other.dmg",
                        "Other-0.1.6-macos-arm64.dmg");
        assets.addObject()
                .put("name", "ProtoLens-0.1.6-macos-arm64.dmg")
                .put("state", "new")
                .put("browser_download_url", "https://example.invalid/pending");
        assets.addObject()
                .put("name", "ProtoLens-0.1.6-macos-arm64.dmg")
                .put("size", 0)
                .put("browser_download_url", "https://example.invalid/empty");
        assertNull(ReleaseAssets.select(assets, new RuntimePlatform("macos", "arm64")));
    }

    /** 保留已有 Windows 安装器地址，以及明确架构的 macOS 便携包回退能力。 */
    @Test
    void retainsWindowsInstallerAndNativePortableFallback() {
        ArrayNode assets =
                assets(
                        "ProtoLens-0.1.6-windows-x64.zip",
                        "ProtoLens-latest.exe",
                        "ProtoLens-0.1.6-macos-arm64.tar.gz",
                        "ProtoLens-0.1.6-linux-x64.tar.gz");
        assertEquals(
                "ProtoLens-latest.exe",
                ReleaseAssets.select(assets, new RuntimePlatform("windows", "x64"))
                        .path("name")
                        .asText());
        assertEquals(
                "ProtoLens-0.1.6-macos-arm64.tar.gz",
                ReleaseAssets.select(assets, new RuntimePlatform("macos", "arm64"))
                        .path("name")
                        .asText());
        assertEquals(
                "ProtoLens-0.1.6-linux-x64.tar.gz",
                ReleaseAssets.select(assets, new RuntimePlatform("linux", "x64"))
                        .path("name")
                        .asText());
    }

    private ArrayNode assets(String... names) {
        ArrayNode assets = mapper.createArrayNode();
        for (String name : names) {
            assets.addObject()
                    .put("name", name)
                    .put("state", "uploaded")
                    .put("size", 100)
                    .put("browser_download_url", "https://example.invalid/" + name);
        }
        return assets;
    }
}
