package airline.management.system.ui.panels;

import airline.management.system.DatabaseConfig;
import airline.management.system.UserSession;
import airline.management.system.audit.AuditEvent;
import airline.management.system.audit.AuditService;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.security.Role;
import airline.management.system.security.RolePermissions;
import airline.management.system.tenant.TenantContext;
import airline.management.system.ui.components.AeroScrollPane;
import airline.management.system.ui.components.ModernButton;
import airline.management.system.ui.components.StyledTable;
import airline.management.system.ui.navigation.NavigationController;
import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;

public class SettingsPanel extends JPanel {
    private final NavigationController controller;

    public SettingsPanel(NavigationController controller) {
        this.controller = controller;
        setLayout(new BorderLayout());
        setBackground(AeroColors.BACKGROUND);

        JPanel mainContent = new JPanel();
        mainContent.setLayout(new BoxLayout(mainContent, BoxLayout.Y_AXIS));
        mainContent.setBackground(AeroColors.BACKGROUND);
        mainContent.setBorder(new EmptyBorder(24, 28, 28, 28));

        // 1. Account Information Card
        Role currentRole = UserSession.getRole();
        String roleDisplay = (currentRole != null) ? currentRole.getDisplayName() : "AIRLINE STAFF";
        String roleDesc = (currentRole != null) ? currentRole.getDescription() : "Standard operator privileges";

        JPanel accountCard = createSectionCard("Operator Identity & Role", "Current authenticated operator session");
        JPanel accInfo = new JPanel(new GridLayout(2, 2, 12, 8));
        accInfo.setOpaque(false);
        addSettingRow(accInfo, "Username", UserSession.username() != null ? UserSession.username() : "Staff Operator");
        addSettingRow(accInfo, "Assigned Role", roleDisplay);
        addSettingRow(accInfo, "Role Purpose", roleDesc);
        addSettingRow(accInfo, "Tenant Organization", TenantContext.getCurrentTenant().airlineName() + " (" + TenantContext.getAirlineCode() + ")");
        accountCard.add(accInfo, BorderLayout.CENTER);
        mainContent.add(accountCard);
        mainContent.add(Box.createVerticalStrut(18));

        // 2. Role Permissions Matrix Card
        JPanel permCard = createSectionCard("Assigned Operational Capabilities", "Granular permissions active for your operator role");
        Set<Permission> perms = RolePermissions.getPermissions(currentRole);
        StringBuilder permList = new StringBuilder("<html><body style='width: 600px; color: #334155; line-height: 1.6;'>");
        if (perms.isEmpty()) {
            permList.append("No active permissions assigned.");
        } else {
            int count = 0;
            for (Permission p : perms) {
                if (count++ > 0) permList.append(" • ");
                permList.append("<b>").append(p.getLabel()).append("</b>");
            }
        }
        permList.append("</body></html>");

        JLabel permLabel = new JLabel(permList.toString());
        permLabel.setFont(AeroFonts.CAPTION);
        permCard.add(permLabel, BorderLayout.CENTER);
        mainContent.add(permCard);
        mainContent.add(Box.createVerticalStrut(18));

        // 3. Database Configuration Card
        JPanel dbCard = createSectionCard("Database Connection", "Active MySQL server parameters");
        JPanel dbInfo = new JPanel(new GridLayout(3, 2, 12, 8));
        dbInfo.setOpaque(false);
        DatabaseConfig config = DatabaseConfig.load();
        addSettingRow(dbInfo, "Host & Port", config.host() + " : " + config.port());
        addSettingRow(dbInfo, "Database Name", config.name());
        addSettingRow(dbInfo, "Database User", config.user());
        addSettingRow(dbInfo, "Audit Log Buffer", AuditService.getInstance().count() + " operational events captured");
        dbCard.add(dbInfo, BorderLayout.CENTER);
        mainContent.add(dbCard);
        mainContent.add(Box.createVerticalStrut(18));

        // 4. System & Environment Card
        JPanel appCard = createSectionCard("System & Environment", "Platform specifications");
        JPanel appInfo = new JPanel(new GridLayout(3, 2, 12, 8));
        appInfo.setOpaque(false);
        addSettingRow(appInfo, "Application", AeroConstants.APP_TITLE);
        addSettingRow(appInfo, "Java Runtime", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        addSettingRow(appInfo, "Operating System", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        appCard.add(appInfo, BorderLayout.CENTER);
        mainContent.add(appCard);

        // 5. Operational Audit Trail (Database & Memory)
        if (AuthorizationService.hasPermission(Permission.VIEW_AUDIT_LOGS)) {
            mainContent.add(Box.createVerticalStrut(18));
            mainContent.add(createAuditSection());
        }

        add(new AeroScrollPane(mainContent), BorderLayout.CENTER);
    }

    private JPanel createSectionCard(String title, String subtitle) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(18, 20, 18, 20)
        ));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(0, 0, 14, 0));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(AeroFonts.TITLE_CARD);
        titleLabel.setForeground(AeroColors.TEXT_MAIN);

        JLabel subtitleLabel = new JLabel(subtitle);
        subtitleLabel.setFont(AeroFonts.CAPTION);
        subtitleLabel.setForeground(AeroColors.TEXT_SECONDARY);

        header.add(titleLabel);
        header.add(Box.createVerticalStrut(2));
        header.add(subtitleLabel);

        card.add(header, BorderLayout.NORTH);
        return card;
    }

    private void addSettingRow(JPanel grid, String label, String val) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);

        JLabel lbl = new JLabel(label);
        lbl.setFont(AeroFonts.CAPTION);
        lbl.setForeground(AeroColors.TEXT_SECONDARY);

        JLabel valLbl = new JLabel(val);
        valLbl.setFont(AeroFonts.BODY_BOLD);
        valLbl.setForeground(AeroColors.TEXT_MAIN);

        p.add(lbl);
        p.add(Box.createVerticalStrut(2));
        p.add(valLbl);
        grid.add(p);
    }

    private JPanel createAuditSection() {
        JPanel card = createSectionCard("Operational Audit Trail", "Real-time compliance logs persisted in MySQL audit_logs table");

        List<AuditEvent> auditList = new ArrayList<>();
        AuditTableModel model = new AuditTableModel(auditList);
        StyledTable table = new StyledTable(model);

        JPanel headerPanel = (JPanel) card.getComponent(0);
        JPanel topRow = new JPanel(new BorderLayout());
        topRow.setOpaque(false);
        topRow.add(headerPanel, BorderLayout.WEST);

        ModernButton refreshBtn = new ModernButton("↻ Refresh Logs", ModernButton.Style.SECONDARY);
        refreshBtn.setPreferredSize(new Dimension(130, 32));
        refreshBtn.addActionListener(e -> loadAuditLogs(model, auditList));
        topRow.add(refreshBtn, BorderLayout.EAST);

        card.remove(headerPanel);
        card.add(topRow, BorderLayout.NORTH);

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(800, 240));
        tableScroll.setBorder(BorderFactory.createLineBorder(AeroColors.BORDER, 1));
        card.add(tableScroll, BorderLayout.CENTER);

        loadAuditLogs(model, auditList);
        return card;
    }

    private void loadAuditLogs(AuditTableModel model, List<AuditEvent> list) {
        SwingWorker<List<AuditEvent>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<AuditEvent> doInBackground() throws Exception {
                return AuditService.getInstance().getAuditHistory(30);
            }

            @Override
            protected void done() {
                try {
                    list.clear();
                    list.addAll(get());
                    model.fireTableDataChanged();
                } catch (Exception ignored) {}
            }
        };
        worker.execute();
    }

    private static class AuditTableModel extends AbstractTableModel {
        private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        private final String[] columns = {"Timestamp", "Operator", "Role", "Action", "Target", "Details"};
        private final List<AuditEvent> events;

        public AuditTableModel(List<AuditEvent> events) {
            this.events = events;
        }

        @Override public int getRowCount() { return events.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            if (row >= events.size()) return "";
            AuditEvent e = events.get(row);
            return switch (col) {
                case 0 -> e.timestamp() != null ? TIME_FORMAT.format(e.timestamp()) : "—";
                case 1 -> e.username() != null ? e.username() : "SYSTEM";
                case 2 -> e.role() != null ? e.role() : "—";
                case 3 -> e.action() != null ? e.action().name() : "—";
                case 4 -> (e.targetType() != null ? e.targetType() : "") + (e.targetId() != null ? " #" + e.targetId() : "");
                case 5 -> e.details() != null ? e.details() : "—";
                default -> "";
            };
        }
    }
}
