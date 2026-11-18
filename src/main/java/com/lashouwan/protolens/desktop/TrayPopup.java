package com.lashouwan.protolens.desktop;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;

/** Swing绘制的托盘菜单，避开Windows原生AWT菜单的中文字形绘制；生命周期仅由AWT事件线程管理。 */
final class TrayPopup {
    private final JDialog dialog = new JDialog((Window) null);

    TrayPopup(Font font, Runnable openPage, Runnable shutdown) {
        dialog.setUndecorated(true);
        dialog.setType(Window.Type.POPUP);
        dialog.setAlwaysOnTop(true);
        dialog.setContentPane(
                createContent(
                        font,
                        () -> {
                            dialog.setVisible(false);
                            openPage.run();
                        },
                        () -> {
                            dialog.setVisible(false);
                            shutdown.run();
                        }));
        dialog.addWindowFocusListener(
                new WindowAdapter() {
                    @Override
                    public void windowLostFocus(WindowEvent event) {
                        dialog.setVisible(false);
                    }
                });
        dialog.getRootPane()
                .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "hide-menu");
        dialog.getRootPane()
                .getActionMap()
                .put(
                        "hide-menu",
                        new AbstractAction() {
                            @Override
                            public void actionPerformed(ActionEvent event) {
                                dialog.setVisible(false);
                            }
                        });
        dialog.pack();
    }

    /** 按鼠标所在屏幕的可用区域定位，支持负坐标副屏，避免贴近任务栏时菜单超出屏幕。 */
    void show(Point anchor) {
        GraphicsConfiguration screen = dialog.getGraphicsConfiguration();
        for (var device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (device.getDefaultConfiguration().getBounds().contains(anchor)) {
                screen = device.getDefaultConfiguration();
                break;
            }
        }
        Rectangle area = new Rectangle(screen.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(screen);
        area.x += insets.left;
        area.y += insets.top;
        area.width -= insets.left + insets.right;
        area.height -= insets.top + insets.bottom;
        int x = Math.max(area.x, Math.min(anchor.x, area.x + area.width - dialog.getWidth()));
        int y =
                Math.max(
                        area.y,
                        Math.min(
                                anchor.y - dialog.getHeight(),
                                area.y + area.height - dialog.getHeight()));
        dialog.setLocation(x, y);
        dialog.setVisible(true);
        dialog.toFront();
        dialog.requestFocus();
    }

    void close() {
        dialog.dispose();
    }

    /** 实际窗口和离屏渲染测试共用同一组件，不再通过原生MenuItem绘制任何文字。 */
    static JPanel createContent(Font font, Runnable openPage, Runnable shutdown) {
        boolean chinese = font.canDisplayUpTo("打开分析工作台退出程序") == -1;
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Color.WHITE);
        panel.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(210, 216, 225)),
                        BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        panel.add(button(chinese ? "打开分析工作台" : "Open analyzer", font, openPage));
        panel.add(new JSeparator());
        panel.add(button(chinese ? "退出程序" : "Exit", font, shutdown));
        return panel;
    }

    private static JButton button(String text, Font font, Runnable action) {
        JButton button = new JButton(text);
        button.setFont(font);
        button.setHorizontalAlignment(SwingConstants.LEADING);
        button.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 26));
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(true);
        button.setBackground(Color.WHITE);
        button.setForeground(new Color(31, 41, 55));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
        button.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent event) {
                        button.setBackground(new Color(235, 242, 255));
                    }

                    @Override
                    public void mouseExited(MouseEvent event) {
                        button.setBackground(Color.WHITE);
                    }
                });
        button.addActionListener(event -> action.run());
        return button;
    }
}
