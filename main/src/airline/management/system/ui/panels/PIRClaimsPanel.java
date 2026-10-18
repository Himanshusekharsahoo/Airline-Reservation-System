package airline.management.system.ui.panels;

import airline.management.system.UserSession;
import airline.management.system.operations.baggage.BaggageRecord;
import airline.management.system.operations.baggage.BaggageService;
import airline.management.system.operations.baggage.BaggageStatus;
import airline.management.system.operations.claim.ClaimCase;
import airline.management.system.operations.claim.ClaimEventRecord;
import airline.management.system.operations.claim.ClaimLifecycleValidator;
import airline.management.system.operations.claim.ClaimService;
import airline.management.system.operations.claim.ClaimSettlementRecord;
import airline.management.system.operations.claim.ClaimStatus;
import airline.management.system.operations.pir.PIRLifecycleValidator;
import airline.management.system.operations.pir.PIRRecord;
import airline.management.system.operations.pir.PIRService;
import airline.management.system.operations.pir.PIRStatus;
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
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * AeroDesk PIR & Claims Operations Console:
 * Property Irregularity Report management, baggage incident investigations,
 * passenger claim evaluations, and financial compensation settlements.
 */
public class PIRClaimsPanel extends JPanel {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    private final NavigationController navigationController;
    private final NotificationBanner notificationBanner;

    // KPI Cards
    private final DashboardCard openPirsCard;
    private final DashboardCard missingBaggageCard;
    private final DashboardCard claimsReviewCard;
    private final DashboardCard settlementsPaidCard;

    // Tabs
    private final JTabbedPane tabbedPane;

    // Tab 1: PIR Components
    private final ModernTextField pirSearchField;
    private final ModernComboBox<String> pirStatusFilter;
    private final ModernButton pirRefreshBtn;
    private final ModernButton createPirBtn;
    private final ModernButton viewPirBtn;
    private final ModernButton updatePirBtn;
    private final ModernButton fileClaimBtn;
    private final JLabel pirCountLabel;
    private final CardLayout pirCardLayout = new CardLayout();
    private final JPanel pirTableContainer = new JPanel(pirCardLayout);
    private final PIRTableModel pirTableModel = new PIRTableModel();
    private final StyledTable pirTable;
    private final List<PIRRecord> pirList = new ArrayList<>();
    private PIRRecord selectedPIR;

    // Tab 2: Claims Components
    private final ModernTextField claimSearchField;
    private final ModernComboBox<String> claimStatusFilter;
    private final ModernButton claimRefreshBtn;
    private final ModernButton viewClaimBtn;
    private final ModernButton reviewClaimBtn;
    private final ModernButton settleClaimBtn;
    private final JLabel claimCountLabel;
    private final CardLayout claimCardLayout = new CardLayout();
    private final JPanel claimTableContainer = new JPanel(claimCardLayout);
    private final ClaimTableModel claimTableModel = new ClaimTableModel();
    private final StyledTable claimTable;
    private final List<ClaimCase> claimList = new ArrayList<>();
    private ClaimCase selectedClaim;

