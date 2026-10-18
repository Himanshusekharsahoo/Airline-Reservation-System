package airline.management.system.ui.components;

import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JButton;

public class ModernButton extends JButton {
    public enum Style { PRIMARY, SECONDARY, DANGER, SUCCESS, GHOST }

    private final Style style;
    private boolean hovered = false;
    private boolean pressed = false;

    public ModernButton(String text) {
        this(text, Style.PRIMARY);
    }

    public ModernButton(String text, Style style) {
        super(text);
        this.style = style;
        setFont(AeroFonts.BUTTON);
        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(getPreferredSize().width + 24, AeroConstants.BUTTON_HEIGHT));

        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
            @Override public void mouseExited(MouseEvent e) { hovered = false; repaint(); }
            @Override public void mousePressed(MouseEvent e) { pressed = true; repaint(); }
            @Override public void mouseReleased(MouseEvent e) { pressed = false; repaint(); }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        Color bg;
        Color fg;
        Color border = null;

        if (!isEnabled()) {
            bg = new Color(0xF1, 0xF5, 0xF9);
            fg = AeroColors.TEXT_MUTED;
            border = AeroColors.BORDER;
        } else {
            switch (style) {
                case SECONDARY -> {
                    bg = pressed ? new Color(0xF1, 0xF5, 0xF9) : (hovered ? new Color(0xF8, 0xFA, 0xFC) : Color.WHITE);
                    fg = AeroColors.TEXT_MAIN;
                    border = AeroColors.BORDER;
                }
                case GHOST -> {
                    bg = pressed ? new Color(0xE2, 0xE8, 0xF0) : (hovered ? new Color(0xF1, 0xF5, 0xF9) : new Color(0, 0, 0, 0));
                    fg = hovered ? AeroColors.TEXT_MAIN : AeroColors.TEXT_SECONDARY;
                    border = null;
                }
                case DANGER -> {
                    bg = pressed ? new Color(0x99, 0x1B, 0x1B) : (hovered ? new Color(0xDC, 0x26, 0x26) : AeroColors.DANGER);
                    fg = Color.WHITE;
                }
                case SUCCESS -> {
                    bg = pressed ? new Color(0x16, 0x65, 0x34) : (hovered ? new Color(0x16, 0xA3, 0x4A) : AeroColors.SUCCESS);
                    fg = Color.WHITE;
                }
                case PRIMARY -> {
                    bg = pressed ? AeroColors.PRIMARY_PRESSED : (hovered ? AeroColors.PRIMARY_HOVER : AeroColors.PRIMARY);
                    fg = Color.WHITE;
                }
                default -> {
                    bg = AeroColors.PRIMARY;
                    fg = Color.WHITE;
                }
            }
        }

        g2.setColor(bg);
        g2.fillRoundRect(0, 0, w, h, AeroConstants.BUTTON_RADIUS, AeroConstants.BUTTON_RADIUS);

        if (border != null) {
            g2.setColor(border);
            g2.drawRoundRect(0, 0, w - 1, h - 1, AeroConstants.BUTTON_RADIUS, AeroConstants.BUTTON_RADIUS);
        }

        g2.setColor(fg);
        g2.setFont(getFont());
        var fm = g2.getFontMetrics();
        int textX = (w - fm.stringWidth(getText())) / 2;
        int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(getText(), textX, textY);

        g2.dispose();
    }
}
