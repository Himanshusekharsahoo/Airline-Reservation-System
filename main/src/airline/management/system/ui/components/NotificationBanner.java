package airline.management.system.ui.components;

import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

public class NotificationBanner extends JPanel {
    public enum Type { SUCCESS, ERROR, WARNING, INFO }

    private final JLabel messageLabel;
    private final AeroIcon icon;
    private Color bgColor = AeroColors.SUCCESS_BG;
    private Color fgColor = AeroColors.SUCCESS;
    private Color borderColor = new Color(0xBB, 0xF7, 0xD0);
    private Timer autoDismissTimer;

    public NotificationBanner() {
        setLayout(new BorderLayout());
        setOpaque(false);
        setBorder(new EmptyBorder(8, 16, 8, 16));
        setVisible(false);

        icon = new AeroIcon(AeroIcon.Type.ALERT, 16).color(fgColor);
        add(icon, BorderLayout.WEST);
        messageLabel = new JLabel("");
        messageLabel.setFont(AeroFonts.BODY_BOLD);
        add(messageLabel, BorderLayout.CENTER);
    }

    public void showMessage(String message, Type type) {
        if (autoDismissTimer != null && autoDismissTimer.isRunning()) {
            autoDismissTimer.stop();
        }

        switch (type) {
            case SUCCESS -> {
                bgColor = AeroColors.SUCCESS_BG;
                fgColor = AeroColors.SUCCESS;
                borderColor = new Color(0xBB, 0xF7, 0xD0);
                icon.color(fgColor);
                messageLabel.setText(message);
            }
            case ERROR -> {
                bgColor = AeroColors.DANGER_BG;
                fgColor = AeroColors.DANGER;
                borderColor = new Color(0xFE, 0xCA, 0xCA);
                icon.color(fgColor);
                messageLabel.setText(message);
            }
            case WARNING -> {
                bgColor = AeroColors.WARNING_BG;
                fgColor = new Color(0xB4, 0x53, 0x09);
                borderColor = new Color(0xFD, 0xE6, 0x8A);
                icon.color(fgColor);
                messageLabel.setText(message);
            }
            case INFO -> {
                bgColor = AeroColors.INFO_BG;
                fgColor = AeroColors.INFO;
                borderColor = new Color(0xBF, 0xDB, 0xFE);
                icon.color(fgColor);
                messageLabel.setText(message);
            }
        }

        messageLabel.setForeground(fgColor);
        setVisible(true);
        revalidate();
        repaint();

        autoDismissTimer = new Timer(4500, e -> {
            setVisible(false);
            revalidate();
            repaint();
        });
        autoDismissTimer.setRepeats(false);
        autoDismissTimer.start();
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (!isVisible()) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        g2.setColor(bgColor);
        g2.fillRoundRect(0, 0, w, h, AeroConstants.CORNER_RADIUS, AeroConstants.CORNER_RADIUS);

        g2.setColor(borderColor);
        g2.drawRoundRect(0, 0, w - 1, h - 1, AeroConstants.CORNER_RADIUS, AeroConstants.CORNER_RADIUS);

        g2.dispose();
        super.paintComponent(g);
    }
}
