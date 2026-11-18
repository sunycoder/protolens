package com.lashouwan.protolens.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.util.Locale;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** 桌面能力只处理打开页面、目录选择与退出入口；不拥有游戏会话。 */
public final class DesktopLauncher {
    private static final Logger logger = LoggerFactory.getLogger(DesktopLauncher.class);
    private TrayIcon trayIcon;

    /** 菜单内容由Swing绘制，不依赖原生AWT菜单字体映射。 */
    private TrayPopup trayPopup;

    private JFrame frame;
    private Runnable openPage;
    private Runnable shutdown = () -> System.exit(0);

    /** 把浏览器打开动作切到AWT事件线程；无桌面直接返回，默认浏览器失败时保留可退出的控制窗口。 */
    public void open(String url) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        EventQueue.invokeLater(
                () -> {
                    try {
                        if (Desktop.isDesktopSupported()
                                && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                            Desktop.getDesktop().browse(URI.create(url));
                            return;
                        }
                    } catch (Exception error) {
                        logger.warn("默认浏览器打开失败 exceptionType:{}", error.getClass().getSimpleName());
                        // 默认浏览器不可用时保留可退出的控制窗口，不让后台程序失去入口。
                    }
                    fallback(url, shutdown);
                });
    }

    /** 在AWT事件线程装配托盘和中文Swing菜单；托盘不可用时使用控制窗口，不拥有或重启角色会话。 */
    public void install(String baseUrl, Runnable openPage, Runnable shutdown) {
        this.openPage = openPage;
        this.shutdown = shutdown;
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        EventQueue.invokeLater(
                () -> {
                    try {
                        if (SystemTray.isSupported()) {
                            Font font = selectMenuFont();
                            trayPopup = new TrayPopup(font, openPage, shutdown);
                            trayIcon =
                                    new TrayIcon(
                                            DesktopIcons.trayImage(),
                                            "ProtoLens 协议分析工作台 " + AnalyzerVersion.current());
                            trayIcon.setImageAutoSize(true);
                            trayIcon.addActionListener(event -> openPage.run());
                            trayIcon.addMouseListener(
                                    new MouseAdapter() {
                                        private void popup(MouseEvent event) {
                                            if (shouldShowTrayMenu(event)) {
                                                trayPopup.show(
                                                        MouseInfo.getPointerInfo().getLocation());
                                            }
                                        }

                                        @Override
                                        public void mousePressed(MouseEvent event) {
                                            popup(event);
                                        }

                                        @Override
                                        public void mouseReleased(MouseEvent event) {
                                            popup(event);
                                        }
                                    });
                            SystemTray.getSystemTray().add(trayIcon);
                            logger.info(
                                    "托盘菜单已初始化 renderer:Swing family:{}",
                                    font.getFamily(Locale.ROOT));
                            return;
                        }
                    } catch (Exception error) {
                        logger.warn("系统托盘不可用 exceptionType:{}", error.getClass().getSimpleName());
                        // 托盘被系统策略禁用时提供控制窗口，服务仍可使用。
                    }
                    fallback(baseUrl, shutdown);
                });
    }

    /** 同时支持平台弹出标记和右键释放，避免托盘事件未标记popupTrigger时失去菜单入口。 */
    static boolean shouldShowTrayMenu(MouseEvent event) {
        return event.isPopupTrigger()
                || event.getID() == MouseEvent.MOUSE_RELEASED
                        && event.getButton() == MouseEvent.BUTTON3;
    }

    /** 优先Windows中文界面字体；只接受实际存在且包含菜单中文字形的字体，不把逻辑替代字体当作命中。 */
    static Font selectMenuFont() {
        for (String family :
                new String[] {
                    "Microsoft YaHei UI",
                    "Microsoft YaHei",
                    "SimSun",
                    "PingFang SC",
                    "Noto Sans CJK SC",
                    Font.DIALOG
                }) {
            Font font = new Font(family, Font.PLAIN, 12);
            if (font.getFamily(Locale.ROOT).equalsIgnoreCase(family)
                    && font.canDisplayUpTo("打开分析工作台退出程序") == -1) {
                return font;
            }
        }
        return new Font(Font.DIALOG, Font.PLAIN, 12);
    }

    /** 在桌面线程复用或创建后备控制窗口，提供打开页面与退出动作，避免无托盘时失去后台入口。 */
    private void fallback(String url, Runnable shutdown) {
        if (frame != null) {
            frame.toFront();
            return;
        }
        frame = new JFrame("ProtoLens 协议分析工作台");
        frame.setIconImages(DesktopIcons.images());
        JPanel panel = new JPanel();
        panel.add(new JLabel("分析服务运行中：" + URI.create(url).resolve("/")));
        JButton open = new JButton("打开分析工作台");
        open.addActionListener(
                event -> {
                    if (openPage == null) {
                        open(url);
                    } else {
                        openPage.run();
                    }
                });
        JButton exit = new JButton("退出程序");
        exit.addActionListener(event -> shutdown.run());
        panel.add(open);
        panel.add(exit);
        frame.setContentPane(panel);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /** 在Swing线程显示仅目录选择器，取消或无桌面返回null；HTTP调用方负责回到Vertx后响应。 */
    public void selectDirectory(Consumer<String> completed) {
        if (GraphicsEnvironment.isHeadless()) {
            completed.accept(null);
            return;
        }
        SwingUtilities.invokeLater(
                () -> {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setDialogTitle("选择本机项目目录");
                    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    int result = chooser.showOpenDialog(null);
                    completed.accept(
                            result == JFileChooser.APPROVE_OPTION
                                    ? chooser.getSelectedFile().getAbsolutePath()
                                    : null);
                });
    }

    /** 异步安排桌面线程释放托盘、菜单和控制窗口；不主动关闭共享Vertx或角色连接。 */
    public void close() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        EventQueue.invokeLater(
                () -> {
                    if (trayIcon != null) {
                        SystemTray.getSystemTray().remove(trayIcon);
                    }
                    if (trayPopup != null) {
                        trayPopup.close();
                    }
                    if (frame != null) {
                        frame.dispose();
                    }
                });
    }

    /** EXE没有控制台时也必须能看到启动失败；弹窗完成后再释放进程，避免闪退。 */
    public static void showStartupFailure(String message, Runnable afterClose) {
        if (GraphicsEnvironment.isHeadless()) {
            logger.error("桌面启动失败 reason:{}", message);
            afterClose.run();
            return;
        }
        EventQueue.invokeLater(
                () -> {
                    JOptionPane.showMessageDialog(
                            null, message, "ProtoLens 协议分析工作台启动失败", JOptionPane.ERROR_MESSAGE);
                    afterClose.run();
                });
    }
}
