package airline.management.system.ui.components;

import airline.management.system.ui.theme.AeroColors;
import java.awt.Color;
import java.awt.Dimension;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** Shared quiet scrollbar treatment for long operational views. */
public final class AeroScrollPane extends JScrollPane {
    public AeroScrollPane(java.awt.Component view) {
        super(view);
        setBorder(new EmptyBorder(0, 0, 0, 0));
        getViewport().setBackground(AeroColors.BACKGROUND);
        getVerticalScrollBar().setUnitIncrement(24);
        getVerticalScrollBar().setBlockIncrement(120);
        getHorizontalScrollBar().setUnitIncrement(24);
        getHorizontalScrollBar().setBlockIncrement(120);
        getHorizontalScrollBar().setPreferredSize(new Dimension(0, 8));
        style(getVerticalScrollBar());
        style(getHorizontalScrollBar());
    }

    private static void style(JScrollBar bar) {
        bar.setPreferredSize(new Dimension(9, 9));
        bar.setOpaque(false);
        bar.setUI(new BasicScrollBarUI() {
            private static final Color TRACK_COLOR = new Color(0xF1, 0xF5, 0xF9);
            private static final Color THUMB_NORMAL = new Color(0xCB, 0xD5, 0xE1);
            private static final Color THUMB_HOVER = new Color(0x94, 0xA3, 0xB8);
            private static final Color THUMB_DRAGGING = new Color(0x64, 0x74, 0x8B);

            @Override
            protected void configureScrollBarColors() {
                trackColor = TRACK_COLOR;
                thumbColor = THUMB_NORMAL;
            }

            @Override
            protected javax.swing.JButton createDecreaseButton(int orientation) {
                return zeroButton();
            }

            @Override
            protected javax.swing.JButton createIncreaseButton(int orientation) {
                return zeroButton();
            }

            private javax.swing.JButton zeroButton() {
                javax.swing.JButton button = new javax.swing.JButton();
                button.setPreferredSize(new Dimension(0, 0));
                button.setMinimumSize(new Dimension(0, 0));
                button.setMaximumSize(new Dimension(0, 0));
                return button;
            }

            @Override
            protected void paintTrack(java.awt.Graphics g, javax.swing.JComponent c, java.awt.Rectangle trackBounds) {
                g.setColor(TRACK_COLOR);
                g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
            }

            @Override
            protected void paintThumb(java.awt.Graphics g, javax.swing.JComponent c, java.awt.Rectangle thumbBounds) {
                if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) return;
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                Color color = isDragging ? THUMB_DRAGGING : (isThumbRollover() ? THUMB_HOVER : THUMB_NORMAL);
                g2.setColor(color);
                int arc = Math.min(thumbBounds.width, thumbBounds.height);
                g2.fillRoundRect(thumbBounds.x + 1, thumbBounds.y + 1, Math.max(0, thumbBounds.width - 2), Math.max(0, thumbBounds.height - 2), arc, arc);
                g2.dispose();
            }
        });
    }
}
