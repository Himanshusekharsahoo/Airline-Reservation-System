package airline.management.system.ui.panels;

import airline.management.system.DBConnection;
import airline.management.system.operations.flight.RebookingFoundationService;
import airline.management.system.operations.irops.DisruptedPassengerManifestItem;
import airline.management.system.operations.irops.DisruptionReason;
import airline.management.system.operations.irops.DisruptionService;
import airline.management.system.operations.irops.DisruptionSeverity;
import airline.management.system.operations.irops.DisruptionStatus;
import airline.management.system.operations.irops.DisruptionType;
import airline.management.system.operations.irops.DisruptionVoucher;
import airline.management.system.operations.irops.DisruptionVoucherService;
import airline.management.system.operations.irops.FlightDisruption;
import airline.management.system.operations.irops.NotificationStatus;
import airline.management.system.operations.irops.PassengerReaccommodation;
import airline.management.system.operations.irops.ReaccommodationEngine;
import airline.management.system.operations.irops.ReaccommodationStatus;
import airline.management.system.operations.irops.ReaccommodationType;
import airline.management.system.operations.irops.VoucherStatus;
import airline.management.system.operations.irops.VoucherType;
import airline.management.system.tenant.TenantContext;
import airline.management.system.ui.components.DashboardCard;
import airline.management.system.ui.components.ModernButton;
import airline.management.system.ui.components.ModernComboBox;
import airline.management.system.ui.components.ModernTextField;
import airline.management.system.ui.components.NotificationBanner;
import airline.management.system.ui.components.StyledTable;
import airline.management.system.ui.navigation.NavRoute;
import airline.management.system.ui.navigation.NavigationController;
import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Enterprise IROPS (Flight Disruption & Irregular Operations) Management Console.
 * Real-time operational control for flight disruptions, impacted passenger discovery,
 * alternate flight search, single/batch re-accommodation, service recovery vouchers, and audit tracking.
 */
public class IROPSDeskPanel extends JPanel {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    private final NavigationController navigationController;
    private final NotificationBanner notificationBanner;

    // KPI Summary Cards
    private final DashboardCard activeDisruptionsCard;
    private final DashboardCard affectedPassengersCard;
    private final DashboardCard pendingReaccCard;
    private final DashboardCard outstandingVouchersCard;

    // Tabs
    private final JTabbedPane tabbedPane;

    // Disruptions Tab
    private final ModernTextField disruptionSearchField;
    private final ModernComboBox<String> disruptionStatusFilter;
    private final DisruptionsTableModel disruptionsTableModel;
    private final StyledTable disruptionsTable;
    private final List<FlightDisruption> disruptionList = new ArrayList<>();

    // Manifest Tab
    private final JLabel manifestHeaderLabel;
    private final ManifestTableModel manifestTableModel;
    private final StyledTable manifestTable;
    private final List<DisruptedPassengerManifestItem> manifestList = new ArrayList<>();
    private FlightDisruption currentDisruptionForManifest = null;

    // Vouchers Tab
    private final ModernTextField voucherSearchField;
    private final ModernComboBox<String> voucherStatusFilter;
    private final VouchersTableModel vouchersTableModel;
    private final StyledTable vouchersTable;
    private final List<DisruptionVoucher> voucherList = new ArrayList<>();

