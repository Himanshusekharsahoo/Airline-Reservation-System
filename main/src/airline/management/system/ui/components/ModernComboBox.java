package airline.management.system.ui.components;

import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.border.EmptyBorder;

public class ModernComboBox<E> extends JComboBox<E> {
    public ModernComboBox(E[] items) {
        super(items);
        init();
    }

    public ModernComboBox() {
        super();
        init();
    }

    private void init() {
        setFont(AeroFonts.BODY);
        setForeground(AeroColors.TEXT_MAIN);
        setBackground(Color.WHITE);
        setOpaque(false);
        setBorder(new EmptyBorder(4, 8, 4, 8));
        setPreferredSize(new Dimension(getPreferredSize().width, AeroConstants.INPUT_HEIGHT));

        setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setFont(AeroFonts.BODY);
                setBorder(new EmptyBorder(6, 12, 6, 12));
                if (isSelected) {
                    setBackground(AeroColors.PRIMARY);
                    setForeground(Color.WHITE);
                } else {
                    setBackground(Color.WHITE);
                    setForeground(AeroColors.TEXT_MAIN);
                }
                return c;
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        g2.setColor(Color.WHITE);
        g2.fillRoundRect(0, 0, w, h, AeroConstants.CORNER_RADIUS, AeroConstants.CORNER_RADIUS);
        g2.setColor(AeroColors.BORDER);
        g2.drawRoundRect(0, 0, w - 1, h - 1, AeroConstants.CORNER_RADIUS, AeroConstants.CORNER_RADIUS);

        super.paintComponent(g);
        g2.dispose();
    }
}
