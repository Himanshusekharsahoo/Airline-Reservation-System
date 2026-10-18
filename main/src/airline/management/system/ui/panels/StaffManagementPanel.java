package airline.management.system.ui.panels;

import airline.management.system.security.Role;
import airline.management.system.staff.StaffRecord;
import airline.management.system.staff.StaffService;
import airline.management.system.ui.components.AeroScrollPane;
import airline.management.system.ui.components.ModernButton;
import airline.management.system.ui.components.ModernComboBox;
import airline.management.system.ui.components.ModernTextField;
import airline.management.system.ui.components.NotificationBanner;
import airline.management.system.ui.components.StyledTable;
import airline.management.system.ui.navigation.NavigationController;
import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;

/**
 * AeroDesk Staff & Operator Account Management Console.
 * Backed by StaffService with role assignment, status toggling, password reset,
 * and compliance audit history inspection.
 */
public final class StaffManagementPanel extends JPanel {
    private final ModernTextField search = new ModernTextField(20);
    private final ModernComboBox<String> roleFilter = new ModernComboBox<>(rolesWithAll());
    private final ModernComboBox<String> activeFilter = new ModernComboBox<>(new String[]{"All", "Active", "Inactive"});
    private final NotificationBanner banner = new NotificationBanner();
    private final List<StaffRecord> rows = new ArrayList<>();
    private final StaffTableModel model = new StaffTableModel();
    private final StyledTable table = new StyledTable(model);

    // Row action buttons
    private final ModernButton roleBtn;
    private final ModernButton toggleBtn;
    private final ModernButton resetBtn;
    private final ModernButton auditBtn;
    private final JLabel selectionLabel;

