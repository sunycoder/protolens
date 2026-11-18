package com.lashouwan.protolens.desktop;

import java.awt.Image;
import java.awt.image.BaseMultiResolutionImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/** 网页Logo的桌面尺寸资源；不在运行时解析SVG，也不依赖外部文件或Node。 */
final class DesktopIcons {
    private DesktopIcons() {}

    /** 从应用资源读取各尺寸PNG并返回不可修改列表；缺失或无效资源拒绝，不在运行时解析SVG或依赖Node。 */
    static List<Image> images() {
        List<Image> images = new ArrayList<>();
        for (int size : new int[] {16, 24, 32, 48, 64, 128, 256}) {
            String resource = "/desktop/protolens-" + size + ".png";
            try (var input = DesktopIcons.class.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalStateException("缺少桌面图标: " + resource);
                }
                Image image = ImageIO.read(input);
                if (image == null) {
                    throw new IllegalStateException("桌面图标不是有效PNG: " + resource);
                }
                images.add(image);
            } catch (IOException error) {
                throw new IllegalStateException("桌面图标读取失败: " + resource, error);
            }
        }
        return List.copyOf(images);
    }

    static Image trayImage() {
        return new BaseMultiResolutionImage(images().toArray(Image[]::new));
    }
}