    public IROPSDeskPanel(NavigationController nav) {
        this.navigationController = nav;
        setLayout(new BorderLayout(0, 16));
        setBackground(AeroColors.BACKGROUND);
        setBorder(new EmptyBorder(16, 20, 16, 20));

        // 1. Notification Banner
        notificationBanner = new NotificationBanner();

        // 2. Top Header & KPI Bar
        JPanel topContainer = new JPanel();
        topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));
        topContainer.setOpaque(false);

        topContainer.add(notificationBanner);
        topContainer.add(Box.createVerticalStrut(8));
        topContainer.add(buildHeaderSection());
        topContainer.add(Box.createVerticalStrut(16));

        // KPI Cards Row
        JPanel kpiPanel = new JPanel(new GridLayout(1, 4, 16, 0));
        kpiPanel.setOpaque(false);
        activeDisruptionsCard = new DashboardCard("Active Disruptions", "0", "Awaiting mitigation", "alert", AeroColors.DANGER_BG);
        affectedPassengersCard = new DashboardCard("Impacted Pax", "0", "Total booked travelers", "passenger", AeroColors.WARNING_BG);
        pendingReaccCard = new DashboardCard("Pending Re-Acc", "0", "Requiring rebooking", "refresh", AeroColors.INFO_BG);
        outstandingVouchersCard = new DashboardCard("Open Vouchers", "0", "Recovery amenities", "reservation", AeroColors.SUCCESS_BG);

        kpiPanel.add(activeDisruptionsCard);
        kpiPanel.add(affectedPassengersCard);
        kpiPanel.add(pendingReaccCard);
        kpiPanel.add(outstandingVouchersCard);
        topContainer.add(kpiPanel);

        add(topContainer, BorderLayout.NORTH);

        // 3. Tabbed Operations Pane
        tabbedPane = new JTabbedPane();
        tabbedPane.setFont(AeroFonts.BODY_BOLD);
        tabbedPane.setBackground(AeroColors.CARD_BG);

        // Tab 1: Disruptions
        disruptionSearchField = new ModernTextField(16);
        disruptionSearchField.setPlaceholder("Search Reference, Flight, Route...");
        disruptionStatusFilter = new ModernComboBox<>(new String[]{"All Statuses", "DECLARED", "ASSESSING", "ACTION_REQUIRED", "MITIGATED", "RESOLVED", "CANCELLED"});
        disruptionsTableModel = new DisruptionsTableModel();
        disruptionsTable = new StyledTable(disruptionsTableModel);
        setupDisruptionsTableRenderers();
        tabbedPane.addTab("Disruptions & Irregularities", buildDisruptionsTab());

        // Tab 2: Passenger Manifest & Reaccommodation
        manifestHeaderLabel = new JLabel("No disruption selected. Select a disruption from the Disruptions tab.");
        manifestHeaderLabel.setFont(AeroFonts.TITLE_CARD);
        manifestHeaderLabel.setForeground(AeroColors.TEXT_SECONDARY);
        manifestTableModel = new ManifestTableModel();
        manifestTable = new StyledTable(manifestTableModel);
        setupManifestTableRenderers();
        tabbedPane.addTab("Passenger Manifest & Re-Accommodation", buildManifestTab());

        // Tab 3: Vouchers & Service Recovery
        voucherSearchField = new ModernTextField(16);
        voucherSearchField.setPlaceholder("Search Ref, PNR, Passenger...");
        voucherStatusFilter = new ModernComboBox<>(new String[]{"All Statuses", "ISSUED", "REDEEMED", "CANCELLED"});
        vouchersTableModel = new VouchersTableModel();
        vouchersTable = new StyledTable(vouchersTableModel);
        setupVouchersTableRenderers();
        tabbedPane.addTab("Service Recovery Vouchers", buildVouchersTab());

        add(tabbedPane, BorderLayout.CENTER);

        // Navigation payload hook
        if (navigationController != null) {
            navigationController.addListener((route, parameter) -> {
                if (route == NavRoute.IROPS) {
                    refreshAllData();
                    if (parameter instanceof String refStr) {
                        handleIncomingPayload(refStr);
                    }
                }
            });
        }
    }

    private JPanel buildHeaderSection() {
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);

        JPanel titlePanel = new JPanel();
        titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));
        titlePanel.setOpaque(false);

        JLabel titleLabel = new JLabel("Flight Disruption & Irregular Operations (IROPS) Desk");
        titleLabel.setFont(AeroFonts.TITLE_PAGE);
        titleLabel.setForeground(AeroColors.TEXT_MAIN);

        JLabel subtitleLabel = new JLabel("Authoritative airline operational disruption control, passenger manifest impact, auto/manual re-accommodation & vouchers.");
        subtitleLabel.setFont(AeroFonts.BODY);
        subtitleLabel.setForeground(AeroColors.TEXT_SECONDARY);

        titlePanel.add(titleLabel);
        titlePanel.add(Box.createVerticalStrut(4));
        titlePanel.add(subtitleLabel);

        headerPanel.add(titlePanel, BorderLayout.WEST);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnPanel.setOpaque(false);

        ModernButton declareBtn = new ModernButton("Declare Disruption", ModernButton.Style.DANGER);
        declareBtn.addActionListener(e -> showDeclareDisruptionDialog());

        ModernButton refreshBtn = new ModernButton("Refresh", ModernButton.Style.SECONDARY);
        refreshBtn.addActionListener(e -> refreshAllData());

        btnPanel.add(declareBtn);
        btnPanel.add(refreshBtn);

        headerPanel.add(btnPanel, BorderLayout.EAST);
        return headerPanel;
    }

    // -------------------------------------------------------------------------
    // TAB 1: Disruptions Tab
    // -------------------------------------------------------------------------

    private JPanel buildDisruptionsTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBackground(AeroColors.CARD_BG);
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Filter Bar
        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filterBar.setOpaque(false);

        filterBar.add(new JLabel("Search:"));
        filterBar.add(disruptionSearchField);
        filterBar.add(new JLabel("Status:"));
        filterBar.add(disruptionStatusFilter);

        ModernButton searchBtn = new ModernButton("Filter", ModernButton.Style.PRIMARY);
        searchBtn.addActionListener(e -> loadDisruptions());
        filterBar.add(searchBtn);

        panel.add(filterBar, BorderLayout.NORTH);

        // Table
        JScrollPane scrollPane = new JScrollPane(disruptionsTable);
        panel.add(scrollPane, BorderLayout.CENTER);

        // Actions Bottom Bar
        JPanel actionsBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actionsBar.setOpaque(false);

        ModernButton viewManifestBtn = new ModernButton("View Passenger Manifest", ModernButton.Style.PRIMARY);
        viewManifestBtn.addActionListener(e -> {
            FlightDisruption selected = getSelectedDisruption();
            if (selected != null) {
                selectDisruptionForManifest(selected);
                tabbedPane.setSelectedIndex(1);
            } else {
                notificationBanner.showMessage("Please select a disruption from the table first.", NotificationBanner.Type.WARNING);
            }
        });

        ModernButton updateBtn = new ModernButton("Update Disruption", ModernButton.Style.SECONDARY);
        updateBtn.addActionListener(e -> {
            FlightDisruption selected = getSelectedDisruption();
            if (selected != null) {
                showUpdateDisruptionDialog(selected);
            } else {
                notificationBanner.showMessage("Please select a disruption to update.", NotificationBanner.Type.WARNING);
            }
        });

        ModernButton resolveBtn = new ModernButton("Resolve Disruption", ModernButton.Style.SUCCESS);
        resolveBtn.addActionListener(e -> {
            FlightDisruption selected = getSelectedDisruption();
            if (selected != null) {
                showResolveDisruptionDialog(selected);
            } else {
                notificationBanner.showMessage("Please select a disruption to resolve.", NotificationBanner.Type.WARNING);
            }
        });

        ModernButton autoReaccBtn = new ModernButton("Batch Auto-Reaccommodate", ModernButton.Style.PRIMARY);
        autoReaccBtn.addActionListener(e -> {
            FlightDisruption selected = getSelectedDisruption();
            if (selected != null) {
                executeBatchAutoReaccommodation(selected);
            } else {
                notificationBanner.showMessage("Please select a disruption for auto-reaccommodation.", NotificationBanner.Type.WARNING);
            }
        });

        actionsBar.add(viewManifestBtn);
        actionsBar.add(autoReaccBtn);
        actionsBar.add(updateBtn);
        actionsBar.add(resolveBtn);

        panel.add(actionsBar, BorderLayout.SOUTH);
        return panel;
    }

    // -------------------------------------------------------------------------
    // TAB 2: Passenger Manifest & Reaccommodation Tab
    // -------------------------------------------------------------------------

    private JPanel buildManifestTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBackground(AeroColors.CARD_BG);
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setOpaque(false);
        topBar.add(manifestHeaderLabel, BorderLayout.WEST);

        ModernButton refreshManifestBtn = new ModernButton("Reload Manifest", ModernButton.Style.SECONDARY);
        refreshManifestBtn.addActionListener(e -> {
            if (currentDisruptionForManifest != null) {
                loadPassengerManifest(currentDisruptionForManifest.id());
            }
        });
        topBar.add(refreshManifestBtn, BorderLayout.EAST);

        panel.add(topBar, BorderLayout.NORTH);

        JScrollPane scrollPane = new JScrollPane(manifestTable);
        panel.add(scrollPane, BorderLayout.CENTER);

        // Actions
        JPanel actionsBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actionsBar.setOpaque(false);

        ModernButton manualReaccBtn = new ModernButton("Manual Reaccommodate...", ModernButton.Style.PRIMARY);
        manualReaccBtn.addActionListener(e -> {
            DisruptedPassengerManifestItem selectedPax = getSelectedManifestItem();
            if (selectedPax != null && currentDisruptionForManifest != null) {
                showManualReaccommodateDialog(currentDisruptionForManifest, selectedPax);
            } else {
                notificationBanner.showMessage("Select a passenger from the manifest first.", NotificationBanner.Type.WARNING);
            }
        });

        ModernButton issueVoucherBtn = new ModernButton("Issue Service Voucher...", ModernButton.Style.SUCCESS);
        issueVoucherBtn.addActionListener(e -> {
            DisruptedPassengerManifestItem selectedPax = getSelectedManifestItem();
            if (selectedPax != null && currentDisruptionForManifest != null) {
                showIssueVoucherDialog(currentDisruptionForManifest, selectedPax);
            } else {
                notificationBanner.showMessage("Select a passenger to issue a voucher.", NotificationBanner.Type.WARNING);
            }
        });

        ModernButton notifBtn = new ModernButton("Update Notification Status...", ModernButton.Style.SECONDARY);
        notifBtn.addActionListener(e -> {
            DisruptedPassengerManifestItem selectedPax = getSelectedManifestItem();
            if (selectedPax != null && selectedPax.reaccommodationId() != null) {
                showUpdateNotificationDialog(selectedPax.reaccommodationId());
            } else {
                notificationBanner.showMessage("Select a passenger with an existing reaccommodation record.", NotificationBanner.Type.WARNING);
            }
        });

        actionsBar.add(manualReaccBtn);
        actionsBar.add(issueVoucherBtn);
        actionsBar.add(notifBtn);

        panel.add(actionsBar, BorderLayout.SOUTH);
        return panel;
    }

    // -------------------------------------------------------------------------
    // TAB 3: Vouchers Tab
    // -------------------------------------------------------------------------

    private JPanel buildVouchersTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBackground(AeroColors.CARD_BG);
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Filter Bar
        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filterBar.setOpaque(false);

        filterBar.add(new JLabel("Search:"));
        filterBar.add(voucherSearchField);
        filterBar.add(new JLabel("Status:"));
        filterBar.add(voucherStatusFilter);

        ModernButton searchBtn = new ModernButton("Filter", ModernButton.Style.PRIMARY);
        searchBtn.addActionListener(e -> loadVouchers());
        filterBar.add(searchBtn);

        panel.add(filterBar, BorderLayout.NORTH);

        JScrollPane scrollPane = new JScrollPane(vouchersTable);
        panel.add(scrollPane, BorderLayout.CENTER);

        // Actions Bottom Bar
        JPanel actionsBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actionsBar.setOpaque(false);

        ModernButton redeemBtn = new ModernButton("Redeem Voucher", ModernButton.Style.SUCCESS);
        redeemBtn.addActionListener(e -> {
            DisruptionVoucher selected = getSelectedVoucher();
            if (selected != null) {
                showRedeemVoucherDialog(selected);
            } else {
                notificationBanner.showMessage("Select a voucher to redeem.", NotificationBanner.Type.WARNING);
            }
        });

        ModernButton cancelBtn = new ModernButton("Cancel Voucher", ModernButton.Style.DANGER);
        cancelBtn.addActionListener(e -> {
            DisruptionVoucher selected = getSelectedVoucher();
            if (selected != null) {
                showCancelVoucherDialog(selected);
            } else {
                notificationBanner.showMessage("Select a voucher to cancel.", NotificationBanner.Type.WARNING);
            }
        });

        actionsBar.add(redeemBtn);
        actionsBar.add(cancelBtn);

        panel.add(actionsBar, BorderLayout.SOUTH);
        return panel;
    }

    // -------------------------------------------------------------------------
    // Data Loading & Async Workers (Zero SQL on EDT)
    // -------------------------------------------------------------------------

    public void refreshAllData() {
        loadDisruptions();
        loadKPIs();
        loadVouchers();
        if (currentDisruptionForManifest != null) {
            loadPassengerManifest(currentDisruptionForManifest.id());
        }
    }

    private void loadKPIs() {
        new SwingWorker<int[], Void>() {
            @Override
            protected int[] doInBackground() throws Exception {
                long orgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
                int activeDisruptions = 0;
                int totalImpactedPax = 0;
                int pendingReacc = 0;
                int openVouchers = 0;

                try (Connection conn = DBConnection.getConnection()) {
                    // 1. Active Disruptions & impacted pax
                    if (tableExists(conn, "flight_disruptions")) {
                        try (PreparedStatement stmt = conn.prepareStatement(
                                "SELECT COUNT(*), COALESCE(SUM(passenger_impact_count), 0) FROM flight_disruptions WHERE organization_id = ? AND status NOT IN ('RESOLVED', 'CANCELLED')")) {
                            stmt.setLong(1, orgId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) {
                                    activeDisruptions = rs.getInt(1);
                                    totalImpactedPax = rs.getInt(2);
                                }
                            }
                        }
                    }

                    // 2. Pending Re-accommodations
                    if (tableExists(conn, "passenger_reaccommodations")) {
                        try (PreparedStatement stmt = conn.prepareStatement(
                                "SELECT COUNT(*) FROM passenger_reaccommodations WHERE organization_id = ? AND status IN ('PROPOSED', 'ACCEPTED', 'PENDING')")) {
                            stmt.setLong(1, orgId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) pendingReacc = rs.getInt(1);
                            }
                        }
                    }

                    // 3. Open Vouchers
                    if (tableExists(conn, "disruption_vouchers")) {
                        try (PreparedStatement stmt = conn.prepareStatement(
                                "SELECT COUNT(*) FROM disruption_vouchers WHERE organization_id = ? AND status = 'ISSUED'")) {
                            stmt.setLong(1, orgId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) openVouchers = rs.getInt(1);
                            }
                        }
                    }
                }
                return new int[]{activeDisruptions, totalImpactedPax, pendingReacc, openVouchers};
            }

            @Override
            protected void done() {
                try {
                    int[] kpis = get();
                    activeDisruptionsCard.setValue(String.valueOf(kpis[0]));
                    affectedPassengersCard.setValue(String.valueOf(kpis[1]));
                    pendingReaccCard.setValue(String.valueOf(kpis[2]));
                    outstandingVouchersCard.setValue(String.valueOf(kpis[3]));
                } catch (Exception ignored) {}
            }
        }.execute();
    }

    private void loadDisruptions() {
        String query = disruptionSearchField.getText().trim();
        String statusStr = (String) disruptionStatusFilter.getSelectedItem();
        DisruptionStatus filterStatus = null;
        if (statusStr != null && !statusStr.startsWith("All")) {
            try { filterStatus = DisruptionStatus.valueOf(statusStr); } catch (Exception ignored) {}
        }

        final DisruptionStatus fStatus = filterStatus;
        new SwingWorker<List<FlightDisruption>, Void>() {
            @Override
            protected List<FlightDisruption> doInBackground() throws Exception {
                return DisruptionService.getInstance().searchDisruptions(query, fStatus);
            }

            @Override
            protected void done() {
                try {
                    disruptionList.clear();
                    disruptionList.addAll(get());
                    disruptionsTableModel.fireTableDataChanged();
                } catch (Exception e) {
                    notificationBanner.showMessage("Error loading disruptions: " + e.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        }.execute();
    }

    private void loadPassengerManifest(long disruptionId) {
        new SwingWorker<List<DisruptedPassengerManifestItem>, Void>() {
            @Override
            protected List<DisruptedPassengerManifestItem> doInBackground() throws Exception {
                return DisruptionService.getInstance().getAffectedPassengerManifest(disruptionId);
            }

            @Override
            protected void done() {
                try {
                    manifestList.clear();
                    manifestList.addAll(get());
                    manifestTableModel.fireTableDataChanged();
                } catch (Exception e) {
                    notificationBanner.showMessage("Error loading manifest: " + e.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        }.execute();
    }

    private void loadVouchers() {
        String query = voucherSearchField.getText().trim();
        String statusStr = (String) voucherStatusFilter.getSelectedItem();
        VoucherStatus filterStatus = null;
        if (statusStr != null && !statusStr.startsWith("All")) {
            try { filterStatus = VoucherStatus.valueOf(statusStr); } catch (Exception ignored) {}
        }

        final VoucherStatus fStatus = filterStatus;
        new SwingWorker<List<DisruptionVoucher>, Void>() {
            @Override
            protected List<DisruptionVoucher> doInBackground() throws Exception {
                return DisruptionVoucherService.getInstance().searchVouchers(query, fStatus, null);
            }

            @Override
            protected void done() {
                try {
                    voucherList.clear();
                    voucherList.addAll(get());
                    vouchersTableModel.fireTableDataChanged();
                } catch (Exception e) {
                    notificationBanner.showMessage("Error loading vouchers: " + e.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        }.execute();
    }

    // -------------------------------------------------------------------------
    // Operations Handlers & Dialogs
    // -------------------------------------------------------------------------

    private void selectDisruptionForManifest(FlightDisruption disruption) {
        this.currentDisruptionForManifest = disruption;
        manifestHeaderLabel.setText(String.format("Manifest for Disruption [%s] — Flight %s (%s) — %s (%d affected)",
            disruption.disruptionReference(), disruption.flightNumber(), disruption.route(),
            disruption.type().getDisplayName(), disruption.passengerImpactCount()));
        manifestHeaderLabel.setForeground(AeroColors.TEXT_MAIN);
        loadPassengerManifest(disruption.id());
    }

    private void handleIncomingPayload(String payload) {
        if (payload == null || payload.isBlank()) return;
        disruptionSearchField.setText(payload);
        loadDisruptions();
    }

    private void showDeclareDisruptionDialog() {
        JDialog dialog = new JDialog((java.awt.Frame) SwingUtilities.getWindowAncestor(this), "Declare Flight Disruption", true);
        dialog.setLayout(new BorderLayout(0, 16));
        dialog.setSize(520, 500);
        dialog.setLocationRelativeTo(this);

        JPanel formPanel = new JPanel(new GridLayout(7, 2, 8, 8));
        formPanel.setBorder(new EmptyBorder(16, 20, 16, 20));

        ModernTextField flightIdField = new ModernTextField(10);
        ModernComboBox<DisruptionType> typeCombo = new ModernComboBox<>(DisruptionType.values());
        ModernComboBox<DisruptionReason> reasonCombo = new ModernComboBox<>(DisruptionReason.values());
        ModernComboBox<DisruptionSeverity> severityCombo = new ModernComboBox<>(DisruptionSeverity.values());
        ModernTextField delayField = new ModernTextField(6);
        delayField.setText("0");
        ModernTextField impactField = new ModernTextField(20);
        ModernTextField remarksField = new ModernTextField(20);

        formPanel.add(new JLabel("Flight ID:"));
        formPanel.add(flightIdField);
        formPanel.add(new JLabel("Disruption Type:"));
        formPanel.add(typeCombo);
        formPanel.add(new JLabel("Operational Reason:"));
        formPanel.add(reasonCombo);
        formPanel.add(new JLabel("Severity:"));
        formPanel.add(severityCombo);
        formPanel.add(new JLabel("Est. Delay (Minutes):"));
        formPanel.add(delayField);
        formPanel.add(new JLabel("Impact Summary:"));
        formPanel.add(impactField);
        formPanel.add(new JLabel("Remarks:"));
        formPanel.add(remarksField);

        dialog.add(formPanel, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        ModernButton confirmBtn = new ModernButton("Declare Disruption", ModernButton.Style.DANGER);
        confirmBtn.addActionListener(e -> {
            try {
                long flightId = Long.parseLong(flightIdField.getText().trim());
                DisruptionType type = (DisruptionType) typeCombo.getSelectedItem();
                DisruptionReason reason = (DisruptionReason) reasonCombo.getSelectedItem();
                DisruptionSeverity severity = (DisruptionSeverity) severityCombo.getSelectedItem();
                int delay = Integer.parseInt(delayField.getText().trim());
                String impact = impactField.getText().trim();
                String remarks = remarksField.getText().trim();

                if (impact.isBlank()) {
                    JOptionPane.showMessageDialog(dialog, "Please enter an impact summary.", "Validation Error", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                new SwingWorker<FlightDisruption, Void>() {
                    @Override
                    protected FlightDisruption doInBackground() throws Exception {
                        return DisruptionService.getInstance().declareDisruption(flightId, type, reason, severity, delay, impact, remarks);
                    }

                    @Override
                    protected void done() {
                        try {
                            FlightDisruption created = get();
                            dialog.dispose();
                            notificationBanner.showMessage("Disruption [" + created.disruptionReference() + "] declared successfully for flight " + created.flightNumber() + " (" + created.passengerImpactCount() + " passengers impacted).", NotificationBanner.Type.SUCCESS);
                            refreshAllData();
                            selectDisruptionForManifest(created);
                            tabbedPane.setSelectedIndex(1);
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(dialog, "Error declaring disruption: " + ex.getMessage(), "Operation Failed", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }.execute();
            } catch (NumberFormatException nfe) {
                JOptionPane.showMessageDialog(dialog, "Please enter valid numbers for Flight ID and Delay Minutes.", "Input Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        ModernButton cancelBtn = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        cancelBtn.addActionListener(e -> dialog.dispose());

        btnPanel.add(cancelBtn);
        btnPanel.add(confirmBtn);
        dialog.add(btnPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    private void showUpdateDisruptionDialog(FlightDisruption d) {
        if (d.isTerminal()) {
            notificationBanner.showMessage("Disruption [" + d.disruptionReference() + "] is in terminal state (" + d.status() + ") and cannot be modified.", NotificationBanner.Type.WARNING);
            return;
        }

        JDialog dialog = new JDialog((java.awt.Frame) SwingUtilities.getWindowAncestor(this), "Update Disruption [" + d.disruptionReference() + "]", true);
        dialog.setLayout(new BorderLayout(0, 16));
        dialog.setSize(480, 420);
        dialog.setLocationRelativeTo(this);

        JPanel formPanel = new JPanel(new GridLayout(5, 2, 8, 8));
        formPanel.setBorder(new EmptyBorder(16, 20, 16, 20));

        ModernComboBox<DisruptionSeverity> severityCombo = new ModernComboBox<>(DisruptionSeverity.values());
        severityCombo.setSelectedItem(d.severity());
        ModernTextField delayField = new ModernTextField(6);
        delayField.setText(String.valueOf(d.delayMinutes()));
        ModernTextField impactField = new ModernTextField(20);
        impactField.setText(d.impactDescription());
        ModernComboBox<DisruptionStatus> statusCombo = new ModernComboBox<>(DisruptionStatus.values());
        statusCombo.setSelectedItem(d.status());
        ModernTextField remarksField = new ModernTextField(20);
        remarksField.setText(d.remarks() != null ? d.remarks() : "");

        formPanel.add(new JLabel("Severity:"));
        formPanel.add(severityCombo);
        formPanel.add(new JLabel("Delay (Minutes):"));
        formPanel.add(delayField);
        formPanel.add(new JLabel("Status:"));
        formPanel.add(statusCombo);
        formPanel.add(new JLabel("Impact Summary:"));
        formPanel.add(impactField);
        formPanel.add(new JLabel("Remarks:"));
        formPanel.add(remarksField);

        dialog.add(formPanel, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        ModernButton saveBtn = new ModernButton("Save Changes", ModernButton.Style.PRIMARY);
        saveBtn.addActionListener(e -> {
            try {
                int delay = Integer.parseInt(delayField.getText().trim());
                DisruptionSeverity sev = (DisruptionSeverity) severityCombo.getSelectedItem();
                DisruptionStatus newSt = (DisruptionStatus) statusCombo.getSelectedItem();
                String imp = impactField.getText().trim();
                String rem = remarksField.getText().trim();

                new SwingWorker<Void, Void>() {
                    @Override
                    protected Void doInBackground() throws Exception {
                        DisruptionService.getInstance().updateDisruption(d.id(), sev, delay, imp, rem);
                        if (newSt != d.status()) {
                            DisruptionService.getInstance().changeStatus(d.id(), newSt, rem);
                        }
                        return null;
                    }

                    @Override
                    protected void done() {
                        try {
                            get();
                            dialog.dispose();
                            notificationBanner.showMessage("Disruption [" + d.disruptionReference() + "] updated successfully.", NotificationBanner.Type.SUCCESS);
                            refreshAllData();
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(dialog, "Update failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }.execute();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Input error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        ModernButton cancelBtn = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        cancelBtn.addActionListener(e -> dialog.dispose());

        btnPanel.add(cancelBtn);
        btnPanel.add(saveBtn);
        dialog.add(btnPanel, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void showResolveDisruptionDialog(FlightDisruption d) {
        if (d.isTerminal()) {
            notificationBanner.showMessage("Disruption [" + d.disruptionReference() + "] is already in terminal state.", NotificationBanner.Type.WARNING);
            return;
        }

        String remarks = JOptionPane.showInputDialog(this, "Enter resolution details / closing remarks:", "Resolve Disruption [" + d.disruptionReference() + "]", JOptionPane.QUESTION_MESSAGE);
        if (remarks == null) return;

        new SwingWorker<FlightDisruption, Void>() {
            @Override
            protected FlightDisruption doInBackground() throws Exception {
                return DisruptionService.getInstance().resolveDisruption(d.id(), remarks);
            }

            @Override
            protected void done() {
                try {
                    get();
                    notificationBanner.showMessage("Disruption [" + d.disruptionReference() + "] has been resolved successfully.", NotificationBanner.Type.SUCCESS);
                    refreshAllData();
                } catch (Exception ex) {
                    notificationBanner.showMessage("Error resolving disruption: " + ex.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        }.execute();
    }

    private void executeBatchAutoReaccommodation(FlightDisruption d) {
        if (d.isTerminal()) {
            notificationBanner.showMessage("Cannot auto-reaccommodate for resolved/cancelled disruption.", NotificationBanner.Type.WARNING);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
            "Are you sure you want to trigger automatic batch re-accommodation for all unaccommodated passengers on flight " + d.flightNumber() + "?",
            "Confirm Batch Auto-Reaccommodation", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        new SwingWorker<ReaccommodationEngine.BatchReaccommodationResult, Void>() {
            @Override
            protected ReaccommodationEngine.BatchReaccommodationResult doInBackground() throws Exception {
                return ReaccommodationEngine.getInstance().autoReaccommodateAll(d.id(), "Batch IROPS re-accommodation");
            }

            @Override
            protected void done() {
                try {
                    ReaccommodationEngine.BatchReaccommodationResult res = get();
                    notificationBanner.showMessage(String.format("Auto-reaccommodation completed: %d total candidates, %d rebooked, %d failed.",
                        res.totalCandidates(), res.rebookedCount(), res.failedCount()),
                        res.failedCount() > 0 ? NotificationBanner.Type.WARNING : NotificationBanner.Type.SUCCESS);
                    refreshAllData();
                    selectDisruptionForManifest(d);
                } catch (Exception ex) {
                    notificationBanner.showMessage("Batch re-accommodation error: " + ex.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        }.execute();
    }

    private void showManualReaccommodateDialog(FlightDisruption disruption, DisruptedPassengerManifestItem pax) {
        if (disruption.isTerminal()) {
            notificationBanner.showMessage("Disruption is resolved; manual re-accommodation not permitted.", NotificationBanner.Type.WARNING);
            return;
        }
        if ("REBOOKED".equalsIgnoreCase(pax.reservationStatus()) || pax.reaccommodationStatus() == ReaccommodationStatus.REBOOKED) {
            notificationBanner.showMessage("Passenger " + pax.passengerName() + " has already been re-accommodated.", NotificationBanner.Type.WARNING);
            return;
        }

        JDialog dialog = new JDialog((java.awt.Frame) SwingUtilities.getWindowAncestor(this), "Reaccommodate: " + pax.passengerName() + " (" + pax.pnr() + ")", true);
        dialog.setLayout(new BorderLayout(0, 16));
        dialog.setSize(600, 450);
        dialog.setLocationRelativeTo(this);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(new EmptyBorder(12, 12, 12, 12));

        JLabel info = new JLabel("<html><b>Passenger:</b> " + pax.passengerName() + " | <b>Current Flight:</b> " + pax.flightNumber() + " (" + pax.route() + ")<br>Searching eligible alternative flights...</html>");
        content.add(info, BorderLayout.NORTH);

        // Alternatives table
        AlternativeFlightsTableModel altModel = new AlternativeFlightsTableModel();
        StyledTable altTable = new StyledTable(altModel);
        JScrollPane scroll = new JScrollPane(altTable);
        content.add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        ModernButton rebookBtn = new ModernButton("Reaccommodate onto Selected Flight", ModernButton.Style.PRIMARY);
        rebookBtn.setEnabled(false);

        altTable.getSelectionModel().addListSelectionListener(e -> {
            rebookBtn.setEnabled(altTable.getSelectedRow() >= 0);
        });

        rebookBtn.addActionListener(e -> {
            int row = altTable.getSelectedRow();
            if (row < 0) return;
            RebookingFoundationService.AlternativeFlight altFlight = altModel.getFlightAt(row);

            new SwingWorker<PassengerReaccommodation, Void>() {
                @Override
                protected PassengerReaccommodation doInBackground() throws Exception {
                    return ReaccommodationEngine.getInstance().reaccommodatePassenger(
                        disruption.id(),
                        pax.reservationId(),
                        altFlight.flightId(),
                        null,
                        ReaccommodationType.MANUAL,
                        "Manual reaccommodation by operator"
                    );
                }

                @Override
                protected void done() {
                    try {
                        PassengerReaccommodation r = get();
                        dialog.dispose();
                        notificationBanner.showMessage("Passenger " + pax.passengerName() + " rebooked onto flight " + altFlight.flightNumber() + " (New PNR: " + r.newPnr() + ", Seat: " + r.newSeatNumber() + ").", NotificationBanner.Type.SUCCESS);
                        refreshAllData();
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(dialog, "Reaccommodation failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });

        ModernButton cancelBtn = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        cancelBtn.addActionListener(e -> dialog.dispose());

        bottom.add(cancelBtn);
        bottom.add(rebookBtn);
        content.add(bottom, BorderLayout.SOUTH);
        dialog.add(content);

        // Fetch alternatives in background
        new SwingWorker<List<RebookingFoundationService.AlternativeFlight>, Void>() {
            @Override
            protected List<RebookingFoundationService.AlternativeFlight> doInBackground() throws Exception {
                return ReaccommodationEngine.getInstance().searchEligibleAlternateFlights(disruption.flightId());
            }

            @Override
            protected void done() {
                try {
                    List<RebookingFoundationService.AlternativeFlight> flights = get();
                    altModel.setFlights(flights);
                    info.setText("<html><b>Passenger:</b> " + pax.passengerName() + " | <b>Current Flight:</b> " + pax.flightNumber() + " (" + pax.route() + ")<br>Found <b>" + flights.size() + "</b> eligible alternative flights:</html>");
                } catch (Exception ex) {
                    info.setText("<html><font color='red'>Failed to query alternative flights: " + ex.getMessage() + "</font></html>");
                }
            }
        }.execute();

        dialog.setVisible(true);
    }

    private void showIssueVoucherDialog(FlightDisruption disruption, DisruptedPassengerManifestItem pax) {
        JDialog dialog = new JDialog((java.awt.Frame) SwingUtilities.getWindowAncestor(this), "Issue Voucher for " + pax.passengerName(), true);
        dialog.setLayout(new BorderLayout(0, 16));
        dialog.setSize(460, 360);
        dialog.setLocationRelativeTo(this);

        JPanel form = new JPanel(new GridLayout(5, 2, 8, 8));
        form.setBorder(new EmptyBorder(16, 20, 16, 20));

        ModernComboBox<VoucherType> typeCombo = new ModernComboBox<>(VoucherType.values());
        ModernTextField amountField = new ModernTextField(8);
        amountField.setText("50.00");
        ModernTextField currField = new ModernTextField(4);
        currField.setText("USD");
        ModernTextField remarksField = new ModernTextField(20);
        remarksField.setText("Amenity voucher for disrupted flight " + disruption.flightNumber());

        form.add(new JLabel("Voucher Type:"));
        form.add(typeCombo);
        form.add(new JLabel("Amount:"));
        form.add(amountField);
        form.add(new JLabel("Currency:"));
        form.add(currField);
        form.add(new JLabel("Remarks:"));
        form.add(remarksField);

        dialog.add(form, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        ModernButton issueBtn = new ModernButton("Issue Voucher", ModernButton.Style.SUCCESS);
        issueBtn.addActionListener(e -> {
            try {
                VoucherType type = (VoucherType) typeCombo.getSelectedItem();
                BigDecimal amount = new BigDecimal(amountField.getText().trim());
                String curr = currField.getText().trim();
                String remarks = remarksField.getText().trim();

                new SwingWorker<DisruptionVoucher, Void>() {
                    @Override
                    protected DisruptionVoucher doInBackground() throws Exception {
                        return DisruptionVoucherService.getInstance().issueVoucher(
                            disruption.id(),
                            pax.passengerId(),
                            pax.reservationId(),
                            type,
                            amount,
                            curr,
                            remarks
                        );
                    }

                    @Override
                    protected void done() {
                        try {
                            DisruptionVoucher v = get();
                            dialog.dispose();
                            notificationBanner.showMessage("Voucher [" + v.voucherReference() + "] for " + v.currency() + " " + v.amount() + " issued to " + pax.passengerName() + ".", NotificationBanner.Type.SUCCESS);
                            refreshAllData();
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(dialog, "Failed to issue voucher: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }.execute();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Input error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        ModernButton cancelBtn = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        cancelBtn.addActionListener(e -> dialog.dispose());

        btnPanel.add(cancelBtn);
        btnPanel.add(issueBtn);
        dialog.add(btnPanel, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void showUpdateNotificationDialog(long reaccommodationId) {
        ModernComboBox<NotificationStatus> notifCombo = new ModernComboBox<>(NotificationStatus.values());
        int res = JOptionPane.showConfirmDialog(this, notifCombo, "Select Passenger Notification Status", JOptionPane.OK_CANCEL_OPTION);
        if (res != JOptionPane.OK_OPTION) return;

        NotificationStatus newStatus = (NotificationStatus) notifCombo.getSelectedItem();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                ReaccommodationEngine.getInstance().updateNotificationStatus(reaccommodationId, newStatus);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    notificationBanner.showMessage("Notification status updated to " + newStatus, NotificationBanner.Type.SUCCESS);
                    if (currentDisruptionForManifest != null) loadPassengerManifest(currentDisruptionForManifest.id());
                } catch (Exception ex) {
                    notificationBanner.showMessage("Failed to update notification status: " + ex.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        }.execute();
    }

    private void showRedeemVoucherDialog(DisruptionVoucher v) {
        if (v.status() != VoucherStatus.ISSUED) {
            notificationBanner.showMessage("Only ISSUED vouchers can be redeemed.", NotificationBanner.Type.WARNING);
            return;
        }

        String remarks = JOptionPane.showInputDialog(this, "Enter redemption remarks:", "Redeem Voucher [" + v.voucherReference() + "]", JOptionPane.QUESTION_MESSAGE);
        if (remarks == null) return;

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                DisruptionVoucherService.getInstance().redeemVoucher(v.id(), remarks);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    notificationBanner.showMessage("Voucher [" + v.voucherReference() + "] redeemed successfully.", NotificationBanner.Type.SUCCESS);
                    refreshAllData();
                } catch (Exception ex) {
                    notificationBanner.showMessage("Redemption failed: " + ex.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        }.execute();
    }

    private void showCancelVoucherDialog(DisruptionVoucher v) {
        if (v.status() != VoucherStatus.ISSUED) {
            notificationBanner.showMessage("Only ISSUED vouchers can be cancelled.", NotificationBanner.Type.WARNING);
            return;
        }

        String reason = JOptionPane.showInputDialog(this, "Enter cancellation reason:", "Cancel Voucher [" + v.voucherReference() + "]", JOptionPane.QUESTION_MESSAGE);
        if (reason == null) return;

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                DisruptionVoucherService.getInstance().cancelVoucher(v.id(), reason);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    notificationBanner.showMessage("Voucher [" + v.voucherReference() + "] cancelled.", NotificationBanner.Type.SUCCESS);
                    refreshAllData();
                } catch (Exception ex) {
                    notificationBanner.showMessage("Cancellation failed: " + ex.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        }.execute();
    }

    // -------------------------------------------------------------------------
    // Selection Helpers
    // -------------------------------------------------------------------------

    private FlightDisruption getSelectedDisruption() {
        int row = disruptionsTable.getSelectedRow();
        if (row >= 0 && row < disruptionList.size()) {
            return disruptionList.get(row);
        }
        return null;
    }

    private DisruptedPassengerManifestItem getSelectedManifestItem() {
        int row = manifestTable.getSelectedRow();
        if (row >= 0 && row < manifestList.size()) {
            return manifestList.get(row);
        }
        return null;
    }

    private DisruptionVoucher getSelectedVoucher() {
        int row = vouchersTable.getSelectedRow();
        if (row >= 0 && row < voucherList.size()) {
            return voucherList.get(row);
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Table Models & Renderers
    // -------------------------------------------------------------------------

    private void setupDisruptionsTableRenderers() {
        disruptionsTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(javax.swing.JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected && row < disruptionList.size()) {
                    FlightDisruption d = disruptionList.get(row);
                    if (d.status() == DisruptionStatus.RESOLVED) {
                        c.setForeground(new Color(46, 125, 50));
                    } else if (d.status() == DisruptionStatus.CANCELLED) {
                        c.setForeground(Color.GRAY);
                    } else if (d.severity() == DisruptionSeverity.CRITICAL) {
                        c.setForeground(new Color(198, 40, 40));
                    } else {
                        c.setForeground(AeroColors.TEXT_MAIN);
                    }
                }
                return c;
            }
        });
    }

    private void setupManifestTableRenderers() {
        manifestTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(javax.swing.JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected && row < manifestList.size()) {
                    DisruptedPassengerManifestItem item = manifestList.get(row);
                    if (item.reaccommodationStatus() == ReaccommodationStatus.REBOOKED) {
                        c.setForeground(new Color(46, 125, 50));
                    } else if (item.reaccommodationStatus() == ReaccommodationStatus.FAILED) {
                        c.setForeground(new Color(198, 40, 40));
                    } else {
                        c.setForeground(AeroColors.TEXT_MAIN);
                    }
                }
                return c;
            }
        });
    }

    private void setupVouchersTableRenderers() {
        vouchersTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(javax.swing.JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected && row < voucherList.size()) {
                    DisruptionVoucher v = voucherList.get(row);
                    if (v.status() == VoucherStatus.REDEEMED) {
                        c.setForeground(new Color(46, 125, 50));
                    } else if (v.status() == VoucherStatus.CANCELLED) {
                        c.setForeground(Color.GRAY);
                    } else {
                        c.setForeground(AeroColors.TEXT_MAIN);
                    }
                }
                return c;
            }
        });
    }

    private class DisruptionsTableModel extends AbstractTableModel {
        private final String[] columns = {"Reference", "Flight", "Route", "Type", "Reason", "Severity", "Delay", "Impacted Pax", "Status", "Declared At"};

        @Override public int getRowCount() { return disruptionList.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            FlightDisruption d = disruptionList.get(row);
            return switch (col) {
                case 0 -> d.disruptionReference();
                case 1 -> d.flightNumber();
                case 2 -> d.route();
                case 3 -> d.type().getDisplayName();
                case 4 -> d.reason().getDisplayName();
                case 5 -> d.severity().name();
                case 6 -> d.delayMinutes() > 0 ? (d.delayMinutes() + " m") : "—";
                case 7 -> d.passengerImpactCount();
                case 8 -> d.status().name();
                case 9 -> d.declaredAt().format(TIME_FMT);
                default -> "—";
            };
        }
    }

    private class ManifestTableModel extends AbstractTableModel {
        private final String[] columns = {"PNR", "Passenger", "Phone", "Seat", "Res Status", "Payment", "Checkin", "Baggage", "PIR", "Re-Acc Status", "New Flight", "New PNR", "Vouchers"};

        @Override public int getRowCount() { return manifestList.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            DisruptedPassengerManifestItem item = manifestList.get(row);
            return switch (col) {
                case 0 -> item.pnr();
                case 1 -> item.passengerName();
                case 2 -> item.phone() != null ? item.phone() : "—";
                case 3 -> item.seatNumber();
                case 4 -> item.reservationStatus();
                case 5 -> item.paymentStatus();
                case 6 -> item.checkinStatus();
                case 7 -> item.baggageCount() > 0 ? (item.baggageCount() + " bags (" + item.baggageStatus() + ")") : "0";
                case 8 -> item.pirReference() != null ? (item.pirReference() + " (" + item.pirStatus() + ")") : "—";
                case 9 -> item.reaccommodationStatus() != null ? item.reaccommodationStatus().name() : "PENDING";
                case 10 -> item.newFlightNumber() != null ? item.newFlightNumber() : "—";
                case 11 -> item.newPnr() != null ? item.newPnr() : "—";
                case 12 -> item.voucherCount();
                default -> "—";
            };
        }
    }

    private class VouchersTableModel extends AbstractTableModel {
        private final String[] columns = {"Reference", "Disruption Ref", "Passenger", "PNR", "Type", "Amount", "Status", "Issued By", "Issued At"};

        @Override public int getRowCount() { return voucherList.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            DisruptionVoucher v = voucherList.get(row);
            return switch (col) {
                case 0 -> v.voucherReference();
                case 1 -> v.disruptionReference();
                case 2 -> v.passengerName();
                case 3 -> v.pnr();
                case 4 -> v.type().name();
                case 5 -> v.currency() + " " + v.amount();
                case 6 -> v.status().name();
                case 7 -> v.issuedByUsername() != null ? v.issuedByUsername() : ("#" + v.issuedByUserId());
                case 8 -> v.issuedAt().format(TIME_FMT);
                default -> "—";
            };
        }
    }

    private static class AlternativeFlightsTableModel extends AbstractTableModel {
        private final String[] columns = {"Flight Number", "Airline", "Route", "Departure", "Available Seats", "Fare"};
        private List<RebookingFoundationService.AlternativeFlight> flights = new ArrayList<>();

        public void setFlights(List<RebookingFoundationService.AlternativeFlight> f) {
            this.flights = f != null ? f : new ArrayList<>();
            fireTableDataChanged();
        }

        public RebookingFoundationService.AlternativeFlight getFlightAt(int r) {
            return flights.get(r);
        }

        @Override public int getRowCount() { return flights.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            RebookingFoundationService.AlternativeFlight f = flights.get(row);
            return switch (col) {
                case 0 -> f.flightNumber();
                case 1 -> f.airline();
                case 2 -> f.source() + " → " + f.destination();
                case 3 -> f.departureTime().format(TIME_FMT);
                case 4 -> f.availableSeats();
                case 5 -> "$" + f.fare();
                default -> "—";
            };
        }
    }

    private static boolean tableExists(Connection conn, String tableName) {
        try (ResultSet rs = conn.getMetaData().getTables(conn.getCatalog(), null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }
}
