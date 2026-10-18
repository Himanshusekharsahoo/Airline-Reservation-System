package airline.management.system.ui.components;

import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;

public class ModernTextField extends JTextField {
    private boolean focused = false;
    private String placeholder = "";

    public ModernTextField() {
        this("", 15);
    }

    public ModernTextField(int columns) {
        this("", columns);
    }

    public ModernTextField(String text) {
        this(text, 15);
    }

    public ModernTextField(String text, int columns) {
        super(text, columns);
        setFont(AeroFonts.BODY);
        setForeground(AeroColors.TEXT_MAIN);
        setCaretColor(AeroColors.PRIMARY);
        setOpaque(false);
        setBorder(new EmptyBorder(6, 12, 6, 12));
        setPreferredSize(new Dimension(getPreferredSize().width, AeroConstants.INPUT_HEIGHT));

        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { focused = true; repaint(); }
            @Override public void focusLost(FocusEvent e) { focused = false; repaint(); }
        });
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder == null ? "" : placeholder;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        g2.setColor(isEnabled() ? Color.WHITE : new Color(0xF3, 0xF4, 0xF6));
        g2.fillRoundRect(0, 0, w, h, AeroConstants.CORNER_RADIUS, AeroConstants.CORNER_RADIUS);

        g2.setColor(focused ? AeroColors.BORDER_FOCUS : AeroColors.BORDER);
        g2.drawRoundRect(0, 0, w - 1, h - 1, AeroConstants.CORNER_RADIUS, AeroConstants.CORNER_RADIUS);

        super.paintComponent(g);

        if (!placeholder.isEmpty() && getText().isEmpty() && !focused) {
            g2.setColor(AeroColors.TEXT_MUTED);
            g2.setFont(AeroFonts.BODY);
            Insets insets = getInsets();
            var fm = g2.getFontMetrics();
            int y = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(placeholder, insets.left, y);
        }

        g2.dispose();
    }

}
