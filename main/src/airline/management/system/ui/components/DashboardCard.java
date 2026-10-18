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
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

public class DashboardCard extends JPanel {
    private final JLabel titleLabel;
    private final JLabel valueLabel;
    private final JLabel subtitleLabel;
    private final JComponent iconLabel;

    public DashboardCard(String title, String value, String subtitle) {
        this(title, value, subtitle, null, null);
    }

    public DashboardCard(String title, String value, String subtitle, String iconSymbol, Color iconBg) {
        setLayout(new BorderLayout());
        setOpaque(false);
        setBorder(new EmptyBorder(12, 16, 12, 16));
        setPreferredSize(new Dimension(240, 130));
        setMinimumSize(new Dimension(180, 120));

        // Top bar with title and optional icon
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setOpaque(false);

        titleLabel = new JLabel(title);
        titleLabel.setFont(AeroFonts.SECTION_CATEGORY);
        titleLabel.setForeground(AeroColors.TEXT_MUTED);
        topBar.add(titleLabel, BorderLayout.WEST);

        if (iconSymbol != null) {
            iconLabel = new CardIconBadge(AeroIcon.fromLegacySymbol(iconSymbol), iconBg != null ? iconBg : AeroColors.INFO_BG);
            topBar.add(iconLabel, BorderLayout.EAST);
        } else {
            iconLabel = null;
        }

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);

        valueLabel = new JLabel(value);
        valueLabel.setFont(AeroFonts.KPI_VALUE);
        valueLabel.setForeground(AeroColors.TEXT_MAIN);

        subtitleLabel = new JLabel(subtitle);
        subtitleLabel.setFont(AeroFonts.MUTED);
        subtitleLabel.setForeground(AeroColors.TEXT_MUTED);

        content.add(topBar);
        content.add(Box.createVerticalStrut(6));
        content.add(valueLabel);
        content.add(Box.createVerticalStrut(3));
        content.add(subtitleLabel);

        add(content, BorderLayout.CENTER);
    }

    public void setValue(String value) {
        valueLabel.setText(value);
    }

    public void setValueColor(Color color) {
        valueLabel.setForeground(color != null ? color : AeroColors.TEXT_MAIN);
    }

    public void setSubtitle(String subtitle) {
        subtitleLabel.setText(subtitle);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        g2.setColor(new Color(15, 23, 42, 6));
        g2.fillRoundRect(1, 2, Math.max(0, w - 2), Math.max(0, h - 2), AeroConstants.CORNER_RADIUS, AeroConstants.CORNER_RADIUS);

        g2.setColor(Color.WHITE);
        g2.fillRoundRect(0, 0, Math.max(0, w - 2), Math.max(0, h - 2), AeroConstants.CORNER_RADIUS, AeroConstants.CORNER_RADIUS);

        g2.setColor(AeroColors.BORDER);
        g2.drawRoundRect(0, 0, Math.max(0, w - 2), Math.max(0, h - 2), AeroConstants.CORNER_RADIUS, AeroConstants.CORNER_RADIUS);

        g2.dispose();
        super.paintComponent(g);
    }

    private static class CardIconBadge extends JPanel {
        private final Color bgColor;
        private final AeroIcon icon;

        public CardIconBadge(AeroIcon.Type type, Color bgColor) {
            this.bgColor = bgColor;
            setLayout(new BorderLayout());
            icon = new AeroIcon(type, 16).color(AeroColors.PRIMARY);
            add(icon, BorderLayout.CENTER);
            setPreferredSize(new Dimension(30, 30));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int size = Math.min(getWidth(), getHeight());
            g2.setColor(bgColor);
            g2.fillRoundRect(0, 0, size, size, 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
