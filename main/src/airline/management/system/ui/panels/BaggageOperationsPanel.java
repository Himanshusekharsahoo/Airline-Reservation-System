package airline.management.system.ui.panels;

import airline.management.system.operations.baggage.BaggageEventRecord;
import airline.management.system.operations.baggage.BaggageLifecycleValidator;
import airline.management.system.operations.baggage.BaggageRecord;
import airline.management.system.operations.baggage.BaggageService;
import airline.management.system.operations.baggage.BaggageStatus;
import airline.management.system.operations.baggage.BaggageType;
import airline.management.system.operations.pir.PIRRecord;
import airline.management.system.operations.pir.PIRService;
import airline.management.system.operations.pir.PIRType;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.ui.components.AeroScrollPane;
import airline.management.system.ui.components.DashboardCard;
import airline.management.system.ui.components.ModernButton;
import airline.management.system.ui.components.ModernComboBox;
import airline.management.system.ui.components.ModernTextField;
import airline.management.system.ui.components.NotificationBanner;
import airline.management.system.ui.components.StatusBadge;
import airline.management.system.ui.components.StyledTable;
import airline.management.system.ui.navigation.NavRoute;
import airline.management.system.ui.navigation.NavigationController;
import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;
import airline.management.system.ui.layout.OperationalStation;
import airline.management.system.ui.layout.StationContext;

/**
 * AeroDesk Airline Staff Operations — Baggage Operations Console.
 * Enables airport check-in desks, ramp dispatch, and baggage handlers to register baggage,
 * track luggage lifecycle transitions, record handling events, and manage operational exceptions.
 */
public class BaggageOperationsPanel extends JPanel {
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NavigationController navigationController;
    private final NotificationBanner notificationBanner;

    // KPI Summary Cards
    private final DashboardCard totalBaggageCard;
    private final DashboardCard activeBaggageCard;
    private final DashboardCard deliveredBaggageCard;
    private final DashboardCard exceptionBaggageCard;

    // Controls
    private final ModernTextField searchField;
    private final ModernComboBox<String> statusFilter;
    private final ModernButton refreshBtn;
    private final ModernButton registerBaggageBtn;
    private final JLabel recordCountLabel;
    private final JLabel selectedUnitLabel;

    // Table & Model
    private final StyledTable baggageTable;
    private final BaggageTableModel baggageTableModel;
    private final List<BaggageRecord> baggageList = new ArrayList<>();
    private final CardLayout tableCardLayout = new CardLayout();
    private final JPanel tableContainer = new JPanel(tableCardLayout);

    // Selected Baggage Context
    private BaggageRecord selectedBaggage = null;
    private final ModernButton viewDetailsBtn;
    private final ModernButton updateStatusBtn;
    private final ModernButton markDelayedBtn;
    private final ModernButton markMissingBtn;
    private final ModernButton pirBtn;

    // Station Context Listener
    private final Consumer<OperationalStation> stationListener;

