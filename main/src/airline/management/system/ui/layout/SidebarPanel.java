package airline.management.system.ui.layout;

import airline.management.system.UserSession;
import airline.management.system.security.Role;
import airline.management.system.ui.components.AeroIcon;
import airline.management.system.ui.components.AeroScrollPane;
import airline.management.system.ui.navigation.NavRoute;
import airline.management.system.ui.navigation.NavigationAccessController;
import airline.management.system.ui.navigation.NavigationController;
import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/** Permission-aware AeroDesk command-center navigation. */
public class SidebarPanel extends JPanel {
    public static final int EXPANDED_WIDTH = AeroConstants.SIDEBAR_WIDTH;
    public static final int COLLAPSED_WIDTH = 72;
    private final NavigationController navigationController;
    private final Map<NavRoute, NavItemPanel> navItems = new HashMap<>();
    private final List<JComponent> expandedOnly = new ArrayList<>();
    private final JLabel wordmark;
    private final JButton toggleButton;
    private final JPanel userText;
    private final LogoutButton logoutButton;
    private boolean collapsed;

    public SidebarPanel(NavigationController controller, Runnable logoutAction) {
        navigationController = controller;
        setLayout(new BorderLayout());
        setBackground(AeroColors.SIDEBAR);
        setPreferredSize(new Dimension(EXPANDED_WIDTH, 720));
        setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, AeroColors.SIDEBAR_BORDER));

        JPanel brand = new JPanel(new BorderLayout(10, 0));
        brand.setOpaque(false);
        brand.setBorder(new EmptyBorder(20, 18, 18, 12));
        brand.add(new BrandMark(), BorderLayout.WEST);
        wordmark = new JLabel("AeroDesk");
        wordmark.setFont(AeroFonts.TITLE_SECTION);
        wordmark.setForeground(AeroColors.TEXT_MAIN);
        brand.add(wordmark, BorderLayout.CENTER);
        toggleButton = iconButton(AeroIcon.Type.CHEVRON_LEFT, "Collapse navigation");
        toggleButton.addActionListener(e -> setCollapsed(!collapsed));
        brand.add(toggleButton, BorderLayout.EAST);
        add(brand, BorderLayout.NORTH);

        JPanel menu = new JPanel();
        menu.setLayout(new BoxLayout(menu, BoxLayout.Y_AXIS));
        menu.setOpaque(false);
        menu.setBorder(new EmptyBorder(2, 10, 10, 10));
        Role role = UserSession.getRole();
        addSection(menu, "OVERVIEW", role, List.of(route(NavRoute.DASHBOARD, "Dashboard", AeroIcon.Type.DASHBOARD, "Operational overview")));
        addSection(menu, "OPERATIONS", role, List.of(
                route(NavRoute.FLIGHTS, "Flight Operations", AeroIcon.Type.FLIGHT, "Flight lifecycle and capacity"),
                route(NavRoute.IROPS, "IROPS Desk", AeroIcon.Type.ALERT, "Disruption management"),
                route(NavRoute.BOARDING, "Gate & Boarding", AeroIcon.Type.BOARDING, "Gate and passenger boarding")));
        addSection(menu, "PASSENGER SERVICES", role, List.of(
                route(NavRoute.BOOKING, "Reservation Desk", AeroIcon.Type.RESERVATION, "Reservations and ticketing"),
                route(NavRoute.PASSENGERS, "Passengers", AeroIcon.Type.PASSENGER, "Passenger profiles"),
                route(NavRoute.CHECKIN, "Check-in", AeroIcon.Type.CHECKIN, "Passenger check-in"),
                route(NavRoute.TRIPS, "Trips", AeroIcon.Type.TRIPS, "Passenger journey history")));
        addSection(menu, "BAGGAGE", role, List.of(
                route(NavRoute.BAGGAGE, "Baggage Operations", AeroIcon.Type.BAGGAGE, "Baggage handling"),
                route(NavRoute.PIR_CLAIMS, "PIR & Claims", AeroIcon.Type.CLAIM, "Baggage irregularities and claims")));
        addSection(menu, "FINANCE", role, List.of(
                route(NavRoute.PAYMENTS, "Payments & Settlement", AeroIcon.Type.PAYMENT, "Financial operations"),
                route(NavRoute.CANCELLATIONS, "Cancellations", AeroIcon.Type.CANCEL, "Cancellations and refunds")));
        addSection(menu, "ANALYTICS", role, List.of(route(NavRoute.REPORTS, "Reports", AeroIcon.Type.REPORTS, "Operational reports")));
        addSection(menu, "ADMINISTRATION", role, List.of(
                route(NavRoute.STAFF, "Staff Management", AeroIcon.Type.STAFF, "Staff and access control"),
                route(NavRoute.SETTINGS, "Settings", AeroIcon.Type.SETTINGS, "System settings")));
        menu.add(Box.createVerticalGlue());
        add(new AeroScrollPane(menu), BorderLayout.CENTER);

        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setOpaque(false);
        footer.setBorder(new EmptyBorder(12, 10, 18, 10));
        JPanel identity = new JPanel(new BorderLayout(9, 0));
        identity.setOpaque(false);
        String username = UserSession.username() == null ? "Operator" : UserSession.username();
        identity.add(new UserAvatar(username), BorderLayout.WEST);
        userText = new JPanel();
        userText.setLayout(new BoxLayout(userText, BoxLayout.Y_AXIS));
        userText.setOpaque(false);
        JLabel name = new JLabel(username);
        name.setFont(AeroFonts.BODY_BOLD);
        name.setForeground(AeroColors.TEXT_MAIN);
        JLabel roleLabel = new JLabel(role == null ? "Airline staff" : role.getDisplayName());
        roleLabel.setFont(AeroFonts.MUTED);
        roleLabel.setForeground(AeroColors.TEXT_MUTED);
        userText.add(name);
        userText.add(Box.createVerticalStrut(2));
        userText.add(roleLabel);
        identity.add(userText, BorderLayout.CENTER);
        footer.add(identity);
        footer.add(Box.createVerticalStrut(10));
        logoutButton = new LogoutButton(logoutAction);
        footer.add(logoutButton);
        add(footer, BorderLayout.SOUTH);
        controller.addListener((route, ignored) -> setActiveRoute(route));
        setActiveRoute(controller.getCurrentRoute());
    }

    private static RouteDef route(NavRoute route, String label, AeroIcon.Type icon, String hint) { return new RouteDef(route, label, icon, hint); }
    private void addSection(JPanel menu, String title, Role role, List<RouteDef> routes) {
        List<RouteDef> available = routes.stream().filter(route -> NavigationAccessController.isRouteAccessible(role, route.route)).toList();
        if (available.isEmpty()) return;
        JLabel heading = new JLabel(title);
        heading.setFont(AeroFonts.SECTION_CATEGORY);
        heading.setForeground(AeroColors.SIDEBAR_SECTION);
        heading.setBorder(new EmptyBorder(14, 12, 8, 0));
        expandedOnly.add(heading);
        menu.add(heading);
        for (RouteDef route : available) {
            NavItemPanel item = new NavItemPanel(route, () -> navigationController.navigateTo(route.route));
            navItems.put(route.route, item);
            menu.add(item);
            menu.add(Box.createVerticalStrut(3));
        }
        menu.add(Box.createVerticalStrut(5));
    }

    public void toggleCollapsed() { setCollapsed(!collapsed); }
    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        setPreferredSize(new Dimension(collapsed ? COLLAPSED_WIDTH : EXPANDED_WIDTH, getHeight()));
        wordmark.setVisible(!collapsed);
        userText.setVisible(!collapsed);
        toggleButton.removeAll();
        toggleButton.add(new AeroIcon(collapsed ? AeroIcon.Type.CHEVRON_RIGHT : AeroIcon.Type.CHEVRON_LEFT, 16).color(AeroColors.TEXT_SECONDARY));
        toggleButton.setToolTipText(collapsed ? "Expand navigation" : "Collapse navigation");
        expandedOnly.forEach(component -> component.setVisible(!collapsed));
        navItems.values().forEach(item -> item.setCollapsed(collapsed));
        logoutButton.setCollapsed(collapsed);
        revalidate(); repaint();
        if (getParent() != null) { getParent().revalidate(); getParent().repaint(); }
    }
    public boolean isCollapsed() { return collapsed; }
    private void setActiveRoute(NavRoute route) { navItems.forEach((key, item) -> item.setActive(key == route)); }

    private static JButton iconButton(AeroIcon.Type type, String hint) {
        JButton button = new JButton();
        button.setLayout(new BorderLayout());
        button.add(new AeroIcon(type, 16).color(AeroColors.TEXT_SECONDARY));
        button.setToolTipText(hint);
        button.setFocusPainted(false); button.setBorderPainted(false); button.setContentAreaFilled(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); button.setPreferredSize(new Dimension(28, 28));
        return button;
    }

    private record RouteDef(NavRoute route, String label, AeroIcon.Type icon, String hint) { }
    private static final class BrandMark extends JPanel {
        BrandMark() { setLayout(new BorderLayout()); setOpaque(false); setPreferredSize(new Dimension(30, 30)); add(new AeroIcon(AeroIcon.Type.FLIGHT, 17).color(Color.WHITE)); }
        @Override protected void paintComponent(Graphics graphics) { Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setColor(AeroColors.PRIMARY); g.fillRoundRect(0, 0, getWidth(), getHeight(), 9, 9); g.dispose(); super.paintComponent(graphics); }
    }
    private static final class NavItemPanel extends JPanel {
        private final JLabel label;
        private final AeroIcon icon;
        private boolean active;
        private boolean hovered;
        NavItemPanel(RouteDef definition, Runnable action) {
            setLayout(new BorderLayout(10, 0)); setOpaque(false); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 40)); setPreferredSize(new Dimension(220, 40)); setBorder(new EmptyBorder(0, 12, 0, 10)); setToolTipText(definition.hint);
            icon = new AeroIcon(definition.icon, 18); icon.setPreferredSize(new Dimension(18, 18)); add(icon, BorderLayout.WEST);
            label = new JLabel(definition.label); label.setFont(AeroFonts.BODY); add(label, BorderLayout.CENTER);
            MouseAdapter listener = new MouseAdapter() { @Override public void mouseEntered(MouseEvent event) { hovered = true; repaint(); } @Override public void mouseExited(MouseEvent event) { hovered = false; repaint(); } @Override public void mouseClicked(MouseEvent event) { action.run(); } };
            addMouseListener(listener); label.addMouseListener(listener); icon.addMouseListener(listener); setActive(false);
        }
        void setCollapsed(boolean collapsed) { label.setVisible(!collapsed); setBorder(new EmptyBorder(0, collapsed ? 16 : 12, 0, collapsed ? 16 : 10)); revalidate(); repaint(); }
        void setActive(boolean active) { this.active = active; Color color = active ? AeroColors.PRIMARY : AeroColors.SIDEBAR_TEXT; label.setForeground(color); label.setFont(active ? AeroFonts.BODY_BOLD : AeroFonts.BODY); icon.color(color); repaint(); }
        @Override protected void paintComponent(Graphics graphics) { Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); if (active || hovered) { g.setColor(active ? AeroColors.SIDEBAR_ACTIVE : AeroColors.SIDEBAR_HOVER); g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10); } if (active) { g.setColor(AeroColors.PRIMARY); g.fillRoundRect(0, 9, 3, getHeight() - 18, 3, 3); } g.dispose(); super.paintComponent(graphics); }
    }
    private static final class UserAvatar extends JPanel {
        private final String initial;
        UserAvatar(String username) { initial = username == null || username.isBlank() ? "U" : username.substring(0, 1).toUpperCase(); setOpaque(false); setPreferredSize(new Dimension(30, 30)); }
        @Override protected void paintComponent(Graphics graphics) { Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setColor(new Color(0xE8, 0xEE, 0xFF)); g.fillOval(0, 0, 30, 30); g.setColor(AeroColors.PRIMARY); g.setFont(AeroFonts.CAPTION_BOLD); int x = (30 - g.getFontMetrics().stringWidth(initial)) / 2; int y = (30 - g.getFontMetrics().getHeight()) / 2 + g.getFontMetrics().getAscent(); g.drawString(initial, x, y); g.dispose(); }
    }
    private static final class LogoutButton extends JPanel {
        private final JLabel label = new JLabel("Log out");
        private final AeroIcon icon = new AeroIcon(AeroIcon.Type.LOGOUT, 17).color(AeroColors.DANGER);
        private boolean hover;
        LogoutButton(Runnable logout) { setLayout(new BorderLayout(9, 0)); setOpaque(false); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); setMaximumSize(new Dimension(Integer.MAX_VALUE, 36)); setPreferredSize(new Dimension(228, 36)); setBorder(new EmptyBorder(0, 12, 0, 10)); add(icon, BorderLayout.WEST); label.setFont(AeroFonts.BODY); label.setForeground(AeroColors.DANGER); add(label, BorderLayout.CENTER); MouseAdapter listener = new MouseAdapter() { @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); } @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); } @Override public void mouseClicked(MouseEvent e) { logout.run(); } }; addMouseListener(listener); label.addMouseListener(listener); icon.addMouseListener(listener); }
        void setCollapsed(boolean collapsed) { label.setVisible(!collapsed); setBorder(new EmptyBorder(0, collapsed ? 15 : 12, 0, collapsed ? 15 : 10)); revalidate(); repaint(); }
        @Override protected void paintComponent(Graphics graphics) { if (hover) { Graphics2D g = (Graphics2D) graphics.create(); g.setColor(AeroColors.DANGER_BG); g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8); g.dispose(); } super.paintComponent(graphics); }
    }
}
