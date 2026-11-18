import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** 构建时生成网页双向箭头的原生图标；运行时仅从资源读取 PNG，不需要 SVG 渲染依赖。 */
public final class GenerateDesktopIcons {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createDirectories(Path.of(args[0]));
        int[] sizes = {16, 24, 32, 48, 64, 128, 256, 512, 1024};
        List<byte[]> images = new ArrayList<>();
        for (int size : sizes) {
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.scale(size / 48.0, size / 48.0);
            graphics.setColor(new Color(0x2563eb));
            graphics.fillRoundRect(0, 0, 48, 48, 20, 20);
            graphics.setColor(Color.WHITE);
            graphics.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D arrows = new Path2D.Double();
            arrows.moveTo(10, 17); arrows.lineTo(37, 17);
            arrows.moveTo(30, 10); arrows.lineTo(37, 17); arrows.lineTo(30, 24);
            arrows.moveTo(38, 31); arrows.lineTo(11, 31);
            arrows.moveTo(18, 24); arrows.lineTo(11, 31); arrows.lineTo(18, 38);
            graphics.draw(arrows);
            graphics.dispose();
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            ImageIO.write(image, "png", encoded);
            byte[] png = encoded.toByteArray();
            images.add(png);
            Files.write(directory.resolve("protolens-" + size + ".png"), png);
        }
        // ICO 目录项使用小端序；256 像素用 0 表示，数据体保存完整的 PNG。
        int count = 7;
        int offset = 6 + count * 16;
        int total = offset;
        for (int index = 0; index < count; index++) { total += images.get(index).length; }
        ByteBuffer ico = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0).putShort((short) 1).putShort((short) count);
        for (int index = 0; index < count; index++) {
            int size = sizes[index];
            ico.put((byte) size).put((byte) size).put((byte) 0).put((byte) 0);
            ico.putShort((short) 1).putShort((short) 32);
            ico.putInt(images.get(index).length).putInt(offset);
            offset += images.get(index).length;
        }
        for (int index = 0; index < count; index++) { ico.put(images.get(index)); }
        Files.write(directory.resolve("protolens.ico"), ico.array());
        // ICNS 与 ICO 的字节序不同，macOS 的 PNG 槽位保留 16 至 1024 像素。
        int[] indices = {0, 2, 5, 6, 7, 8};
        String[] types = {"icp4", "icp5", "ic07", "ic08", "ic09", "ic10"};
        int icnsLength = 8;
        for (int index : indices) { icnsLength += 8 + images.get(index).length; }
        ByteBuffer icns = ByteBuffer.allocate(icnsLength);
        icns.putInt(0x69636e73).putInt(icnsLength);
        for (int index = 0; index < indices.length; index++) {
            byte[] png = images.get(indices[index]);
            for (char letter : types[index].toCharArray()) { icns.put((byte) letter); }
            icns.putInt(png.length + 8).put(png);
        }
        Files.write(directory.resolve("protolens.icns"), icns.array());
    }
}