    public StaffManagementPanel(NavigationController ignored) {
        setLayout(new BorderLayout());
        setBackground(AeroColors.BACKGROUND);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(AeroColors.BACKGROUND);
        content.setBorder(new EmptyBorder(24, 28, 28, 28));

        // 1. Header Area
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Staff Management");
        title.setFont(AeroFonts.TITLE_PAGE);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel sub = new JLabel("Manage airline staff accounts, operational roles, and access status.");
        sub.setFont(AeroFonts.BODY);
        sub.setForeground(AeroColors.TEXT_SECONDARY);

        titles.add(title);
        titles.add(Box.createVerticalStrut(2));
        titles.add(sub);

        ModernButton create = new ModernButton("+ Add Staff", ModernButton.Style.PRIMARY);
        create.addActionListener(e -> showCreate());

        header.add(titles, BorderLayout.WEST);
        header.add(create, BorderLayout.EAST);
        content.add(header);
        content.add(Box.createVerticalStrut(14));

        // 2. Banner
        content.add(banner);
        content.add(Box.createVerticalStrut(12));

        // 3. Search & Filters Bar
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        filters.setBackground(Color.WHITE);
        filters.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(4, 10, 4, 10)
        ));
        search.setPlaceholder("Search username...");
        filters.add(search);
        filters.add(roleFilter);
        filters.add(activeFilter);

        ModernButton load = new ModernButton("Search", ModernButton.Style.PRIMARY);
        load.addActionListener(e -> load());
        filters.add(load);

        ModernButton resetFilters = new ModernButton("Reset", ModernButton.Style.SECONDARY);
        resetFilters.addActionListener(e -> {
            search.setText("");
            roleFilter.setSelectedIndex(0);
            activeFilter.setSelectedIndex(0);
            load();
        });
        filters.add(resetFilters);

        content.add(filters);
        content.add(Box.createVerticalStrut(16));

        // 4. Data Card
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(14, 16, 14, 16)
        ));

        // Card Top Bar with Context and Row Actions
        JPanel cardHeader = new JPanel(new BorderLayout());
        cardHeader.setOpaque(false);
        cardHeader.setBorder(new EmptyBorder(0, 0, 10, 0));

        selectionLabel = new JLabel("Select an operator from the table below to manage credentials or inspect audit trail.");
        selectionLabel.setFont(AeroFonts.CAPTION);
        selectionLabel.setForeground(AeroColors.TEXT_MUTED);
        cardHeader.add(selectionLabel, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);

        roleBtn = new ModernButton("Change Role", ModernButton.Style.SECONDARY);
        roleBtn.setEnabled(false);
        roleBtn.addActionListener(e -> changeRole());

        toggleBtn = new ModernButton("Enable / Disable", ModernButton.Style.SECONDARY);
        toggleBtn.setEnabled(false);
        toggleBtn.addActionListener(e -> toggle());

        resetBtn = new ModernButton("Reset Password", ModernButton.Style.SECONDARY);
        resetBtn.setEnabled(false);
        resetBtn.addActionListener(e -> resetPassword());

        auditBtn = new ModernButton("View Audit", ModernButton.Style.SECONDARY);
        auditBtn.setEnabled(false);
        auditBtn.addActionListener(e -> showAudit());

        actions.add(roleBtn);
        actions.add(toggleBtn);
        actions.add(resetBtn);
        actions.add(auditBtn);
        cardHeader.add(actions, BorderLayout.EAST);

        card.add(cardHeader, BorderLayout.NORTH);
        card.add(new JScrollPane(table), BorderLayout.CENTER);

        // Table selection listener
        table.getSelectionModel().addListSelectionListener(e -> {
            boolean hasSel = table.getSelectedRow() >= 0;
            roleBtn.setEnabled(hasSel);
            toggleBtn.setEnabled(hasSel);
            resetBtn.setEnabled(hasSel);
            auditBtn.setEnabled(hasSel);
            if (hasSel) {
                StaffRecord s = selected();
                if (s != null) {
                    selectionLabel.setText("Selected operator: " + s.username() + " (" + s.role().getDisplayName() + ") — " + (s.active() ? "ACTIVE" : "INACTIVE"));
                }
            } else {
                selectionLabel.setText("Select an operator from the table below to manage credentials or inspect audit trail.");
            }
        });

        content.add(card);
        add(new AeroScrollPane(content), BorderLayout.CENTER);
        load();
    }

    private void load() {
        new SwingWorker<List<StaffRecord>, Void>() {
            @Override
            protected List<StaffRecord> doInBackground() throws Exception {
                String roleVal = (String) roleFilter.getSelectedItem();
                String activeVal = (String) activeFilter.getSelectedItem();
                Boolean active = switch (activeVal != null ? activeVal : "All") {
                    case "Active" -> true;
                    case "Inactive" -> false;
                    default -> null;
                };
                return StaffService.find(search.getText(), roleVal, active);
            }

            @Override
            protected void done() {
                try {
                    rows.clear();
                    rows.addAll(get());
                    model.fireTableDataChanged();
                } catch (Exception e) {
                    banner.showMessage("Unable to load staff accounts.", NotificationBanner.Type.ERROR);
                }
            }
        }.execute();
    }

    private StaffRecord selected() {
        int r = table.getSelectedRow();
        return r < 0 ? null : rows.get(table.convertRowIndexToModel(r));
    }

    private void showCreate() {
        ModernTextField name = new ModernTextField(18);
        JPasswordField password = new JPasswordField(18);
        ModernComboBox<String> role = new ModernComboBox<>(roleNames());

        JPanel form = new JPanel(new GridLayout(3, 2, 8, 8));
        form.add(new JLabel("Username"));
        form.add(name);
        form.add(new JLabel("Temporary password"));
        form.add(password);
        form.add(new JLabel("Role"));
        form.add(role);

        if (JOptionPane.showConfirmDialog(this, form, "Create Staff Account", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        String username = name.getText();
        String roleValue = (String) role.getSelectedItem();
        char[] passwordValue = password.getPassword(); // StaffService owns and clears this temporary array.

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                StaffService.create(username, passwordValue, roleValue);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    banner.showMessage("Staff account created.", NotificationBanner.Type.SUCCESS);
                    load();
                } catch (Exception e) {
                    banner.showMessage(message(e), NotificationBanner.Type.ERROR);
                } finally {
                    Arrays.fill(passwordValue, '\0');
                }
            }
        }.execute();
    }

    private void changeRole() {
        StaffRecord s = selected();
        if (s == null) {
            banner.showMessage("Select a staff member first.", NotificationBanner.Type.WARNING);
            return;
        }

        ModernComboBox<String> role = new ModernComboBox<>(roleNames());
        role.setSelectedItem(s.role().name());

        if (JOptionPane.showConfirmDialog(this, role, "Change role for " + s.username(), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                StaffService.changeRole(s.id(), (String) role.getSelectedItem());
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    banner.showMessage("Staff role updated.", NotificationBanner.Type.SUCCESS);
                    load();
                } catch (Exception e) {
                    banner.showMessage(message(e), NotificationBanner.Type.ERROR);
                }
            }
        }.execute();
    }

    private void toggle() {
        StaffRecord s = selected();
        if (s == null) {
            banner.showMessage("Select a staff member first.", NotificationBanner.Type.WARNING);
            return;
        }

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                StaffService.setActive(s.id(), !s.active());
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    banner.showMessage("Staff access updated.", NotificationBanner.Type.SUCCESS);
                    load();
                } catch (Exception e) {
                    banner.showMessage(message(e), NotificationBanner.Type.ERROR);
                }
            }
        }.execute();
    }

    private void resetPassword() {
        StaffRecord s = selected();
        if (s == null) {
            banner.showMessage("Select a staff member first.", NotificationBanner.Type.WARNING);
            return;
        }

        JPasswordField password = new JPasswordField(18);
        if (JOptionPane.showConfirmDialog(this, password, "New password for " + s.username(), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        char[] passwordValue = password.getPassword(); // StaffService owns and clears this temporary array.

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                StaffService.resetPassword(s.id(), passwordValue);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    banner.showMessage("Password reset without exposing credentials.", NotificationBanner.Type.SUCCESS);
                } catch (Exception e) {
                    banner.showMessage(message(e), NotificationBanner.Type.ERROR);
                } finally {
                    Arrays.fill(passwordValue, '\0');
                }
            }
        }.execute();
    }

    private void showAudit() {
        StaffRecord s = selected();
        if (s == null) {
            banner.showMessage("Select a staff member first.", NotificationBanner.Type.WARNING);
            return;
        }

        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return StaffService.auditHistory(s.id());
            }

            @Override
            protected void done() {
                try {
                    List<String> items = get();
                    JTextArea area = new JTextArea(items.isEmpty() ? "No staff audit events yet." : String.join("\n", items));
                    area.setEditable(false);
                    area.setLineWrap(true);
                    area.setWrapStyleWord(true);
                    JOptionPane.showMessageDialog(StaffManagementPanel.this, new JScrollPane(area), "Audit history — " + s.username(), JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    banner.showMessage(message(e), NotificationBanner.Type.ERROR);
                }
            }
        }.execute();
    }

    private static String message(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? "Operation could not be completed." : t.getMessage();
    }

    private static String[] rolesWithAll() {
        String[] names = roleNames();
        String[] all = new String[names.length + 1];
        all[0] = "ALL";
        System.arraycopy(names, 0, all, 1, names.length);
        return all;
    }

    private static String[] roleNames() {
        return Arrays.stream(Role.values()).map(Enum::name).toArray(String[]::new);
    }

    private final class StaffTableModel extends AbstractTableModel {
        private final String[] columns = {"Username", "Role", "Status", "Created"};

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            StaffRecord s = rows.get(r);
            return switch (c) {
                case 0 -> s.username();
                case 1 -> s.role().getDisplayName();
                case 2 -> s.active() ? "ACTIVE" : "INACTIVE";
                default -> s.createdAt() == null ? "—" : s.createdAt().toString().replace('T', ' ');
            };
        }
    }
}