    public PIRClaimsPanel(NavigationController controller) {
        this.navigationController = controller;
        setLayout(new BorderLayout());
        setBackground(AeroColors.BACKGROUND);

        JPanel mainContent = new JPanel();
        mainContent.setLayout(new BoxLayout(mainContent, BoxLayout.Y_AXIS));
        mainContent.setBackground(AeroColors.BACKGROUND);
        mainContent.setBorder(new EmptyBorder(24, 28, 28, 28));

        // 1. Header Banner
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);
        headerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));

        JPanel titleBlock = new JPanel();
        titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
        titleBlock.setOpaque(false);

        JLabel titleLabel = new JLabel("PIR & Claims Operations");
        titleLabel.setFont(AeroFonts.TITLE_PAGE);
        titleLabel.setForeground(AeroColors.TEXT_MAIN);

        JLabel subLabel = new JLabel("Property Irregularity Reports, luggage investigations, passenger claims, and settlements");
        subLabel.setFont(AeroFonts.BODY);
        subLabel.setForeground(AeroColors.TEXT_MUTED);

        titleBlock.add(titleLabel);
        titleBlock.add(Box.createVerticalStrut(2));
        titleBlock.add(subLabel);
        headerPanel.add(titleBlock, BorderLayout.WEST);
        mainContent.add(headerPanel);
        mainContent.add(Box.createVerticalStrut(14));

        // 2. Notification Banner
        notificationBanner = new NotificationBanner();
        mainContent.add(notificationBanner);
        mainContent.add(Box.createVerticalStrut(14));

        // 3. KPI Cards Row
        JPanel kpiPanel = new JPanel(new GridLayout(1, 4, 16, 0));
        kpiPanel.setOpaque(false);
        kpiPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));

        openPirsCard = new DashboardCard("OPEN PIRS", "0", "Awaiting investigation", "claim", AeroColors.WARNING_BG);
        missingBaggageCard = new DashboardCard("MISSING LUGGAGE", "0", "Unresolved luggage trace", "baggage", AeroColors.DANGER_BG);
        claimsReviewCard = new DashboardCard("CLAIMS IN REVIEW", "0", "Submitted & under review", "claim", AeroColors.INFO_BG);
        settlementsPaidCard = new DashboardCard("SETTLED CLAIMS", "0", "Paid compensation cases", "payment", AeroColors.SUCCESS_BG);

        kpiPanel.add(openPirsCard);
        kpiPanel.add(missingBaggageCard);
        kpiPanel.add(claimsReviewCard);
        kpiPanel.add(settlementsPaidCard);
        mainContent.add(kpiPanel);
        mainContent.add(Box.createVerticalStrut(16));

        // 4. Tabbed Pane
        tabbedPane = new JTabbedPane();
        tabbedPane.setFont(AeroFonts.BODY_BOLD);
        tabbedPane.setBackground(Color.WHITE);

        // --- TAB 1: PIR PANEL ---
        JPanel pirTab = new JPanel(new BorderLayout(0, 12));
        pirTab.setOpaque(false);
        pirTab.setBorder(new EmptyBorder(12, 4, 4, 4));

        // Filter bar for PIR
        JPanel pirFilterBar = new JPanel(new BorderLayout(14, 0));
        pirFilterBar.setOpaque(false);
        pirFilterBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));

        JPanel pirLeftFilters = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        pirLeftFilters.setOpaque(false);

        pirSearchField = new ModernTextField();
        pirSearchField.setPlaceholder("Search PIR #, Tag #, PNR, Passenger, Flight...");
        pirSearchField.setPreferredSize(new Dimension(280, 36));

        pirStatusFilter = new ModernComboBox<>(new String[]{"All Statuses", "OPEN", "UNDER_INVESTIGATION", "LOCATED", "DELIVERED", "UNRESOLVED", "CLOSED"});
        pirStatusFilter.setPreferredSize(new Dimension(170, 36));

        pirRefreshBtn = new ModernButton("↻ Refresh", ModernButton.Style.SECONDARY);
        pirRefreshBtn.setPreferredSize(new Dimension(100, 36));

        pirLeftFilters.add(pirSearchField);
        pirLeftFilters.add(pirStatusFilter);
        pirLeftFilters.add(pirRefreshBtn);

        JPanel pirRightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        pirRightActions.setOpaque(false);
        pirCountLabel = new JLabel("0 reports");
        pirCountLabel.setFont(AeroFonts.MUTED);
        pirCountLabel.setForeground(AeroColors.TEXT_SECONDARY);

        createPirBtn = new ModernButton("+ File PIR", ModernButton.Style.PRIMARY);
        createPirBtn.setPreferredSize(new Dimension(130, 36));
        createPirBtn.setEnabled(AuthorizationService.hasPermission(Permission.MANAGE_PIR));

        pirRightActions.add(pirCountLabel);
        pirRightActions.add(createPirBtn);

        pirFilterBar.add(pirLeftFilters, BorderLayout.WEST);
        pirFilterBar.add(pirRightActions, BorderLayout.EAST);
        pirTab.add(pirFilterBar, BorderLayout.NORTH);

        // Table for PIR
        pirTable = new StyledTable(pirTableModel);
        pirTable.getColumnModel().getColumn(3).setCellRenderer(new StatusBadgeRenderer());
        JScrollPane pirScroll = new JScrollPane(pirTable);
        pirScroll.setBorder(BorderFactory.createLineBorder(AeroColors.BORDER, 1));
        pirScroll.getViewport().setBackground(Color.WHITE);

        JPanel pirEmpty = new JPanel(new BorderLayout());
        pirEmpty.setBackground(Color.WHITE);
        pirEmpty.setBorder(BorderFactory.createLineBorder(AeroColors.BORDER, 1));
        JLabel pirEmptyLbl = new JLabel("No Property Irregularity Reports matching query.", SwingConstants.CENTER);
        pirEmptyLbl.setFont(AeroFonts.BODY);
        pirEmptyLbl.setForeground(AeroColors.TEXT_MUTED);
        pirEmpty.add(pirEmptyLbl, BorderLayout.CENTER);

        pirTableContainer.add(pirScroll, "table");
        pirTableContainer.add(pirEmpty, "empty");
        pirCardLayout.show(pirTableContainer, "empty");
        pirTab.add(pirTableContainer, BorderLayout.CENTER);

        // Bottom Action Bar for PIR
        JPanel pirBottomBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        pirBottomBar.setOpaque(false);
        pirBottomBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));

        viewPirBtn = new ModernButton("View Incident & History", ModernButton.Style.SECONDARY);
        viewPirBtn.setPreferredSize(new Dimension(185, 36));
        viewPirBtn.setEnabled(false);

        updatePirBtn = new ModernButton("Update Status & Notes", ModernButton.Style.SECONDARY);
        updatePirBtn.setPreferredSize(new Dimension(175, 36));
        updatePirBtn.setEnabled(false);

        fileClaimBtn = new ModernButton("File Passenger Claim →", ModernButton.Style.PRIMARY);
        fileClaimBtn.setPreferredSize(new Dimension(185, 36));
        fileClaimBtn.setEnabled(false);

        pirBottomBar.add(viewPirBtn);
        pirBottomBar.add(updatePirBtn);
        pirBottomBar.add(fileClaimBtn);
        pirTab.add(pirBottomBar, BorderLayout.SOUTH);

        tabbedPane.addTab("Property Irregularity Reports (PIR)", pirTab);

        // --- TAB 2: CLAIMS PANEL ---
        JPanel claimTab = new JPanel(new BorderLayout(0, 12));
        claimTab.setOpaque(false);
        claimTab.setBorder(new EmptyBorder(12, 4, 4, 4));

        // Filter bar for Claims
        JPanel claimFilterBar = new JPanel(new BorderLayout(14, 0));
        claimFilterBar.setOpaque(false);
        claimFilterBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));

        JPanel claimLeftFilters = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        claimLeftFilters.setOpaque(false);

        claimSearchField = new ModernTextField();
        claimSearchField.setPlaceholder("Search Claim #, PIR #, Tag #, Passenger, PNR...");
        claimSearchField.setPreferredSize(new Dimension(280, 36));

        claimStatusFilter = new ModernComboBox<>(new String[]{"All Statuses", "SUBMITTED", "UNDER_REVIEW", "APPROVED", "REJECTED", "SETTLED", "CLOSED"});
        claimStatusFilter.setPreferredSize(new Dimension(170, 36));

        claimRefreshBtn = new ModernButton("↻ Refresh", ModernButton.Style.SECONDARY);
        claimRefreshBtn.setPreferredSize(new Dimension(100, 36));

        claimLeftFilters.add(claimSearchField);
        claimLeftFilters.add(claimStatusFilter);
        claimLeftFilters.add(claimRefreshBtn);

        JPanel claimRightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        claimRightActions.setOpaque(false);
        claimCountLabel = new JLabel("0 claims");
        claimCountLabel.setFont(AeroFonts.MUTED);
        claimCountLabel.setForeground(AeroColors.TEXT_SECONDARY);
        claimRightActions.add(claimCountLabel);

        claimFilterBar.add(claimLeftFilters, BorderLayout.WEST);
        claimFilterBar.add(claimRightActions, BorderLayout.EAST);
        claimTab.add(claimFilterBar, BorderLayout.NORTH);

        // Table for Claims
        claimTable = new StyledTable(claimTableModel);
        claimTable.getColumnModel().getColumn(5).setCellRenderer(new StatusBadgeRenderer());
        JScrollPane claimScroll = new JScrollPane(claimTable);
        claimScroll.setBorder(BorderFactory.createLineBorder(AeroColors.BORDER, 1));
        claimScroll.getViewport().setBackground(Color.WHITE);

        JPanel claimEmpty = new JPanel(new BorderLayout());
        claimEmpty.setBackground(Color.WHITE);
        claimEmpty.setBorder(BorderFactory.createLineBorder(AeroColors.BORDER, 1));
        JLabel claimEmptyLbl = new JLabel("No passenger claims found matching criteria.", SwingConstants.CENTER);
        claimEmptyLbl.setFont(AeroFonts.BODY);
        claimEmptyLbl.setForeground(AeroColors.TEXT_MUTED);
        claimEmpty.add(claimEmptyLbl, BorderLayout.CENTER);

        claimTableContainer.add(claimScroll, "table");
        claimTableContainer.add(claimEmpty, "empty");
        claimCardLayout.show(claimTableContainer, "empty");
        claimTab.add(claimTableContainer, BorderLayout.CENTER);

        // Bottom Action Bar for Claims
        JPanel claimBottomBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        claimBottomBar.setOpaque(false);
        claimBottomBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));

        viewClaimBtn = new ModernButton("View Claim & History", ModernButton.Style.SECONDARY);
        viewClaimBtn.setPreferredSize(new Dimension(175, 36));
        viewClaimBtn.setEnabled(false);

        reviewClaimBtn = new ModernButton("Review Claim (Approve/Reject)", ModernButton.Style.SECONDARY);
        reviewClaimBtn.setPreferredSize(new Dimension(225, 36));
        reviewClaimBtn.setEnabled(false);

        settleClaimBtn = new ModernButton("Record Settlement", ModernButton.Style.PRIMARY);
        settleClaimBtn.setPreferredSize(new Dimension(160, 36));
        settleClaimBtn.setEnabled(false);

        claimBottomBar.add(viewClaimBtn);
        claimBottomBar.add(reviewClaimBtn);
        claimBottomBar.add(settleClaimBtn);
        claimTab.add(claimBottomBar, BorderLayout.SOUTH);

        tabbedPane.addTab("Passenger Claims & Settlements", claimTab);

        mainContent.add(tabbedPane);
        add(new AeroScrollPane(mainContent), BorderLayout.CENTER);

        // Listeners for PIR
        pirRefreshBtn.addActionListener(e -> loadPIRsAsync());
        pirStatusFilter.addActionListener(e -> loadPIRsAsync());
        pirSearchField.addActionListener(e -> loadPIRsAsync());

        pirTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = pirTable.getSelectedRow();
                if (row >= 0 && row < pirList.size()) {
                    selectedPIR = pirList.get(row);
                    viewPirBtn.setEnabled(true);
                    updatePirBtn.setEnabled(AuthorizationService.hasPermission(Permission.MANAGE_PIR) && selectedPIR.isActive());
                    fileClaimBtn.setEnabled(AuthorizationService.hasPermission(Permission.MANAGE_CLAIMS) && selectedPIR.isActive());
                } else {
                    selectedPIR = null;
                    viewPirBtn.setEnabled(false);
                    updatePirBtn.setEnabled(false);
                    fileClaimBtn.setEnabled(false);
                }
            }
        });

        createPirBtn.addActionListener(e -> showCreatePirDialog(null));
        viewPirBtn.addActionListener(e -> {
            if (selectedPIR != null) showPirDetailsDialog(selectedPIR);
        });
        updatePirBtn.addActionListener(e -> {
            if (selectedPIR != null) showUpdatePirDialog(selectedPIR);
        });
        fileClaimBtn.addActionListener(e -> {
            if (selectedPIR != null) showCreateClaimDialog(selectedPIR);
        });

        // Listeners for Claims
        claimRefreshBtn.addActionListener(e -> loadClaimsAsync());
        claimStatusFilter.addActionListener(e -> loadClaimsAsync());
        claimSearchField.addActionListener(e -> loadClaimsAsync());

        claimTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = claimTable.getSelectedRow();
                if (row >= 0 && row < claimList.size()) {
                    selectedClaim = claimList.get(row);
                    viewClaimBtn.setEnabled(true);
                    reviewClaimBtn.setEnabled(AuthorizationService.hasPermission(Permission.REVIEW_CLAIMS) && !selectedClaim.isTerminal() && selectedClaim.status() != ClaimStatus.APPROVED);
                    settleClaimBtn.setEnabled(AuthorizationService.hasPermission(Permission.MANAGE_CLAIM_SETTLEMENTS) && selectedClaim.isApproved());
                } else {
                    selectedClaim = null;
                    viewClaimBtn.setEnabled(false);
                    reviewClaimBtn.setEnabled(false);
                    settleClaimBtn.setEnabled(false);
                }
            }
        });

        viewClaimBtn.addActionListener(e -> {
            if (selectedClaim != null) showClaimDetailsDialog(selectedClaim);
        });
        reviewClaimBtn.addActionListener(e -> {
            if (selectedClaim != null) showReviewClaimDialog(selectedClaim);
        });
        settleClaimBtn.addActionListener(e -> {
            if (selectedClaim != null) showSettleClaimDialog(selectedClaim);
        });

        // Navigation listener for deep linking
        if (controller != null) {
            controller.addListener((route, param) -> {
                if (route == NavRoute.PIR_CLAIMS) {
                    if (param instanceof String value && !value.isBlank()) {
                        pirSearchField.setText(value.trim());
                    }
                    loadPIRsAsync();
                    loadClaimsAsync();
                }
            });
        }

        // Initial Data Load
        loadPIRsAsync();
        loadClaimsAsync();
    }

    public void loadPIRsAsync() {
        pirRefreshBtn.setEnabled(false);
        pirRefreshBtn.setText("Loading…");
        String query = pirSearchField.getText();
        String statusStr = (String) pirStatusFilter.getSelectedItem();
        PIRStatus filterStatus = (statusStr != null && !statusStr.startsWith("All")) ? PIRStatus.fromString(statusStr) : null;

        SwingWorker<List<PIRRecord>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<PIRRecord> doInBackground() throws Exception {
                return PIRService.getInstance().searchPIRs(query, filterStatus);
            }

            @Override
            protected void done() {
                pirRefreshBtn.setEnabled(true);
                pirRefreshBtn.setText("↻ Refresh");
                try {
                    pirList.clear();
                    pirList.addAll(get());
                    pirTableModel.fireTableDataChanged();
                    pirCountLabel.setText(pirList.size() + (pirList.size() == 1 ? " report" : " reports"));
                    pirCardLayout.show(pirTableContainer, pirList.isEmpty() ? "empty" : "table");

                    int openCount = 0;
                    int missingCount = 0;
                    for (PIRRecord p : pirList) {
                        if (p.status() == PIRStatus.OPEN || p.status() == PIRStatus.UNDER_INVESTIGATION) openCount++;
                        if (p.type() == PIRType.MISSING_BAGGAGE && p.isActive()) missingCount++;
                    }
                    openPirsCard.setValue(String.valueOf(openCount));
                    missingBaggageCard.setValue(String.valueOf(missingCount));
                } catch (Exception ex) {
                    notificationBanner.showMessage("Error loading PIRs: " + ex.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    public void loadClaimsAsync() {
        claimRefreshBtn.setEnabled(false);
        claimRefreshBtn.setText("Loading…");
        String query = claimSearchField.getText();
        String statusStr = (String) claimStatusFilter.getSelectedItem();
        ClaimStatus filterStatus = (statusStr != null && !statusStr.startsWith("All")) ? ClaimStatus.fromString(statusStr) : null;

        SwingWorker<List<ClaimCase>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<ClaimCase> doInBackground() throws Exception {
                return ClaimService.getInstance().searchClaims(query, filterStatus);
            }

            @Override
            protected void done() {
                claimRefreshBtn.setEnabled(true);
                claimRefreshBtn.setText("↻ Refresh");
                try {
                    claimList.clear();
                    claimList.addAll(get());
                    claimTableModel.fireTableDataChanged();
                    claimCountLabel.setText(claimList.size() + (claimList.size() == 1 ? " claim" : " claims"));
                    claimCardLayout.show(claimTableContainer, claimList.isEmpty() ? "empty" : "table");

                    int inReview = 0;
                    int settledCount = 0;
                    for (ClaimCase c : claimList) {
                        if (c.status() == ClaimStatus.SUBMITTED || c.status() == ClaimStatus.UNDER_REVIEW) inReview++;
                        if (c.isSettled()) settledCount++;
                    }
                    claimsReviewCard.setValue(String.valueOf(inReview));
                    settlementsPaidCard.setValue(String.valueOf(settledCount));
                } catch (Exception ex) {
                    notificationBanner.showMessage("Error loading claims: " + ex.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    public void openCreatePirForBag(String bagTag) {
        tabbedPane.setSelectedIndex(0);
        showCreatePirDialog(bagTag);
    }

    // ==========================================
    // MODAL DIALOGS
    // ==========================================

    private void showCreatePirDialog(String initialTag) {
        Component parent = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parent instanceof java.awt.Frame ? (java.awt.Frame) parent : null, "FILE PROPERTY IRREGULARITY REPORT", true);
        dialog.setSize(520, 480);
        dialog.setLocationRelativeTo(parent);
        dialog.setLayout(new BorderLayout());

        JPanel form = new JPanel(new GridLayout(5, 2, 12, 14));
        form.setBackground(Color.WHITE);
        form.setBorder(new EmptyBorder(24, 24, 24, 24));

        ModernTextField tagField = new ModernTextField();
        tagField.setPlaceholder("Enter Baggage Tag # (e.g. BAG...)");
        if (initialTag != null) tagField.setText(initialTag);

        ModernComboBox<PIRType> typeCombo = new ModernComboBox<>(new PIRType[]{PIRType.DELAYED_BAGGAGE, PIRType.MISSING_BAGGAGE});
        ModernTextField stationField = new ModernTextField();
        stationField.setPlaceholder("Airport Station (e.g. DEL, BOM)");
        stationField.setText("STATION");

        JTextArea descArea = new JTextArea(4, 20);
        descArea.setLineWrap(true);
        descArea.setWrapStyleWord(true);
        descArea.setBorder(BorderFactory.createLineBorder(AeroColors.BORDER, 1));
        descArea.setFont(AeroFonts.BODY);

        form.add(new JLabel("Baggage Tag Number:"));
        form.add(tagField);
        form.add(new JLabel("Incident Irregularity Type:"));
        form.add(typeCombo);
        form.add(new JLabel("Airport Station Code:"));
        form.add(stationField);
        form.add(new JLabel("Incident Description:"));
        form.add(new JScrollPane(descArea));

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 12));
        bottom.setBackground(Color.WHITE);
        bottom.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, AeroColors.BORDER));

        ModernButton cancelBtn = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        cancelBtn.addActionListener(e -> dialog.dispose());

        ModernButton submitBtn = new ModernButton("File Report", ModernButton.Style.PRIMARY);
        submitBtn.addActionListener(e -> {
            String tag = tagField.getText().trim();
            PIRType type = (PIRType) typeCombo.getSelectedItem();
            String station = stationField.getText().trim();
            String desc = descArea.getText().trim();

            if (tag.isBlank() || desc.isBlank()) {
                JOptionPane.showMessageDialog(dialog, "Baggage tag and description are required.", "Validation Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            submitBtn.setEnabled(false);
            SwingWorker<PIRRecord, Void> w = new SwingWorker<>() {
                @Override
                protected PIRRecord doInBackground() throws Exception {
                    Optional<BaggageRecord> bagOpt = BaggageService.getInstance().findByTagNumber(tag);
                    if (bagOpt.isEmpty()) {
                        throw new IllegalArgumentException("No baggage found matching tag [" + tag + "].");
                    }
                    BaggageRecord bag = bagOpt.get();
                    return PIRService.getInstance().createPIR(bag.id(), type, station, desc);
                }

                @Override
                protected void done() {
                    try {
                        PIRRecord pir = get();
                        dialog.dispose();
                        notificationBanner.showMessage("Property Irregularity Report [" + pir.pirReference() + "] filed successfully.", NotificationBanner.Type.SUCCESS);
                        loadPIRsAsync();
                    } catch (Exception ex) {
                        submitBtn.setEnabled(true);
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        JOptionPane.showMessageDialog(dialog, cause.getMessage(), "Error Filing PIR", JOptionPane.ERROR_MESSAGE);
                    }
                }
            };
            w.execute();
        });

        bottom.add(cancelBtn);
        bottom.add(submitBtn);

        dialog.add(form, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void showUpdatePirDialog(PIRRecord pir) {
        Component parent = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parent instanceof java.awt.Frame ? (java.awt.Frame) parent : null, "UPDATE PIR — " + pir.pirReference(), true);
        dialog.setSize(480, 400);
        dialog.setLocationRelativeTo(parent);
        dialog.setLayout(new BorderLayout());

        JPanel form = new JPanel(new GridLayout(4, 2, 10, 14));
        form.setBackground(Color.WHITE);
        form.setBorder(new EmptyBorder(24, 24, 24, 24));

        Set<PIRStatus> nextStates = PIRLifecycleValidator.getAvailableNextStates(pir.status());
        List<PIRStatus> options = new ArrayList<>();
        options.add(pir.status());
        options.addAll(nextStates);

        ModernComboBox<PIRStatus> statusCombo = new ModernComboBox<>(options.toArray(new PIRStatus[0]));

        JTextArea remarksArea = new JTextArea(4, 20);
        remarksArea.setLineWrap(true);
        remarksArea.setWrapStyleWord(true);
        remarksArea.setBorder(BorderFactory.createLineBorder(AeroColors.BORDER, 1));
        remarksArea.setFont(AeroFonts.BODY);

        form.add(new JLabel("PIR Reference:"));
        form.add(new JLabel(pir.pirReference() + " (" + pir.type().getDisplayName() + ")"));
        form.add(new JLabel("Current Status:"));
        form.add(new JLabel(pir.status().name()));
        form.add(new JLabel("New Status:"));
        form.add(statusCombo);
        form.add(new JLabel("Investigation / Update Notes:"));
        form.add(new JScrollPane(remarksArea));

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 12));
        bottom.setBackground(Color.WHITE);
        bottom.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, AeroColors.BORDER));

        ModernButton cancelBtn = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        cancelBtn.addActionListener(e -> dialog.dispose());

        ModernButton saveBtn = new ModernButton("Update PIR", ModernButton.Style.PRIMARY);
        saveBtn.addActionListener(e -> {
            PIRStatus newSt = (PIRStatus) statusCombo.getSelectedItem();
            String notes = remarksArea.getText().trim();

            saveBtn.setEnabled(false);
            SwingWorker<PIRRecord, Void> w = new SwingWorker<>() {
                @Override
                protected PIRRecord doInBackground() throws Exception {
                    if (newSt != pir.status()) {
                        PIRService.getInstance().updatePIRStatus(pir.id(), newSt, notes);
                    }
                    if (!notes.isBlank() && newSt == pir.status()) {
                        PIRService.getInstance().addInvestigationNotes(pir.id(), notes);
                    }
                    return PIRService.getInstance().findById(pir.id()).orElseThrow();
                }

                @Override
                protected void done() {
                    try {
                        get();
                        dialog.dispose();
                        notificationBanner.showMessage("PIR [" + pir.pirReference() + "] updated successfully.", NotificationBanner.Type.SUCCESS);
                        loadPIRsAsync();
                    } catch (Exception ex) {
                        saveBtn.setEnabled(true);
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        JOptionPane.showMessageDialog(dialog, cause.getMessage(), "Error Updating PIR", JOptionPane.ERROR_MESSAGE);
                    }
                }
            };
            w.execute();
        });

        bottom.add(cancelBtn);
        bottom.add(saveBtn);

        dialog.add(form, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void showCreateClaimDialog(PIRRecord pir) {
        Component parent = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parent instanceof java.awt.Frame ? (java.awt.Frame) parent : null, "FILE BAGGAGE CLAIM — PIR " + pir.pirReference(), true);
        dialog.setSize(500, 440);
        dialog.setLocationRelativeTo(parent);
        dialog.setLayout(new BorderLayout());

        JPanel form = new JPanel(new GridLayout(5, 2, 10, 14));
        form.setBackground(Color.WHITE);
        form.setBorder(new EmptyBorder(24, 24, 24, 24));

        ModernTextField amountField = new ModernTextField();
        amountField.setPlaceholder("e.g. 250.00");

        ModernComboBox<String> currCombo = new ModernComboBox<>(new String[]{"USD", "INR", "EUR", "GBP"});

        JTextArea reasonArea = new JTextArea(4, 20);
        reasonArea.setLineWrap(true);
        reasonArea.setWrapStyleWord(true);
        reasonArea.setBorder(BorderFactory.createLineBorder(AeroColors.BORDER, 1));
        reasonArea.setFont(AeroFonts.BODY);

        form.add(new JLabel("PIR / Passenger:"));
        form.add(new JLabel(pir.pirReference() + " — " + pir.passengerName()));
        form.add(new JLabel("Baggage Tag:"));
        form.add(new JLabel(pir.baggageTag() + " (" + pir.type().getDisplayName() + ")"));
        form.add(new JLabel("Compensation Currency:"));
        form.add(currCombo);
        form.add(new JLabel("Requested Amount:"));
        form.add(amountField);
        form.add(new JLabel("Claim Justification / Items:"));
        form.add(new JScrollPane(reasonArea));

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 12));
        bottom.setBackground(Color.WHITE);
        bottom.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, AeroColors.BORDER));

        ModernButton cancelBtn = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        cancelBtn.addActionListener(e -> dialog.dispose());

        ModernButton submitBtn = new ModernButton("Submit Claim", ModernButton.Style.PRIMARY);
        submitBtn.addActionListener(e -> {
            String amtStr = amountField.getText().trim();
            String curr = (String) currCombo.getSelectedItem();
            String reason = reasonArea.getText().trim();

            if (amtStr.isBlank() || reason.isBlank()) {
                JOptionPane.showMessageDialog(dialog, "Amount and claim reason are required.", "Validation Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            BigDecimal reqAmt;
            try {
                reqAmt = new BigDecimal(amtStr);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Invalid numeric compensation amount.", "Validation Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            submitBtn.setEnabled(false);
            SwingWorker<ClaimCase, Void> w = new SwingWorker<>() {
                @Override
                protected ClaimCase doInBackground() throws Exception {
                    return ClaimService.getInstance().createClaim(pir.id(), reqAmt, curr, reason);
                }

                @Override
                protected void done() {
                    try {
                        ClaimCase c = get();
                        dialog.dispose();
                        notificationBanner.showMessage("Baggage Claim [" + c.claimReference() + "] filed successfully.", NotificationBanner.Type.SUCCESS);
                        loadClaimsAsync();
                        tabbedPane.setSelectedIndex(1);
                    } catch (Exception ex) {
                        submitBtn.setEnabled(true);
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        JOptionPane.showMessageDialog(dialog, cause.getMessage(), "Error Filing Claim", JOptionPane.ERROR_MESSAGE);
                    }
                }
            };
            w.execute();
        });

        bottom.add(cancelBtn);
        bottom.add(submitBtn);

        dialog.add(form, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void showReviewClaimDialog(ClaimCase claim) {
        Component parent = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parent instanceof java.awt.Frame ? (java.awt.Frame) parent : null, "REVIEW CLAIM — " + claim.claimReference(), true);
        dialog.setSize(500, 440);
        dialog.setLocationRelativeTo(parent);
        dialog.setLayout(new BorderLayout());

        JPanel form = new JPanel(new GridLayout(5, 2, 10, 14));
        form.setBackground(Color.WHITE);
        form.setBorder(new EmptyBorder(24, 24, 24, 24));

        Set<ClaimStatus> nextStates = ClaimLifecycleValidator.getAvailableNextStates(claim.status());
        List<ClaimStatus> options = new ArrayList<>();
        options.add(claim.status());
        options.addAll(nextStates);

        ModernComboBox<ClaimStatus> statusCombo = new ModernComboBox<>(options.toArray(new ClaimStatus[0]));

        ModernTextField approvedField = new ModernTextField();
        approvedField.setText(claim.approvedAmount() != null ? claim.approvedAmount().toPlainString() : claim.requestedAmount().toPlainString());
        ClaimStatus initialSelected = (ClaimStatus) statusCombo.getSelectedItem();
        approvedField.setEnabled(initialSelected == ClaimStatus.APPROVED);
        statusCombo.addActionListener(e -> {
            ClaimStatus st = (ClaimStatus) statusCombo.getSelectedItem();
            approvedField.setEnabled(st == ClaimStatus.APPROVED);
        });

        JTextArea notesArea = new JTextArea(4, 20);
        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);
        notesArea.setBorder(BorderFactory.createLineBorder(AeroColors.BORDER, 1));
        notesArea.setFont(AeroFonts.BODY);

        form.add(new JLabel("Claim / Passenger:"));
        form.add(new JLabel(claim.claimReference() + " (" + claim.passengerName() + ")"));
        form.add(new JLabel("Requested Amount:"));
        form.add(new JLabel(claim.currency() + " " + claim.requestedAmount()));
        form.add(new JLabel("Target Review Status:"));
        form.add(statusCombo);
        form.add(new JLabel("Approved Amount (" + claim.currency() + "):"));
        form.add(approvedField);
        form.add(new JLabel("Review Notes / Justification:"));
        form.add(new JScrollPane(notesArea));

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 12));
        bottom.setBackground(Color.WHITE);
        bottom.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, AeroColors.BORDER));

        ModernButton cancelBtn = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        cancelBtn.addActionListener(e -> dialog.dispose());

        ModernButton saveBtn = new ModernButton("Apply Review Decision", ModernButton.Style.PRIMARY);
        saveBtn.addActionListener(e -> {
            ClaimStatus newSt = (ClaimStatus) statusCombo.getSelectedItem();
            String notes = notesArea.getText().trim();
            BigDecimal approved = null;
            if (newSt == ClaimStatus.APPROVED) {
                try {
                    approved = new BigDecimal(approvedField.getText().trim());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, "Please enter a valid approved compensation amount.", "Validation Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }

            saveBtn.setEnabled(false);
            BigDecimal finalApproved = approved;
            SwingWorker<ClaimCase, Void> w = new SwingWorker<>() {
                @Override
                protected ClaimCase doInBackground() throws Exception {
                    return ClaimService.getInstance().reviewClaim(claim.id(), newSt, finalApproved, notes);
                }

                @Override
                protected void done() {
                    try {
                        get();
                        dialog.dispose();
                        notificationBanner.showMessage("Claim [" + claim.claimReference() + "] transitioned to " + newSt.name(), NotificationBanner.Type.SUCCESS);
                        loadClaimsAsync();
                    } catch (Exception ex) {
                        saveBtn.setEnabled(true);
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        JOptionPane.showMessageDialog(dialog, cause.getMessage(), "Error Reviewing Claim", JOptionPane.ERROR_MESSAGE);
                    }
                }
            };
            w.execute();
        });

        bottom.add(cancelBtn);
        bottom.add(saveBtn);

        dialog.add(form, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void showSettleClaimDialog(ClaimCase claim) {
        Component parent = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parent instanceof java.awt.Frame ? (java.awt.Frame) parent : null, "EXECUTE COMPENSATION SETTLEMENT", true);
        dialog.setSize(480, 380);
        dialog.setLocationRelativeTo(parent);
        dialog.setLayout(new BorderLayout());

        JPanel form = new JPanel(new GridLayout(4, 2, 10, 14));
        form.setBackground(Color.WHITE);
        form.setBorder(new EmptyBorder(24, 24, 24, 24));

        ModernComboBox<String> methodCombo = new ModernComboBox<>(new String[]{"BANK_TRANSFER", "UPI", "CARD", "CASH", "VOUCHER"});

        JTextArea reasonArea = new JTextArea(4, 20);
        reasonArea.setLineWrap(true);
        reasonArea.setWrapStyleWord(true);
        reasonArea.setBorder(BorderFactory.createLineBorder(AeroColors.BORDER, 1));
        reasonArea.setFont(AeroFonts.BODY);
        reasonArea.setText("Settlement authorized as per approved baggage irregularity evaluation.");

        form.add(new JLabel("Claim Reference:"));
        form.add(new JLabel(claim.claimReference() + " (" + claim.passengerName() + ")"));
        form.add(new JLabel("Settlement Amount:"));
        form.add(new JLabel(claim.currency() + " " + claim.approvedAmount()));
        form.add(new JLabel("Disbursement Method:"));
        form.add(methodCombo);
        form.add(new JLabel("Settlement Justification:"));
        form.add(new JScrollPane(reasonArea));

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 12));
        bottom.setBackground(Color.WHITE);
        bottom.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, AeroColors.BORDER));

        ModernButton cancelBtn = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        cancelBtn.addActionListener(e -> dialog.dispose());

        ModernButton settleBtn = new ModernButton("Execute Settlement", ModernButton.Style.PRIMARY);
        settleBtn.addActionListener(e -> {
            String method = (String) methodCombo.getSelectedItem();
            String reason = reasonArea.getText().trim();

            settleBtn.setEnabled(false);
            SwingWorker<ClaimSettlementRecord, Void> w = new SwingWorker<>() {
                @Override
                protected ClaimSettlementRecord doInBackground() throws Exception {
                    return ClaimService.getInstance().settleClaim(claim.id(), method, reason);
                }

                @Override
                protected void done() {
                    try {
                        ClaimSettlementRecord stl = get();
                        dialog.dispose();
                        notificationBanner.showMessage("Settlement [" + stl.settlementReference() + "] executed successfully for " + stl.currency() + " " + stl.amount(), NotificationBanner.Type.SUCCESS);
                        loadClaimsAsync();
                    } catch (Exception ex) {
                        settleBtn.setEnabled(true);
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        JOptionPane.showMessageDialog(dialog, cause.getMessage(), "Settlement Execution Failed", JOptionPane.ERROR_MESSAGE);
                    }
                }
            };
            w.execute();
        });

        bottom.add(cancelBtn);
        bottom.add(settleBtn);

        dialog.add(form, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void showPirDetailsDialog(PIRRecord pir) {
        Component parent = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parent instanceof java.awt.Frame ? (java.awt.Frame) parent : null, "PIR INCIDENT & AUDIT HISTORY — " + pir.pirReference(), true);
        dialog.setSize(640, 560);
        dialog.setLocationRelativeTo(parent);
        dialog.setLayout(new BorderLayout());

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Color.WHITE);
        content.setBorder(new EmptyBorder(18, 20, 18, 20));

        JPanel infoGrid = new JPanel(new GridLayout(6, 2, 8, 8));
        infoGrid.setOpaque(false);
        infoGrid.add(new JLabel("PIR Reference: " + pir.pirReference() + " (ID: #" + pir.id() + ")"));
        infoGrid.add(new JLabel("Status: " + pir.status().name()));
        infoGrid.add(new JLabel("Baggage Tag: " + pir.baggageTag()));
        infoGrid.add(new JLabel("Baggage Type: " + (pir.baggageType() != null && !pir.baggageType().isBlank() ? pir.baggageType() : "Checked Bag")));
        infoGrid.add(new JLabel("Incident Type: " + pir.type().getDisplayName()));
        infoGrid.add(new JLabel("Station: " + pir.stationCode()));
        infoGrid.add(new JLabel("Passenger: " + pir.passengerName() + " (" + pir.pnr() + ")"));
        infoGrid.add(new JLabel("Flight: " + pir.flightNumber() + " (" + pir.route() + ")"));
        infoGrid.add(new JLabel("Incident Time: " + pir.incidentTime().format(TIME_FMT)));
        infoGrid.add(new JLabel("Reported: " + (pir.createdAt() != null ? pir.createdAt().format(TIME_FMT) : "—")));
        infoGrid.add(new JLabel("Updated: " + (pir.updatedAt() != null ? pir.updatedAt().format(TIME_FMT) : "—")));
        JLabel claimStatusLbl = new JLabel("Linked Claim: Checking...");
        claimStatusLbl.setFont(AeroFonts.CAPTION_BOLD);
        infoGrid.add(claimStatusLbl);
        content.add(infoGrid);
        content.add(Box.createVerticalStrut(12));

        JLabel descLbl = new JLabel("Incident Description: " + pir.description());
        descLbl.setFont(AeroFonts.CAPTION_BOLD);
        content.add(descLbl);
        content.add(Box.createVerticalStrut(8));

        if (pir.investigationNotes() != null && !pir.investigationNotes().isBlank()) {
            JLabel notesLbl = new JLabel("Investigation Notes:");
            notesLbl.setFont(AeroFonts.CAPTION_BOLD);
            content.add(notesLbl);
            JTextArea notesArea = new JTextArea(pir.investigationNotes());
            notesArea.setEditable(false);
            notesArea.setBackground(new Color(0xF8, 0xFA, 0xFC));
            notesArea.setBorder(BorderFactory.createLineBorder(AeroColors.BORDER));
            content.add(new JScrollPane(notesArea));
            content.add(Box.createVerticalStrut(12));
        }

        JLabel evTitle = new JLabel("Chronological Tracking Events & Audit Trail:");
        evTitle.setFont(AeroFonts.CAPTION_BOLD);
        content.add(evTitle);
        content.add(Box.createVerticalStrut(4));

        JPanel eventListPanel = new JPanel();
        eventListPanel.setLayout(new BoxLayout(eventListPanel, BoxLayout.Y_AXIS));
        eventListPanel.setBackground(Color.WHITE);
        JScrollPane eventScroll = new JScrollPane(eventListPanel);
        eventScroll.setPreferredSize(new Dimension(580, 160));
        content.add(eventScroll);

        record PirDetailData(List<ClaimEventRecord> events, Optional<ClaimCase> activeClaim) {}

        SwingWorker<PirDetailData, Void> evWorker = new SwingWorker<>() {
            @Override
            protected PirDetailData doInBackground() throws Exception {
                List<ClaimEventRecord> events = PIRService.getInstance().getPIREvents(pir.id());
                Optional<ClaimCase> claimOpt = ClaimService.getInstance().findActiveByPirId(pir.id());
                return new PirDetailData(events, claimOpt);
            }

            @Override
            protected void done() {
                try {
                    PirDetailData data = get();
                    if (data.activeClaim().isPresent()) {
                        ClaimCase c = data.activeClaim().get();
                        claimStatusLbl.setText("Linked Claim: " + c.claimReference() + " [" + c.status().name() + "]");
                    } else {
                        claimStatusLbl.setText("Linked Claim: None (Eligible)");
                    }

                    eventListPanel.removeAll();
                    if (data.events().isEmpty()) {
                        eventListPanel.add(new JLabel("No event records logged yet."));
                    } else {
                        for (ClaimEventRecord ev : data.events()) {
                            JLabel l = new JLabel("• [" + ev.createdAt().format(TIME_FMT) + "] " + ev.eventType() + " (" + (ev.performedByUsername() != null ? ev.performedByUsername() : "System") + "): " + (ev.remarks() != null ? ev.remarks() : ""));
                            l.setFont(AeroFonts.CAPTION);
                            eventListPanel.add(l);
                            eventListPanel.add(Box.createVerticalStrut(4));
                        }
                    }
                    eventListPanel.revalidate();
                    eventListPanel.repaint();
                } catch (Exception ignored) {}
            }
        };
        evWorker.execute();

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.setBackground(Color.WHITE);
        ModernButton closeBtn = new ModernButton("Close", ModernButton.Style.SECONDARY);
        closeBtn.addActionListener(e -> dialog.dispose());
        bottom.add(closeBtn);

        dialog.add(content, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void showClaimDetailsDialog(ClaimCase claim) {
        Component parent = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parent instanceof java.awt.Frame ? (java.awt.Frame) parent : null, "CLAIM DETAILS & SETTLEMENT — " + claim.claimReference(), true);
        dialog.setSize(640, 560);
        dialog.setLocationRelativeTo(parent);
        dialog.setLayout(new BorderLayout());

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Color.WHITE);
        content.setBorder(new EmptyBorder(18, 20, 18, 20));

        JPanel infoGrid = new JPanel(new GridLayout(6, 2, 8, 8));
        infoGrid.setOpaque(false);
        infoGrid.add(new JLabel("Claim Reference: " + claim.claimReference() + " (ID: #" + claim.id() + ")"));
        infoGrid.add(new JLabel("Status: " + claim.status().name()));
        infoGrid.add(new JLabel("Linked PIR: " + claim.pirReference() + " (PIR ID: #" + claim.pirId() + ")"));
        infoGrid.add(new JLabel("Baggage Tag: " + claim.baggageTag()));
        infoGrid.add(new JLabel("Passenger: " + claim.passengerName() + " (" + claim.pnr() + ")"));
        infoGrid.add(new JLabel("Requested: " + claim.currency() + " " + claim.requestedAmount()));
        infoGrid.add(new JLabel("Approved Amount: " + (claim.approvedAmount() != null ? (claim.currency() + " " + claim.approvedAmount()) : "—")));
        infoGrid.add(new JLabel("Filed At: " + claim.createdAt().format(TIME_FMT)));
        infoGrid.add(new JLabel("Updated At: " + (claim.updatedAt() != null ? claim.updatedAt().format(TIME_FMT) : "—")));
        JLabel settlementLbl = new JLabel("Settlement: Checking...");
        settlementLbl.setFont(AeroFonts.CAPTION_BOLD);
        infoGrid.add(settlementLbl);
        content.add(infoGrid);
        content.add(Box.createVerticalStrut(12));

        JLabel reasonLbl = new JLabel("Claim Reason: " + claim.claimReason());
        reasonLbl.setFont(AeroFonts.CAPTION_BOLD);
        content.add(reasonLbl);
        content.add(Box.createVerticalStrut(8));

        if (claim.reviewNotes() != null && !claim.reviewNotes().isBlank()) {
            JLabel revLbl = new JLabel("Review History & Justification:");
            revLbl.setFont(AeroFonts.CAPTION_BOLD);
            content.add(revLbl);
            JTextArea notesArea = new JTextArea(claim.reviewNotes());
            notesArea.setEditable(false);
            notesArea.setBackground(new Color(0xF8, 0xFA, 0xFC));
            notesArea.setBorder(BorderFactory.createLineBorder(AeroColors.BORDER));
            content.add(new JScrollPane(notesArea));
            content.add(Box.createVerticalStrut(12));
        }

        JLabel evTitle = new JLabel("Audit & Settlement History:");
        evTitle.setFont(AeroFonts.CAPTION_BOLD);
        content.add(evTitle);
        content.add(Box.createVerticalStrut(4));

        JPanel eventListPanel = new JPanel();
        eventListPanel.setLayout(new BoxLayout(eventListPanel, BoxLayout.Y_AXIS));
        eventListPanel.setBackground(Color.WHITE);
        JScrollPane eventScroll = new JScrollPane(eventListPanel);
        eventScroll.setPreferredSize(new Dimension(580, 140));
        content.add(eventScroll);

        record ClaimDetailData(List<ClaimEventRecord> events, Optional<ClaimSettlementRecord> settlement) {}

        SwingWorker<ClaimDetailData, Void> evWorker = new SwingWorker<>() {
            @Override
            protected ClaimDetailData doInBackground() throws Exception {
                List<ClaimEventRecord> events = ClaimService.getInstance().getClaimEvents(claim.id());
                Optional<ClaimSettlementRecord> stlOpt = ClaimService.getInstance().getSettlementByClaimId(claim.id());
                return new ClaimDetailData(events, stlOpt);
            }

            @Override
            protected void done() {
                try {
                    ClaimDetailData data = get();
                    if (data.settlement().isPresent()) {
                        ClaimSettlementRecord stl = data.settlement().get();
                        settlementLbl.setText("Settlement: " + stl.settlementReference() + " (" + stl.currency() + " " + stl.amount() + " via " + stl.paymentMethod() + ", Auth: " + (stl.authorizedByUsername() != null ? stl.authorizedByUsername() : "Staff") + ")");
                    } else {
                        settlementLbl.setText("Settlement: None (" + (claim.isApproved() ? "Ready for disbursement" : (claim.isSettled() ? "Settled" : "Not yet approved")) + ")");
                    }

                    eventListPanel.removeAll();
                    if (data.events().isEmpty()) {
                        eventListPanel.add(new JLabel("No claim event records logged yet."));
                    } else {
                        for (ClaimEventRecord ev : data.events()) {
                            JLabel l = new JLabel("• [" + ev.createdAt().format(TIME_FMT) + "] " + ev.eventType() + " (" + (ev.performedByUsername() != null ? ev.performedByUsername() : "Staff") + "): " + (ev.remarks() != null ? ev.remarks() : ""));
                            l.setFont(AeroFonts.CAPTION);
                            eventListPanel.add(l);
                            eventListPanel.add(Box.createVerticalStrut(4));
                        }
                    }
                    eventListPanel.revalidate();
                    eventListPanel.repaint();
                } catch (Exception ignored) {}
            }
        };
        evWorker.execute();

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.setBackground(Color.WHITE);
        ModernButton closeBtn = new ModernButton("Close", ModernButton.Style.SECONDARY);
        closeBtn.addActionListener(e -> dialog.dispose());
        bottom.add(closeBtn);

        dialog.add(content, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    // ==========================================
    // TABLE MODELS
    // ==========================================

    private class PIRTableModel extends AbstractTableModel {
        private final String[] columns = {"PIR Reference", "Baggage Tag", "Incident Type", "PIR Status", "Passenger", "PNR", "Flight", "Station", "Incident Time"};

        @Override public int getRowCount() { return pirList.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }

        @Override
        public Object getValueAt(int row, int col) {
            PIRRecord p = pirList.get(row);
            return switch (col) {
                case 0 -> p.pirReference();
                case 1 -> p.baggageTag();
                case 2 -> p.type().getDisplayName();
                case 3 -> p.status().name();
                case 4 -> p.passengerName();
                case 5 -> p.pnr();
                case 6 -> p.flightNumber();
                case 7 -> p.stationCode();
                case 8 -> p.incidentTime().format(TIME_FMT);
                default -> "";
            };
        }
    }

    private class ClaimTableModel extends AbstractTableModel {
        private final String[] columns = {"Claim Reference", "PIR Reference", "Baggage Tag", "Passenger", "PNR", "Claim Status", "Requested", "Approved", "Filed Date"};

        @Override public int getRowCount() { return claimList.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }

        @Override
        public Object getValueAt(int row, int col) {
            ClaimCase c = claimList.get(row);
            return switch (col) {
                case 0 -> c.claimReference();
                case 1 -> c.pirReference();
                case 2 -> c.baggageTag();
                case 3 -> c.passengerName();
                case 4 -> c.pnr();
                case 5 -> c.status().name();
                case 6 -> c.currency() + " " + c.requestedAmount();
                case 7 -> c.approvedAmount() != null ? (c.currency() + " " + c.approvedAmount()) : "—";
                case 8 -> c.createdAt().format(TIME_FMT);
                default -> "";
            };
        }
    }

    private static class StatusBadgeRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(javax.swing.JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel lbl = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            lbl.setHorizontalAlignment(CENTER);
            String st = value != null ? value.toString() : "";
            if ("DELIVERED".equals(st) || "SETTLED".equals(st) || "APPROVED".equals(st)) {
                lbl.setForeground(new Color(0x15, 0x80, 0x3D));
            } else if ("OPEN".equals(st) || "SUBMITTED".equals(st) || "UNDER_REVIEW".equals(st) || "UNDER_INVESTIGATION".equals(st) || "LOCATED".equals(st)) {
                lbl.setForeground(new Color(0xB4, 0x53, 0x09));
            } else if ("MISSING".equals(st) || "UNRESOLVED".equals(st) || "REJECTED".equals(st)) {
                lbl.setForeground(new Color(0xB9, 0x1C, 0x1C));
            } else {
                lbl.setForeground(AeroColors.TEXT_MUTED);
            }
            lbl.setFont(AeroFonts.CAPTION_BOLD);
            return lbl;
        }
    }
}
