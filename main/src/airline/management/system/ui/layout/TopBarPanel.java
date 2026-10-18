package airline.management.system.ui.layout;

import airline.management.system.UserSession;
import airline.management.system.security.Role;
import airline.management.system.ui.components.AeroIcon;
import airline.management.system.ui.navigation.NavRoute;
import airline.management.system.ui.navigation.NavigationController;
import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/** Persistent page context and staff identity for the AeroDesk workspace. */
public class TopBarPanel extends JPanel {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private final JLabel titleLabel;
    private final JLabel subtitleLabel;
    private final JLabel userLabel;
    private final JLabel roleLabel;
    private final AvatarBadge avatar;

    public TopBarPanel(NavigationController controller) {
        setLayout(new BorderLayout(20, 0));
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(800, AeroConstants.TOPBAR_HEIGHT));
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, AeroColors.BORDER), new EmptyBorder(0, 28, 0, 24)));
        JPanel page = new JPanel(); page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS)); page.setOpaque(false); page.setAlignmentY(CENTER_ALIGNMENT);
        titleLabel = new JLabel(controller.getCurrentRoute().getTitle()); titleLabel.setFont(AeroFonts.TITLE_SECTION); titleLabel.setForeground(AeroColors.TEXT_MAIN);
        subtitleLabel = new JLabel(controller.getCurrentRoute().getSubtitle()); subtitleLabel.setFont(AeroFonts.CAPTION); subtitleLabel.setForeground(AeroColors.TEXT_SECONDARY);
        page.add(titleLabel); page.add(Box.createVerticalStrut(2)); page.add(subtitleLabel); add(page, BorderLayout.WEST);

        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.setOpaque(false);
        actions.setAlignmentY(CENTER_ALIGNMENT);

        JComponent hubSwitcher = createHubSwitcher();
        hubSwitcher.setAlignmentY(CENTER_ALIGNMENT);
        actions.add(hubSwitcher);
        actions.add(Box.createHorizontalStrut(12));

        JPanel liveIndicator = createLiveIndicator();
        liveIndicator.setAlignmentY(CENTER_ALIGNMENT);
        actions.add(liveIndicator);
        actions.add(Box.createHorizontalStrut(12));

        JPanel divider1 = createDivider();
        divider1.setAlignmentY(CENTER_ALIGNMENT);
        actions.add(divider1);
        actions.add(Box.createHorizontalStrut(12));

        JPanel dateBadge = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        dateBadge.setOpaque(false);
        dateBadge.setAlignmentY(CENTER_ALIGNMENT);
        dateBadge.add(new AeroIcon(AeroIcon.Type.CALENDAR, 14).color(AeroColors.TEXT_MUTED));
        JLabel date = new JLabel(LocalDate.now().format(DATE_FORMAT).toUpperCase());
        date.setFont(AeroFonts.CAPTION_BOLD);
        date.setForeground(AeroColors.TEXT_SECONDARY);
        dateBadge.add(date);
        actions.add(dateBadge);
        actions.add(Box.createHorizontalStrut(12));

        JPanel divider2 = createDivider();
        divider2.setAlignmentY(CENTER_ALIGNMENT);
        actions.add(divider2);
        actions.add(Box.createHorizontalStrut(8));

        JButton bell = createIconButton(AeroIcon.Type.BELL, "Notifications", this::showNotificationHint);
        bell.setAlignmentY(CENTER_ALIGNMENT);
        actions.add(bell);
        JButton help = createIconButton(AeroIcon.Type.HELP, "Help and shortcuts", this::showHelpDialog);
        help.setAlignmentY(CENTER_ALIGNMENT);
        actions.add(help);
        actions.add(Box.createHorizontalStrut(8));

        JPanel divider3 = createDivider();
        divider3.setAlignmentY(CENTER_ALIGNMENT);
        actions.add(divider3);
        actions.add(Box.createHorizontalStrut(12));

        JPanel staff = new JPanel();
        staff.setLayout(new BoxLayout(staff, BoxLayout.Y_AXIS));
        staff.setOpaque(false);
        staff.setAlignmentY(CENTER_ALIGNMENT);
        String username = UserSession.username() == null ? "Operator" : UserSession.username();
        userLabel = new JLabel(username);
        userLabel.setFont(AeroFonts.BODY_BOLD);
        userLabel.setForeground(AeroColors.TEXT_MAIN);
        roleLabel = new JLabel(roleName());
        roleLabel.setFont(AeroFonts.MUTED);
        roleLabel.setForeground(AeroColors.TEXT_MUTED);
        staff.add(userLabel);
        staff.add(roleLabel);
        actions.add(staff);
        actions.add(Box.createHorizontalStrut(10));

        avatar = new AvatarBadge(username);
        avatar.setAlignmentY(CENTER_ALIGNMENT);
        actions.add(avatar);

        add(actions, BorderLayout.EAST);
        controller.addListener((route, ignored) -> updateRoute(route));
    }

    private static JPanel createDivider() {
        JPanel divider = new JPanel();
        divider.setBackground(AeroColors.BORDER);
        divider.setPreferredSize(new Dimension(1, 22));
        divider.setMaximumSize(new Dimension(1, 22));
        return divider;
    }

    private static JComponent createHubSwitcher() {
        final Color baseBg = new Color(0xF8, 0xFA, 0xFC);
        final Color hoverBg = new Color(0xEF, 0xF6, 0xFF);
        final Color baseBorder = AeroColors.BORDER;
        final Color hoverBorder = new Color(0xBF, 0xDB, 0xFE);
        final boolean[] hovered = new boolean[] { false };

        JPanel pill = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hovered[0] ? hoverBg : baseBg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.setColor(hovered[0] ? hoverBorder : baseBorder);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        pill.setOpaque(false);
        pill.setBorder(new EmptyBorder(2, 9, 2, 9));
        pill.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        AeroIcon icon = new AeroIcon(AeroIcon.Type.STATION, 14).color(AeroColors.PRIMARY);
        JLabel hubLabel = new JLabel("PRIMARY HUB");
        hubLabel.setFont(AeroFonts.SECTION_CATEGORY);
        hubLabel.setForeground(AeroColors.TEXT_MUTED);

        JLabel bullet = new JLabel("•");
        bullet.setForeground(AeroColors.BORDER);

        JLabel codeLabel = new JLabel(StationContext.getCurrentStation().getCode());
        codeLabel.setFont(AeroFonts.MONO_BOLD);
        codeLabel.setForeground(AeroColors.TEXT_MAIN);

        AeroIcon chevron = new AeroIcon(AeroIcon.Type.CHEVRON_DOWN, 9).color(AeroColors.TEXT_MUTED);

        pill.add(icon);
        pill.add(hubLabel);
        pill.add(bullet);
        pill.add(codeLabel);
        pill.add(chevron);

        Runnable updateTooltip = () -> {
            OperationalStation st = StationContext.getCurrentStation();
            pill.setToolTipText("Active Dispatch Station: " + st.getCode() + " (" + st.getAirportName() + ", " + st.getCity() + ") — Click to switch");
        };
        updateTooltip.run();

        StationContext.addListener(st -> {
            codeLabel.setText(st.getCode());
            updateTooltip.run();
            pill.repaint();
        });

        pill.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hovered[0] = true;
                icon.color(AeroColors.ACCENT);
                chevron.color(AeroColors.ACCENT);
                pill.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered[0] = false;
                icon.color(AeroColors.PRIMARY);
                chevron.color(AeroColors.TEXT_MUTED);
                pill.repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                showHubMenu(pill);
            }
        });

        return pill;
    }

    private static void showHubMenu(JComponent parent) {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(Color.WHITE);
        menu.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(6, 6, 6, 6)
        ));

        JLabel title = new JLabel("OPERATIONAL DISPATCH HUBS");
        title.setFont(AeroFonts.SECTION_CATEGORY);
        title.setForeground(AeroColors.TEXT_MUTED);
        title.setBorder(new EmptyBorder(4, 10, 6, 10));
        menu.add(title);
        menu.addSeparator();

        OperationalStation current = StationContext.getCurrentStation();
        for (OperationalStation station : OperationalStation.values()) {
            boolean isActive = station == current;

            JMenuItem item = new JMenuItem();
            item.setLayout(new BorderLayout(14, 0));
            item.setOpaque(true);
            item.setBackground(isActive ? new Color(0xEF, 0xF6, 0xFF) : Color.WHITE);
            item.setBorder(new EmptyBorder(6, 10, 6, 10));
            item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            left.setOpaque(false);
            JLabel code = new JLabel(station.getCode());
            code.setFont(AeroFonts.MONO_BOLD);
            code.setForeground(isActive ? AeroColors.PRIMARY : AeroColors.TEXT_MAIN);
            JLabel name = new JLabel(station.getAirportName() + " (" + station.getCity() + ")");
            name.setFont(AeroFonts.BODY);
            name.setForeground(AeroColors.TEXT_SECONDARY);
            left.add(code);
            left.add(name);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            right.setOpaque(false);
            JLabel desc = new JLabel(station.getRoleDescription());
            desc.setFont(AeroFonts.MUTED);
            desc.setForeground(AeroColors.TEXT_MUTED);
            right.add(desc);

            if (isActive) {
                AeroIcon check = new AeroIcon(AeroIcon.Type.CHECK, 13).color(AeroColors.SUCCESS);
                right.add(check);
            }

            item.add(left, BorderLayout.WEST);
            item.add(right, BorderLayout.EAST);

            item.addActionListener(evt -> {
                StationContext.setStation(station);
            });

            menu.add(item);
        }

        menu.show(parent, 0, parent.getHeight() + 4);
    }

    private static JPanel createLiveIndicator() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(AeroColors.SUCCESS_BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.setColor(new Color(0xBB, 0xF7, 0xD0));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(1, 8, 1, 8));

        JPanel dot = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(AeroColors.SUCCESS);
                g2.fillOval(0, 1, 7, 7);
                g2.dispose();
            }
        };
        dot.setOpaque(false);
        dot.setPreferredSize(new Dimension(8, 9));

        JLabel label = new JLabel("LIVE OPERATIONS");
        label.setFont(AeroFonts.BADGE);
        label.setForeground(AeroColors.SUCCESS);

        p.add(dot);
        p.add(label);
        return p;
    }

    public void updateRoute(NavRoute route) {
        titleLabel.setText(route.getTitle()); subtitleLabel.setText(route.getSubtitle());
        String username = UserSession.username() == null ? "Operator" : UserSession.username();
        userLabel.setText(username); roleLabel.setText(roleName()); avatar.setUser(username);
    }

    private static String roleName() { Role role = UserSession.getRole(); return role == null ? "Airline staff" : role.getDisplayName(); }

    private JButton createIconButton(AeroIcon.Type type, String tooltip, Runnable action) {
        JButton button = new JButton() {
            private boolean hover = false;
            {
                addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override public void mouseEntered(java.awt.event.MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(java.awt.event.MouseEvent e) { hover = false; repaint(); }
                });
            }
            @Override
            protected void paintComponent(Graphics g) {
                if (hover) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(AeroColors.SIDEBAR_HOVER);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        button.setLayout(new BorderLayout());
        button.add(new AeroIcon(type, 16).color(AeroColors.TEXT_SECONDARY));
        button.setPreferredSize(new Dimension(30, 30));
        button.setToolTipText(tooltip);
        button.setFocusPainted(false);
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(event -> action.run());
        return button;
    }

    private void showNotificationHint() {
        JDialog dialog = messageDialog("Notifications", "Operational notifications appear here when a connected workflow raises an alert.");
        dialog.setVisible(true);
    }

    private void showHelpDialog() {
        JDialog dialog = messageDialog("AeroDesk help", "Use Tab to move through controls, Enter to submit the current form, and Escape to close a dialog. Operational actions remain protected by your assigned role.");
        dialog.setVisible(true);
    }

    private JDialog messageDialog(String title, String message) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), title, JDialog.ModalityType.APPLICATION_MODAL);
        JPanel content = new JPanel(); content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS)); content.setBackground(Color.WHITE); content.setBorder(new EmptyBorder(24, 26, 20, 26));
        JLabel heading = new JLabel(title); heading.setFont(AeroFonts.TITLE_SECTION); heading.setForeground(AeroColors.TEXT_MAIN);
        JLabel body = new JLabel("<html><div style='width:320px'>" + message + "</div></html>"); body.setFont(AeroFonts.BODY); body.setForeground(AeroColors.TEXT_SECONDARY);
        JButton close = new JButton("Close"); close.setFont(AeroFonts.BUTTON); close.setForeground(Color.WHITE); close.setBackground(AeroColors.PRIMARY); close.setFocusPainted(false); close.setAlignmentX(CENTER_ALIGNMENT); close.addActionListener(event -> dialog.dispose());
        content.add(heading); content.add(Box.createVerticalStrut(12)); content.add(body); content.add(Box.createVerticalStrut(20)); content.add(close);
        dialog.setContentPane(content); dialog.pack(); dialog.setLocationRelativeTo(this); dialog.setResizable(false); return dialog;
    }

    private static final class AvatarBadge extends JPanel {
        private String initial;
        AvatarBadge(String username) { setOpaque(false); setPreferredSize(new Dimension(32, 32)); setUser(username); }
        void setUser(String username) { initial = username == null || username.isBlank() ? "A" : username.substring(0, 1).toUpperCase(); repaint(); }
        @Override protected void paintComponent(Graphics graphics) { Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setColor(AeroColors.PRIMARY); g.fillOval(0, 0, 32, 32); g.setColor(Color.WHITE); g.setFont(AeroFonts.CAPTION_BOLD); int x = (32 - g.getFontMetrics().stringWidth(initial)) / 2; int y = (32 - g.getFontMetrics().getHeight()) / 2 + g.getFontMetrics().getAscent(); g.drawString(initial, x, y); g.dispose(); }
    }
}
