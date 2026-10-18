package airline.management.system.ui.components;

import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

public class StatusBadge extends JLabel {
    private Color bgColor;
    private Color textColor;
    private Color borderColor;

    public StatusBadge(String text) {
        super(text == null ? "—" : text.toUpperCase(), SwingConstants.CENTER);
        setFont(AeroFonts.BADGE);
        setBorder(new EmptyBorder(3, 8, 3, 8));
        setOpaque(false);
        updateColors(getText());
    }

    public void setStatus(String status) {
        setText(status == null ? "—" : status.toUpperCase());
        updateColors(getText());
        repaint();
    }

    private void updateColors(String status) {
        if (status == null) status = "";
        switch (status) {
            case "DEPARTED", "CONFIRMED", "ACTIVE", "SUCCESS", "COMPLETED", "DELIVERED", "RESOLVED", "SETTLED", "REBOOKED", "ACCEPTED", "BOARDED", "ARRIVED", "NORMAL", "OK", "CLEAR", "PAID", "CLOSED" -> {
                bgColor = AeroColors.SUCCESS_BG;
                textColor = AeroColors.SUCCESS;
                borderColor = new Color(0xBB, 0xF7, 0xD0);
            }
            case "CANCELLED", "FAILED", "INACTIVE", "MISSING", "REJECTED", "DECLINED", "UNRESOLVED", "CRITICAL", "HIGH", "VOID" -> {
                bgColor = AeroColors.DANGER_BG;
                textColor = AeroColors.DANGER;
                borderColor = new Color(0xFE, 0xCA, 0xCA);
            }
            case "DELAYED", "ATTENTION", "PENDING", "PENDING_PAYMENT", "UNDER_INVESTIGATION", "UNDER_REVIEW", "SUBMITTED", "DECLARED", "INVESTIGATING", "OFFERED", "MEDIUM", "PROCESSING" -> {
                bgColor = AeroColors.WARNING_BG;
                textColor = AeroColors.WARNING;
                borderColor = new Color(0xFD, 0xE6, 0x8A);
            }
            case "BOARDING", "REVIEW", "REFUNDED", "REACCOMMODATING", "MITIGATED", "CHECKED_IN", "CHECKED", "LOADED", "IN_TRANSIT", "LOCATED", "OPEN", "ISSUED", "REDEEMED", "LOW" -> {
                bgColor = AeroColors.INFO_BG;
                textColor = AeroColors.INFO;
                borderColor = new Color(0xBF, 0xDB, 0xFE);
            }
            case "SCHEDULED", "INFO", "READY" -> {
                bgColor = new Color(0xF1, 0xF5, 0xF9);
                textColor = new Color(0x33, 0x41, 0x55);
                borderColor = new Color(0xCB, 0xD5, 0xE1);
            }
            default -> {
                bgColor = new Color(0xF1, 0xF5, 0xF9);
                textColor = AeroColors.TEXT_SECONDARY;
                borderColor = AeroColors.BORDER;
            }
        }
        setForeground(textColor);
    }

    public static javax.swing.table.TableCellRenderer createTableCellRenderer() {
        return new javax.swing.table.TableCellRenderer() {
            private final StatusBadge badge = new StatusBadge("");
            private final javax.swing.JPanel container = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 4));

            {
                container.setOpaque(true);
                container.add(badge);
            }

            @Override
            public java.awt.Component getTableCellRendererComponent(javax.swing.JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                badge.setStatus(value != null ? value.toString() : "—");
                java.awt.Color bg = isSelected ? table.getSelectionBackground() : (row % 2 == 0 ? java.awt.Color.WHITE : AeroColors.TABLE_ROW_ALT);
                container.setBackground(bg);
                return container;
            }
        };
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        g2.setColor(bgColor != null ? bgColor : new Color(0xF1, 0xF5, 0xF9));
        g2.fillRoundRect(0, 0, w, h, h, h);

        if (borderColor != null) {
            g2.setColor(borderColor);
            g2.drawRoundRect(0, 0, w - 1, h - 1, h, h);
        }

        super.paintComponent(g);
        g2.dispose();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.max(d.width + 10, 64), Math.max(d.height, 22));
    }
}