    public BaggageOperationsPanel(NavigationController controller) {
        this.navigationController = controller;
        setLayout(new BorderLayout());
        setBackground(AeroColors.BACKGROUND);

        JPanel mainContent = new JPanel();
        mainContent.setLayout(new BoxLayout(mainContent, BoxLayout.Y_AXIS));
        mainContent.setBackground(AeroColors.BACKGROUND);
        mainContent.setBorder(new EmptyBorder(24, 28, 28, 28));

        // 1. Header
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        JPanel titleBlock = new JPanel();
        titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
        titleBlock.setOpaque(false);

        JLabel title = new JLabel("Baggage Operations Console");
        title.setFont(AeroFonts.TITLE_PAGE);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel subtitle = new JLabel("Track passenger luggage, register checked bags, dispatch cargo status, and resolve handling exceptions.");
        subtitle.setFont(AeroFonts.BODY);
        subtitle.setForeground(AeroColors.TEXT_SECONDARY);

        titleBlock.add(title);
        titleBlock.add(Box.createVerticalStrut(2));
        titleBlock.add(subtitle);
        header.add(titleBlock, BorderLayout.WEST);

        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        headerActions.setOpaque(false);

        registerBaggageBtn = new ModernButton("+ Register Baggage", ModernButton.Style.PRIMARY);
        registerBaggageBtn.setPreferredSize(new Dimension(165, 36));

        refreshBtn = new ModernButton("↻ Refresh", ModernButton.Style.SECONDARY);
        refreshBtn.setPreferredSize(new Dimension(96, 36));

        headerActions.add(registerBaggageBtn);
        headerActions.add(refreshBtn);
        header.add(headerActions, BorderLayout.EAST);

        mainContent.add(header);
        mainContent.add(Box.createVerticalStrut(16));

        notificationBanner = new NotificationBanner();
        mainContent.add(notificationBanner);
        mainContent.add(Box.createVerticalStrut(14));

        // 2. KPI Cards Container
        JPanel kpiContainer = new JPanel(new GridLayout(1, 4, 16, 0));
        kpiContainer.setOpaque(false);
        kpiContainer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 134));
        kpiContainer.setPreferredSize(new Dimension(Integer.MAX_VALUE, 128));

        totalBaggageCard = new DashboardCard("TOTAL REGISTERED", "0", "Tenant Scoped");
        activeBaggageCard = new DashboardCard("ACCEPTED / LOADED", "0", "In Processing");
        deliveredBaggageCard = new DashboardCard("DELIVERED", "0", "Claimed at Carousel");
        exceptionBaggageCard = new DashboardCard("EXCEPTIONS", "0", "Delayed / Missing");

        kpiContainer.add(totalBaggageCard);
        kpiContainer.add(activeBaggageCard);
        kpiContainer.add(deliveredBaggageCard);
        kpiContainer.add(exceptionBaggageCard);

        mainContent.add(kpiContainer);
        mainContent.add(Box.createVerticalStrut(18));

        // 3. Search and Filter Bar Card
        JPanel filterCard = new JPanel(new BorderLayout(14, 0));
        filterCard.setBackground(Color.WHITE);
        filterCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(12, 16, 12, 16)
        ));
        filterCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));

        searchField = new ModernTextField(18);
        searchField.setPlaceholder("Search Tag #, PNR, Passenger, Flight...");
        searchField.setPreferredSize(new Dimension(340, AeroConstants.INPUT_HEIGHT));

        JPanel filterControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        filterControls.setOpaque(false);

        statusFilter = new ModernComboBox<>(new String[]{
            "All Statuses", "REGISTERED", "ACCEPTED", "LOADED", "IN_TRANSIT", "DELIVERED", "DELAYED", "MISSING", "CANCELLED"
        });
        statusFilter.setPreferredSize(new Dimension(160, AeroConstants.INPUT_HEIGHT));

        ModernButton searchBtn = new ModernButton("Search", ModernButton.Style.SECONDARY);
        searchBtn.setPreferredSize(new Dimension(84, AeroConstants.BUTTON_HEIGHT));
        searchBtn.addActionListener(e -> loadBaggageAsync());

        filterControls.add(new JLabel("Status:"));
        filterControls.add(statusFilter);
        filterControls.add(searchBtn);

        filterCard.add(searchField, BorderLayout.CENTER);
        filterCard.add(filterControls, BorderLayout.EAST);
        mainContent.add(filterCard);
        mainContent.add(Box.createVerticalStrut(14));

        // 4. Table Card with Top Action Bar
        JPanel tableCard = new JPanel(new BorderLayout(0, 10));
        tableCard.setBackground(Color.WHITE);
        tableCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(16, 18, 16, 18)
        ));

        JPanel tableHeaderBar = new JPanel(new BorderLayout());
        tableHeaderBar.setOpaque(false);

        JPanel leftCount = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftCount.setOpaque(false);

        JLabel tblTitle = new JLabel("Luggage Tracking Units");
        tblTitle.setFont(AeroFonts.TITLE_CARD);
        tblTitle.setForeground(AeroColors.TEXT_MAIN);

        recordCountLabel = new JLabel("0 luggage units");
        recordCountLabel.setFont(AeroFonts.CAPTION);
        recordCountLabel.setForeground(AeroColors.TEXT_MUTED);

        leftCount.add(tblTitle);
        leftCount.add(new JLabel("•"));
        leftCount.add(recordCountLabel);
        tableHeaderBar.add(leftCount, BorderLayout.WEST);

        JPanel actionGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actionGroup.setOpaque(false);

        viewDetailsBtn = new ModernButton("View History & Details", ModernButton.Style.SECONDARY);
        viewDetailsBtn.setPreferredSize(new Dimension(175, 32));
        viewDetailsBtn.setFont(AeroFonts.CAPTION_BOLD);
        viewDetailsBtn.setEnabled(false);

        updateStatusBtn = new ModernButton("Update Status", ModernButton.Style.PRIMARY);
        updateStatusBtn.setPreferredSize(new Dimension(120, 32));
        updateStatusBtn.setFont(AeroFonts.CAPTION_BOLD);
        updateStatusBtn.setEnabled(false);

        markDelayedBtn = new ModernButton("Mark Delayed", ModernButton.Style.SECONDARY);
        markDelayedBtn.setPreferredSize(new Dimension(115, 32));
        markDelayedBtn.setFont(AeroFonts.CAPTION_BOLD);
        markDelayedBtn.setEnabled(false);

        markMissingBtn = new ModernButton("Mark Missing", ModernButton.Style.DANGER);
        markMissingBtn.setPreferredSize(new Dimension(115, 32));
        markMissingBtn.setFont(AeroFonts.CAPTION_BOLD);
        markMissingBtn.setEnabled(false);

        pirBtn = new ModernButton("PIR / Incident", ModernButton.Style.SECONDARY);
        pirBtn.setPreferredSize(new Dimension(125, 32));
        pirBtn.setFont(AeroFonts.CAPTION_BOLD);
        pirBtn.setEnabled(false);

        actionGroup.add(viewDetailsBtn);
        actionGroup.add(updateStatusBtn);
        actionGroup.add(markDelayedBtn);
        actionGroup.add(markMissingBtn);
        actionGroup.add(pirBtn);

        tableCard.add(tableHeaderBar, BorderLayout.NORTH);

        baggageTableModel = new BaggageTableModel();
        baggageTable = new StyledTable(baggageTableModel);
        baggageTable.getColumnModel().getColumn(7).setCellRenderer(StatusBadge.createTableCellRenderer());

        JScrollPane scrollPane = new JScrollPane(baggageTable);
        scrollPane.setBorder(BorderFactory.createLineBorder(AeroColors.BORDER, 1));
        scrollPane.getViewport().setBackground(Color.WHITE);

        JPanel emptyPanel = new JPanel(new BorderLayout());
        emptyPanel.setBackground(Color.WHITE);
        emptyPanel.setBorder(BorderFactory.createLineBorder(AeroColors.BORDER, 1));
        JLabel emptyLabel = new JLabel("No baggage units found matching query.", SwingConstants.CENTER);
        emptyLabel.setFont(AeroFonts.BODY);
        emptyLabel.setForeground(AeroColors.TEXT_MUTED);
        emptyPanel.add(emptyLabel, BorderLayout.CENTER);

        tableContainer.setOpaque(false);
        tableContainer.add(scrollPane, "table");
        tableContainer.add(emptyPanel, "empty");
        tableCardLayout.show(tableContainer, "empty");

        tableCard.add(tableContainer, BorderLayout.CENTER);

        JPanel tableBottomBar = new JPanel(new BorderLayout(12, 0));
        tableBottomBar.setOpaque(false);
        tableBottomBar.setBorder(new EmptyBorder(12, 0, 0, 0));

        selectedUnitLabel = new JLabel("Select a baggage unit to perform operations");
        selectedUnitLabel.setFont(AeroFonts.CAPTION);
        selectedUnitLabel.setForeground(AeroColors.TEXT_MUTED);

        tableBottomBar.add(selectedUnitLabel, BorderLayout.WEST);
        tableBottomBar.add(actionGroup, BorderLayout.EAST);
        tableCard.add(tableBottomBar, BorderLayout.SOUTH);

        mainContent.add(tableCard);

        add(new AeroScrollPane(mainContent), BorderLayout.CENTER);

        // Listeners
        refreshBtn.addActionListener(e -> loadBaggageAsync());
        statusFilter.addActionListener(e -> loadBaggageAsync());
        searchField.addActionListener(e -> loadBaggageAsync());

        baggageTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = baggageTable.getSelectedRow();
                if (row >= 0 && row < baggageList.size()) {
                    selectedBaggage = baggageList.get(row);
                    enableActions(true);
                } else {
                    selectedBaggage = null;
                    enableActions(false);
                }
            }
        });

        baggageTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && selectedBaggage != null) {
                    showDetailsModal(selectedBaggage);
                }
            }
        });

        viewDetailsBtn.addActionListener(e -> {
            if (selectedBaggage != null) showDetailsModal(selectedBaggage);
        });

        updateStatusBtn.addActionListener(e -> {
            if (selectedBaggage != null) showStatusUpdateModal(selectedBaggage);
        });

        markDelayedBtn.addActionListener(e -> {
            if (selectedBaggage != null) promptQuickException(selectedBaggage, BaggageStatus.DELAYED);
        });

        markMissingBtn.addActionListener(e -> {
            if (selectedBaggage != null) promptQuickException(selectedBaggage, BaggageStatus.MISSING);
        });

        pirBtn.addActionListener(e -> {
            if (selectedBaggage == null) return;
            SwingWorker<Optional<PIRRecord>, Void> w = new SwingWorker<>() {
                @Override
                protected Optional<PIRRecord> doInBackground() throws Exception {
                    return PIRService.getInstance().findActiveByBaggageId(selectedBaggage.id());
                }
                @Override
                protected void done() {
                    try {
                        Optional<PIRRecord> activePir = get();
                        if (activePir.isPresent()) {
                            int opt = JOptionPane.showConfirmDialog(
                                BaggageOperationsPanel.this,
                                "Active PIR [" + activePir.get().pirReference() + "] exists for this baggage.\n" +
                                "Incident: " + activePir.get().type().getDisplayName() + " | Status: " + activePir.get().status().name() + "\n" +
                                "Open the PIR & Claims console now?",
                                "PIR Incident Found",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.INFORMATION_MESSAGE
                            );
                            if (opt == JOptionPane.YES_OPTION && navigationController != null) {
                                navigationController.navigateTo(NavRoute.PIR_CLAIMS, activePir.get().pirReference());
                            }
                        } else {
                            int opt = JOptionPane.showConfirmDialog(
                                BaggageOperationsPanel.this,
                                "No active Property Irregularity Report exists for baggage tag [" + selectedBaggage.tagNumber() + "].\n" +
                                "Current Status: " + selectedBaggage.status().name() + "\n" +
                                "Open PIR console to file a new incident report?",
                                "File PIR Incident",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.QUESTION_MESSAGE
                            );
                            if (opt == JOptionPane.YES_OPTION) createPirForSelectedBaggage(selectedBaggage);
                        }
                    } catch (Exception ex) {
                        if (navigationController != null) {
                            navigationController.navigateTo(NavRoute.PIR_CLAIMS, selectedBaggage.tagNumber());
                        }
                    }
                }
            };
            w.execute();
        });

        registerBaggageBtn.addActionListener(e -> showRegistrationModal());

        // Enforce RBAC
        boolean canManage = AuthorizationService.hasPermission(Permission.MANAGE_BAGGAGE);
        registerBaggageBtn.setVisible(canManage);
        updateStatusBtn.setVisible(canManage);
        markDelayedBtn.setVisible(canManage);
        markMissingBtn.setVisible(canManage);

        // Navigation listener for deep linking (e.g. from TripsPanel or CheckinPanel)
        if (navigationController != null) {
            navigationController.addListener((route, param) -> {
                if (route == NavRoute.BAGGAGE) {
                    if (param instanceof String s && !s.isBlank()) {
                        searchField.setText(s.trim());
                    }
                    loadBaggageAsync();
                }
            });
        }

        // Station context change listener
        stationListener = st -> loadBaggageAsync();
        StationContext.addListener(stationListener);

        loadBaggageAsync();
    }

    private void createPirForSelectedBaggage(BaggageRecord bag) {
        PIRType type = bag.status() == BaggageStatus.MISSING ? PIRType.MISSING_BAGGAGE : PIRType.DELAYED_BAGGAGE;
        OperationalStation activeSt = StationContext.getCurrentStation();
        String pirStation = activeSt != null ? activeSt.getCode() : "DEL";
        new SwingWorker<PIRRecord, Void>() {
            @Override protected PIRRecord doInBackground() throws Exception {
                return PIRService.getInstance().createPIR(bag.id(), type, pirStation, "PIR filed from baggage operations for " + bag.status().name().toLowerCase() + " baggage.");
            }
            @Override protected void done() {
                try {
                    PIRRecord pir = get();
                    notificationBanner.showMessage("PIR [" + pir.pirReference() + "] created for baggage [" + bag.tagNumber() + "].", NotificationBanner.Type.SUCCESS);
                    loadBaggageAsync();
                    if (navigationController != null) navigationController.navigateTo(NavRoute.PIR_CLAIMS, pir.pirReference());
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    notificationBanner.showMessage(cause.getMessage() == null ? "PIR could not be created." : cause.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        }.execute();
    }

    private void enableActions(boolean enabled) {
        viewDetailsBtn.setEnabled(enabled);
        boolean canManage = AuthorizationService.hasPermission(Permission.MANAGE_BAGGAGE);
        updateStatusBtn.setEnabled(enabled && canManage && selectedBaggage != null && !selectedBaggage.isTerminal());
        markDelayedBtn.setEnabled(enabled && canManage && selectedBaggage != null && !selectedBaggage.isTerminal());
        markMissingBtn.setEnabled(enabled && canManage && selectedBaggage != null && !selectedBaggage.isTerminal());
        boolean isIrregularity = selectedBaggage != null && (selectedBaggage.status() == BaggageStatus.DELAYED || selectedBaggage.status() == BaggageStatus.MISSING);
        pirBtn.setEnabled(enabled && isIrregularity && AuthorizationService.hasPermission(Permission.MANAGE_PIR));

        if (enabled && selectedBaggage != null) {
            selectedUnitLabel.setText("Selected Luggage: Tag " + selectedBaggage.tagNumber() + " • Passenger: " + selectedBaggage.passengerName() + " • Status: " + selectedBaggage.status());
            selectedUnitLabel.setForeground(AeroColors.TEXT_MAIN);
        } else {
            selectedUnitLabel.setText("Select a baggage unit to perform operations");
            selectedUnitLabel.setForeground(AeroColors.TEXT_MUTED);
        }
    }

    private void loadBaggageAsync() {
        refreshBtn.setEnabled(false);
        String query = searchField.getText().trim();
        String selectedStatusStr = (String) statusFilter.getSelectedItem();
        BaggageStatus statusReq = (selectedStatusStr != null && !selectedStatusStr.equals("All Statuses"))
                ? BaggageStatus.fromString(selectedStatusStr) : null;

        OperationalStation activeSt = StationContext.getCurrentStation();
        String stationCode = activeSt != null ? activeSt.getCode() : "DEL";

        SwingWorker<BaggageLoadResult, Void> worker = new SwingWorker<>() {
            @Override
            protected BaggageLoadResult doInBackground() throws Exception {
                BaggageService service = BaggageService.getInstance();
                List<BaggageRecord> records = service.searchBaggage(query, statusReq, null, stationCode);
                BaggageService.BaggageSummary summary = service.getBaggageSummary(null, stationCode);
                return new BaggageLoadResult(records, summary);
            }

            @Override
            protected void done() {
                refreshBtn.setEnabled(true);
                try {
                    BaggageLoadResult res = get();
                    baggageList.clear();
                    baggageList.addAll(res.records());
                    baggageTableModel.fireTableDataChanged();

                    recordCountLabel.setText(baggageList.size() + " baggage unit" + (baggageList.size() == 1 ? "" : "s"));
                    if (baggageList.isEmpty()) {
                        tableCardLayout.show(tableContainer, "empty");
                    } else {
                        tableCardLayout.show(tableContainer, "table");
                    }

                    // Update KPIs
                    BaggageService.BaggageSummary s = res.summary();
                    totalBaggageCard.setValue(String.valueOf(s.totalRegistered()));
                    totalBaggageCard.setSubtitle("Station " + stationCode + " Scoped");
                    activeBaggageCard.setValue(String.valueOf(s.accepted() + s.loaded()));
                    deliveredBaggageCard.setValue(String.valueOf(s.delivered()));
                    exceptionBaggageCard.setValue(String.valueOf(s.delayed() + s.missing()));

                    enableActions(false);
                } catch (Exception ex) {
                    notificationBanner.showMessage("Error loading baggage data: " + ex.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    private void showRegistrationModal() {
        Component parent = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parent instanceof java.awt.Frame ? (java.awt.Frame) parent : null, "Register Checked Baggage", true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(480, 420);
        dialog.setLocationRelativeTo(parent);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(20, 24, 20, 24));
        content.setBackground(AeroColors.BACKGROUND);

        JLabel desc = new JLabel("<html>Register checked baggage for a confirmed, checked-in passenger journey.</html>");
        desc.setFont(AeroFonts.BODY);
        desc.setForeground(AeroColors.TEXT_SECONDARY);
        content.add(desc);
        content.add(Box.createVerticalStrut(14));

        ModernTextField pnrField = new ModernTextField(15);
        pnrField.setPlaceholder("Enter Passenger PNR (e.g. 11, PNR12345)");

        ModernTextField weightField = new ModernTextField(10);
        weightField.setPlaceholder("Weight in KG (e.g. 18.50)");

        ModernComboBox<BaggageType> typeCombo = new ModernComboBox<>(BaggageType.values());

        ModernTextField remarksField = new ModernTextField(20);
        remarksField.setPlaceholder("Optional remarks / handling instructions");

        content.add(createFieldRow("Reservation PNR:", pnrField));
        content.add(Box.createVerticalStrut(10));
        content.add(createFieldRow("Baggage Weight (KG):", weightField));
        content.add(Box.createVerticalStrut(10));
        content.add(createFieldRow("Baggage Type:", typeCombo));
        content.add(Box.createVerticalStrut(10));
        content.add(createFieldRow("Remarks / Tags:", remarksField));
        content.add(Box.createVerticalStrut(16));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnRow.setOpaque(false);
        ModernButton cancelBtn = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        ModernButton submitBtn = new ModernButton("Register & Accept", ModernButton.Style.PRIMARY);

        cancelBtn.addActionListener(e -> dialog.dispose());
        submitBtn.addActionListener(e -> {
            String pnr = pnrField.getText().trim();
            String weightStr = weightField.getText().trim();
            if (pnr.isEmpty() || weightStr.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "PNR and Weight are required.", "Validation Error", JOptionPane.WARNING_MESSAGE);
                return;
            }

            BigDecimal weight;
            try {
                weight = new BigDecimal(weightStr);
                if (weight.compareTo(BigDecimal.ZERO) <= 0 || weight.compareTo(new BigDecimal("70.00")) > 0) {
                    JOptionPane.showMessageDialog(dialog, "Weight must be between 0.1 and 70.00 kg.", "Validation Error", JOptionPane.WARNING_MESSAGE);
                    return;
                }
            } catch (NumberFormatException nfe) {
                JOptionPane.showMessageDialog(dialog, "Invalid weight format.", "Validation Error", JOptionPane.WARNING_MESSAGE);
                return;
            }

            submitBtn.setEnabled(false);
            SwingWorker<BaggageRecord, Void> regWorker = new SwingWorker<>() {
                @Override
                protected BaggageRecord doInBackground() throws Exception {
                    return BaggageService.getInstance().registerBaggage(
                        pnr, weight, (BaggageType) typeCombo.getSelectedItem(), remarksField.getText().trim()
                    );
                }

                @Override
                protected void done() {
                    try {
                        BaggageRecord rec = get();
                        dialog.dispose();
                        notificationBanner.showMessage("Baggage [" + rec.tagNumber() + "] successfully registered (" + rec.weightKg() + " kg).", NotificationBanner.Type.SUCCESS);
                        loadBaggageAsync();
                    } catch (Exception ex) {
                        submitBtn.setEnabled(true);
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        JOptionPane.showMessageDialog(dialog, cause.getMessage(), "Registration Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            };
            regWorker.execute();
        });

        btnRow.add(cancelBtn);
        btnRow.add(submitBtn);
        content.add(btnRow);

        dialog.add(content, BorderLayout.CENTER);
        dialog.setVisible(true);
    }

    private void showStatusUpdateModal(BaggageRecord bag) {
        Component parent = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parent instanceof java.awt.Frame ? (java.awt.Frame) parent : null, "Update Baggage Status — " + bag.tagNumber(), true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(460, 360);
        dialog.setLocationRelativeTo(parent);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(20, 24, 20, 24));
        content.setBackground(AeroColors.BACKGROUND);

        Set<BaggageStatus> available = BaggageLifecycleValidator.getAvailableNextStates(bag.status());
        if (available.isEmpty()) {
            JOptionPane.showMessageDialog(dialog, "Baggage is in terminal status [" + bag.status() + "]. No further transitions permitted.", "Notice", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JLabel curLabel = new JLabel("Current Status: " + bag.status().name() + " (" + bag.status().getDisplayName() + ")");
        curLabel.setFont(AeroFonts.BODY_BOLD);
        curLabel.setForeground(AeroColors.TEXT_MAIN);
        content.add(curLabel);
        content.add(Box.createVerticalStrut(14));

        ModernComboBox<BaggageStatus> nextCombo = new ModernComboBox<>(available.toArray(new BaggageStatus[0]));
        OperationalStation activeSt = StationContext.getCurrentStation();
        String curStation = activeSt != null ? activeSt.getCode() : "DEL";
        ModernTextField locationField = new ModernTextField(curStation, 15);
        locationField.setPlaceholder("Location Code (e.g. " + curStation + ", RAMP_4, CAROUSEL)");
        ModernTextField remarksField = new ModernTextField(20);
        remarksField.setPlaceholder("Operational remark / dispatch notes");

        content.add(createFieldRow("New Status:", nextCombo));
        content.add(Box.createVerticalStrut(10));
        content.add(createFieldRow("Location Code:", locationField));
        content.add(Box.createVerticalStrut(10));
        content.add(createFieldRow("Remarks:", remarksField));
        content.add(Box.createVerticalStrut(18));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnRow.setOpaque(false);
        ModernButton cancelBtn = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        ModernButton updateBtn = new ModernButton("Commit Transition", ModernButton.Style.PRIMARY);

        cancelBtn.addActionListener(e -> dialog.dispose());
        updateBtn.addActionListener(e -> {
            BaggageStatus selected = (BaggageStatus) nextCombo.getSelectedItem();
            if (selected == null) return;

            updateBtn.setEnabled(false);
            SwingWorker<BaggageRecord, Void> worker = new SwingWorker<>() {
                @Override
                protected BaggageRecord doInBackground() throws Exception {
                    return BaggageService.getInstance().updateBaggageStatus(
                        bag.id(), selected, locationField.getText().trim(), remarksField.getText().trim()
                    );
                }

                @Override
                protected void done() {
                    try {
                        BaggageRecord res = get();
                        dialog.dispose();
                        notificationBanner.showMessage("Baggage [" + res.tagNumber() + "] status updated to " + res.status() + ".", NotificationBanner.Type.SUCCESS);
                        loadBaggageAsync();
                    } catch (Exception ex) {
                        updateBtn.setEnabled(true);
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        JOptionPane.showMessageDialog(dialog, cause.getMessage(), "Status Update Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            };
            worker.execute();
        });

        btnRow.add(cancelBtn);
        btnRow.add(updateBtn);
        content.add(btnRow);

        dialog.add(content, BorderLayout.CENTER);
        dialog.setVisible(true);
    }

    private void promptQuickException(BaggageRecord bag, BaggageStatus targetException) {
        String reason = JOptionPane.showInputDialog(
            this,
            "Enter operational reason for marking baggage [" + bag.tagNumber() + "] as " + targetException.name() + ":",
            "Mark " + targetException.getDisplayName(),
            JOptionPane.WARNING_MESSAGE
        );
        if (reason == null || reason.isBlank()) {
            return;
        }

        SwingWorker<BaggageRecord, Void> worker = new SwingWorker<>() {
            @Override
            protected BaggageRecord doInBackground() throws Exception {
                return BaggageService.getInstance().updateBaggageStatus(bag.id(), targetException, "EXCEPTION_DESK", reason.trim());
            }

            @Override
            protected void done() {
                try {
                    BaggageRecord res = get();
                    notificationBanner.showMessage("Baggage [" + res.tagNumber() + "] recorded as " + res.status() + ".", NotificationBanner.Type.WARNING);
                    loadBaggageAsync();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    notificationBanner.showMessage("Exception recording failed: " + cause.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    private void showDetailsModal(BaggageRecord bag) {
        Component parent = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parent instanceof java.awt.Frame ? (java.awt.Frame) parent : null, "Baggage Details & Audit History — " + bag.tagNumber(), true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(640, 520);
        dialog.setLocationRelativeTo(parent);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(20, 24, 20, 24));
        content.setBackground(AeroColors.BACKGROUND);

        // Info Grid
        JPanel infoCard = new JPanel(new BorderLayout());
        infoCard.setBackground(Color.WHITE);
        infoCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(14, 16, 14, 16)
        ));

        JPanel grid = new JPanel(new GridLayout(4, 2, 10, 6));
        grid.setOpaque(false);
        addDetailCell(grid, "Tag Number:", bag.tagNumber());
        addDetailCell(grid, "Passenger:", bag.passengerName());
        addDetailCell(grid, "PNR Reference:", bag.pnr());
        addDetailCell(grid, "Flight & Route:", bag.flightNumber() + " (" + bag.route() + ")");
        addDetailCell(grid, "Seat Number:", bag.seatNumber());
        addDetailCell(grid, "Weight & Type:", bag.weightKg() + " kg (" + bag.type() + ")");
        addDetailCell(grid, "Status:", bag.status().name());
        addDetailCell(grid, "Registered At:", bag.createdAt().format(DISPLAY_FORMAT));

        infoCard.add(grid, BorderLayout.CENTER);
        content.add(infoCard);
        content.add(Box.createVerticalStrut(14));

        // Event History
        JLabel histTitle = new JLabel("Tracking Events & Operational Trail:");
        histTitle.setFont(AeroFonts.BODY_BOLD);
        content.add(histTitle);
        content.add(Box.createVerticalStrut(8));

        DefaultTableModel eventModel = new DefaultTableModel(new String[]{"Event", "Location", "Logged By", "Remarks", "Timestamp"}, 0);
        StyledTable eventTable = new StyledTable(eventModel);
        JScrollPane eventScroll = new JScrollPane(eventTable);
        eventScroll.setPreferredSize(new Dimension(580, 180));
        content.add(eventScroll);
        content.add(Box.createVerticalStrut(14));

        SwingWorker<List<BaggageEventRecord>, Void> eventWorker = new SwingWorker<>() {
            @Override
            protected List<BaggageEventRecord> doInBackground() throws Exception {
                return BaggageService.getInstance().getBaggageHistory(bag.id());
            }

            @Override
            protected void done() {
                try {
                    List<BaggageEventRecord> events = get();
                    for (BaggageEventRecord ev : events) {
                        eventModel.addRow(new Object[]{
                            ev.eventType().getDisplayName(),
                            ev.locationCode(),
                            ev.scannedByUsername(),
                            ev.remarks(),
                            ev.scannedAt().format(DISPLAY_FORMAT)
                        });
                    }
                } catch (Exception ignored) {}
            }
        };
        eventWorker.execute();

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        btnRow.setOpaque(false);
        ModernButton closeBtn = new ModernButton("Close", ModernButton.Style.SECONDARY);
        closeBtn.addActionListener(e -> dialog.dispose());
        btnRow.add(closeBtn);
        content.add(btnRow);

        dialog.add(content, BorderLayout.CENTER);
        dialog.setVisible(true);
    }

    private JPanel createFieldRow(String label, Component field) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        JLabel lbl = new JLabel(label);
        lbl.setPreferredSize(new Dimension(150, 36));
        lbl.setFont(AeroFonts.BODY_BOLD);
        lbl.setForeground(AeroColors.TEXT_MAIN);
        row.add(lbl, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    private void addDetailCell(JPanel panel, String label, String value) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setFont(AeroFonts.CAPTION_BOLD);
        l.setForeground(AeroColors.TEXT_SECONDARY);
        JLabel v = new JLabel(value != null ? value : "—");
        v.setFont(AeroFonts.BODY);
        v.setForeground(AeroColors.TEXT_MAIN);
        p.add(l);
        p.add(v);
        panel.add(p);
    }

    private record BaggageLoadResult(List<BaggageRecord> records, BaggageService.BaggageSummary summary) {}

    // Table Model
    private class BaggageTableModel extends AbstractTableModel {
        private final String[] columns = {"Tag #", "PNR", "Passenger", "Flight", "Route", "Seat", "Weight", "Status", "Registered At"};

        @Override public int getRowCount() { return baggageList.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            if (row < 0 || row >= baggageList.size()) return null;
            BaggageRecord b = baggageList.get(row);
            return switch (col) {
                case 0 -> b.tagNumber();
                case 1 -> b.pnr();
                case 2 -> b.passengerName();
                case 3 -> b.flightNumber();
                case 4 -> b.route();
                case 5 -> b.seatNumber();
                case 6 -> b.weightKg() + " kg";
                case 7 -> b.status().name();
                case 8 -> b.createdAt().format(DISPLAY_FORMAT);
                default -> null;
            };
        }
    }

    public void unregisterStationListener() {
        StationContext.removeListener(stationListener);
    }
}
