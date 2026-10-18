package airline.management.system.ui.panels;

import airline.management.system.DBConnection;
import airline.management.system.ReservationService;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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
import javax.swing.table.TableCellRenderer;

/**
 * AeroDesk Enterprise Cancellation Management & Refund Operations Screen.
 * Primarily an operations and historical audit screen displaying authoritative database
 * records joined across cancellations, reservations, passengers, flights, and payments.
 * All cancellation actions delegate strictly to ReservationService.cancel(pnr).
 */
public class CancellationsPanel extends JPanel {
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NavigationController navigationController;
    private final NotificationBanner notificationBanner;

    // KPI Summary Metrics Cards
    private final DashboardCard totalCancellationsCard;
    private final DashboardCard totalRefundsCard;
    private final DashboardCard refundedAmountCard;
    private final DashboardCard cancelledTripsCard;

    // Active Cancellation Operations Console
    private final ModernTextField pnrLookupField;
    private final ModernButton findBookingBtn;
    private final ModernButton clearLookupBtn;
    private final CardLayout lookupResultCardLayout = new CardLayout();
    private final JPanel lookupResultContainer = new JPanel(lookupResultCardLayout);

    // Booking Lookup Display Controls
    private BookingLookupResult currentLookupResult = null;
    private final JLabel resPnrLabel = new JLabel("—");
    private final JLabel resDateLabel = new JLabel("—");
    private final StatusBadge resStatusBadge = new StatusBadge("—");
    private final JLabel resSeatLabel = new JLabel("—");
    private final JLabel resFareLabel = new JLabel("—");
    private final JLabel passNameLabel = new JLabel("—");
    private final JLabel passPhoneLabel = new JLabel("—");
    private final JLabel passEmailLabel = new JLabel("—");
    private final JLabel flightNoLabel = new JLabel("—");
    private final JLabel flightRouteLabel = new JLabel("—");
    private final JLabel flightDepLabel = new JLabel("—");
    private final StatusBadge pmtStatusBadge = new StatusBadge("—");
    private final JLabel pmtMethodLabel = new JLabel("—");
    private final JLabel pmtRefLabel = new JLabel("—");
    private final JLabel pmtAmountLabel = new JLabel("—");
    private final JLabel operationNoticeLabel = new JLabel(" ");
    private final ModernComboBox<String> cancelReasonCombo;
    private final ModernTextField refundInputField;
    private final ModernButton executeCancelBtn;

    // Historical Cancellations Filter and Table
    private final ModernTextField filterSearchField;
    private final ModernComboBox<String> filterStatusCombo;
    private final ModernButton filterBtn;
    private final ModernButton resetFilterBtn;
    private final ModernButton refreshBtn;
    private final ModernButton bookTicketBtn;
    private final JLabel recordCountLabel;

    private final StyledTable cancellationTable;
    private final CancellationTableModel cancellationTableModel;
    private final List<CancellationRecord> cancellationList = new ArrayList<>();
    private final CardLayout tableCardLayout = new CardLayout();
    private final JPanel tableContainer = new JPanel(tableCardLayout);

    // Table Selection Context & Actions
    private CancellationRecord selectedCancellation = null;
    private final JLabel selectedSummaryLabel;
    private final ModernButton viewDetailsBtn;
    private final ModernButton viewTripBtn;
    private final ModernButton viewPassengerBtn;
    private final ModernButton viewFlightBtn;

    public CancellationsPanel(NavigationController controller) {
        this.navigationController = controller;
        setLayout(new BorderLayout());
        setBackground(AeroColors.BACKGROUND);

        JPanel mainContent = new JPanel();
        mainContent.setLayout(new BoxLayout(mainContent, BoxLayout.Y_AXIS));
        mainContent.setBackground(AeroColors.BACKGROUND);
        mainContent.setBorder(new EmptyBorder(24, 28, 28, 28));

        // ----------------------------------------------------
        // 1. PAGE HEADER
        // ----------------------------------------------------
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);

        JPanel titleBlock = new JPanel();
        titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
        titleBlock.setOpaque(false);

        JLabel title = new JLabel("CANCELLATION MANAGEMENT");
        title.setFont(AeroFonts.TITLE_PAGE);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel subtitle = new JLabel("Review cancelled trips, refund records, and cancellation history.");
        subtitle.setFont(AeroFonts.BODY);
        subtitle.setForeground(AeroColors.TEXT_SECONDARY);

        titleBlock.add(title);
        titleBlock.add(Box.createVerticalStrut(2));
        titleBlock.add(subtitle);

        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        headerActions.setOpaque(false);

        bookTicketBtn = new ModernButton("+ Reservation Desk", ModernButton.Style.PRIMARY);
        bookTicketBtn.setPreferredSize(new Dimension(150, AeroConstants.BUTTON_HEIGHT));
        bookTicketBtn.addActionListener(e -> {
            if (navigationController != null) navigationController.navigateTo(NavRoute.BOOKING);
        });

        refreshBtn = new ModernButton("↻ Refresh", ModernButton.Style.SECONDARY);
        refreshBtn.setPreferredSize(new Dimension(100, AeroConstants.BUTTON_HEIGHT));
        refreshBtn.addActionListener(e -> refreshDataAsync());

        headerActions.add(bookTicketBtn);
        headerActions.add(refreshBtn);

        headerPanel.add(titleBlock, BorderLayout.WEST);
        headerPanel.add(headerActions, BorderLayout.EAST);
        mainContent.add(headerPanel);
        mainContent.add(Box.createVerticalStrut(14));

        // Notification Banner
        notificationBanner = new NotificationBanner();
        mainContent.add(notificationBanner);
        mainContent.add(Box.createVerticalStrut(14));

        // ----------------------------------------------------
        // 2. KPI SUMMARY METRIC CARDS
        // ----------------------------------------------------
        JPanel kpiGrid = new JPanel(new GridLayout(1, 4, 14, 0));
        kpiGrid.setOpaque(false);

        totalCancellationsCard = new DashboardCard("TOTAL CANCELLATIONS", "0", "Cancelled reservations", "cancel", AeroColors.DANGER_BG);
        totalRefundsCard = new DashboardCard("TOTAL REFUNDS", "0", "Processed refunds", "payment", AeroColors.INFO_BG);
        refundedAmountCard = new DashboardCard("REFUNDED AMOUNT", "₹0.00", "Settled refund balance", "payment", AeroColors.WARNING_BG);
        cancelledTripsCard = new DashboardCard("CANCELLED TRIPS", "0", "Total cancelled journeys", "flight", AeroColors.BORDER);

        kpiGrid.add(totalCancellationsCard);
        kpiGrid.add(totalRefundsCard);
        kpiGrid.add(refundedAmountCard);
        kpiGrid.add(cancelledTripsCard);
        mainContent.add(kpiGrid);
        mainContent.add(Box.createVerticalStrut(18));

        // ----------------------------------------------------
        // 3. CANCELLATION OPERATIONS CONSOLE (LOOKUP & ACTION)
        // ----------------------------------------------------
        JPanel consoleCard = new JPanel(new BorderLayout(0, 14));
        consoleCard.setBackground(Color.WHITE);
        consoleCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(18, 20, 20, 20)
        ));

        // Console Header
        JPanel consoleHeader = new JPanel(new BorderLayout());
        consoleHeader.setOpaque(false);

        JLabel consoleTitle = new JLabel("Process Ticket Cancellation");
        consoleTitle.setFont(AeroFonts.TITLE_SECTION);
        consoleTitle.setForeground(AeroColors.TEXT_MAIN);

        JLabel consoleSubtitle = new JLabel("Look up a confirmed reservation by PNR to inspect passenger details, seat allocation, and perform cancellation.");
        consoleSubtitle.setFont(AeroFonts.CAPTION);
        consoleSubtitle.setForeground(AeroColors.TEXT_MUTED);

        JPanel consoleTitleBox = new JPanel();
        consoleTitleBox.setLayout(new BoxLayout(consoleTitleBox, BoxLayout.Y_AXIS));
        consoleTitleBox.setOpaque(false);
        consoleTitleBox.add(consoleTitle);
        consoleTitleBox.add(Box.createVerticalStrut(2));
        consoleTitleBox.add(consoleSubtitle);
        consoleHeader.add(consoleTitleBox, BorderLayout.WEST);

        // Search Input Controls
        JPanel pnrSearchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        pnrSearchRow.setOpaque(false);

        JLabel pnrLabel = new JLabel("Booking PNR:");
        pnrLabel.setFont(AeroFonts.BODY_BOLD);
        pnrLabel.setForeground(AeroColors.TEXT_MAIN);

        pnrLookupField = new ModernTextField(18);
        pnrLookupField.setPlaceholder("e.g. PNR-1005A or 11");
        pnrLookupField.setPreferredSize(new Dimension(240, AeroConstants.INPUT_HEIGHT));
        pnrLookupField.addActionListener(e -> performBookingLookup());

        findBookingBtn = new ModernButton("Find Booking", ModernButton.Style.PRIMARY);
        findBookingBtn.setPreferredSize(new Dimension(125, AeroConstants.BUTTON_HEIGHT));
        findBookingBtn.addActionListener(e -> performBookingLookup());

        clearLookupBtn = new ModernButton("Clear", ModernButton.Style.SECONDARY);
        clearLookupBtn.setPreferredSize(new Dimension(75, AeroConstants.BUTTON_HEIGHT));
        clearLookupBtn.addActionListener(e -> clearBookingLookup());

        pnrSearchRow.add(pnrLabel);
        pnrSearchRow.add(pnrLookupField);
        pnrSearchRow.add(findBookingBtn);
        pnrSearchRow.add(clearLookupBtn);

        JPanel consoleTop = new JPanel(new BorderLayout(0, 12));
        consoleTop.setOpaque(false);
        consoleTop.add(consoleHeader, BorderLayout.NORTH);
        consoleTop.add(pnrSearchRow, BorderLayout.SOUTH);
        consoleCard.add(consoleTop, BorderLayout.NORTH);

        // Result Container (EMPTY vs DETAILS)
        JPanel emptyLookupPanel = createEmptyLookupPanel();
        JPanel detailsLookupPanel = createDetailsLookupPanel();

        lookupResultContainer.setOpaque(false);
        lookupResultContainer.add(emptyLookupPanel, "EMPTY");
        lookupResultContainer.add(detailsLookupPanel, "DETAILS");
        lookupResultCardLayout.show(lookupResultContainer, "EMPTY");
        consoleCard.add(lookupResultContainer, BorderLayout.CENTER);

        // Operations Action Row
        JPanel operationsBar = new JPanel(new BorderLayout(14, 0));
        operationsBar.setOpaque(false);
        operationsBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, AeroColors.BORDER),
            new EmptyBorder(14, 0, 0, 0)
        ));

        operationNoticeLabel.setFont(AeroFonts.CAPTION);
        operationNoticeLabel.setForeground(AeroColors.TEXT_MUTED);
        operationsBar.add(operationNoticeLabel, BorderLayout.CENTER);

        JPanel actionControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actionControls.setOpaque(false);

        JLabel reasonLabel = new JLabel("Reason:");
        reasonLabel.setFont(AeroFonts.CAPTION_BOLD);
        reasonLabel.setForeground(AeroColors.TEXT_SECONDARY);

        cancelReasonCombo = new ModernComboBox<>(new String[]{
            "Customer Request",
            "Schedule Conflict",
            "Flight Delay / Rescheduled",
            "Medical Emergency",
            "Duplicate Booking",
            "Administrative Release"
        });
        cancelReasonCombo.setEditable(true);
        cancelReasonCombo.setPreferredSize(new Dimension(170, AeroConstants.INPUT_HEIGHT));

        JLabel refundLabel = new JLabel("Refund (₹):");
        refundLabel.setFont(AeroFonts.CAPTION_BOLD);
        refundLabel.setForeground(AeroColors.TEXT_SECONDARY);

        refundInputField = new ModernTextField(6);
        refundInputField.setPlaceholder("Auto");
        refundInputField.setPreferredSize(new Dimension(80, AeroConstants.INPUT_HEIGHT));

        executeCancelBtn = new ModernButton("Cancel Ticket & Release Seat", ModernButton.Style.DANGER);
        executeCancelBtn.setPreferredSize(new Dimension(210, AeroConstants.BUTTON_HEIGHT));
        executeCancelBtn.setEnabled(false);
        executeCancelBtn.addActionListener(e -> handleCancelBookingAction());

        actionControls.add(reasonLabel);
        actionControls.add(cancelReasonCombo);
        actionControls.add(refundLabel);
        actionControls.add(refundInputField);
        actionControls.add(executeCancelBtn);
        operationsBar.add(actionControls, BorderLayout.EAST);

        consoleCard.add(operationsBar, BorderLayout.SOUTH);
        mainContent.add(consoleCard);
        mainContent.add(Box.createVerticalStrut(18));

        // ----------------------------------------------------
        // 4. HISTORICAL CANCELLATIONS FILTER & TABLE
        // ----------------------------------------------------
        JPanel filterCard = new JPanel(new BorderLayout(12, 0));
        filterCard.setBackground(Color.WHITE);
        filterCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(12, 16, 12, 16)
        ));

        filterSearchField = new ModernTextField();
        filterSearchField.setPlaceholder("Search cancellations by PNR, passenger, flight, reason, or cancellation ID...");
        filterSearchField.addActionListener(e -> loadCancellationsAsync());

        JPanel filterControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        filterControls.setOpaque(false);

        filterStatusCombo = new ModernComboBox<>(new String[]{"All Cancellations", "COMPLETED", "REFUNDED", "PENDING", "FAILED"});
        filterStatusCombo.setPreferredSize(new Dimension(150, AeroConstants.INPUT_HEIGHT));
        filterStatusCombo.addActionListener(e -> loadCancellationsAsync());

        filterBtn = new ModernButton("Search", ModernButton.Style.SECONDARY);
        filterBtn.setPreferredSize(new Dimension(84, AeroConstants.BUTTON_HEIGHT));
        filterBtn.addActionListener(e -> loadCancellationsAsync());

        resetFilterBtn = new ModernButton("Reset", ModernButton.Style.SECONDARY);
        resetFilterBtn.setPreferredSize(new Dimension(76, AeroConstants.BUTTON_HEIGHT));
        resetFilterBtn.addActionListener(e -> resetFilters());

        filterControls.add(new JLabel("Status:"));
        filterControls.add(filterStatusCombo);
        filterControls.add(filterBtn);
        filterControls.add(resetFilterBtn);

        filterCard.add(filterSearchField, BorderLayout.CENTER);
        filterCard.add(filterControls, BorderLayout.EAST);
        mainContent.add(filterCard);
        mainContent.add(Box.createVerticalStrut(14));

        // Table Card
        JPanel tableCard = new JPanel(new BorderLayout(0, 10));
        tableCard.setBackground(Color.WHITE);
        tableCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(16, 18, 16, 18)
        ));

        JPanel tableHeaderBar = new JPanel(new BorderLayout());
        tableHeaderBar.setOpaque(false);

        JLabel tblTitle = new JLabel("Cancellation Records & Audit Trail");
        tblTitle.setFont(AeroFonts.CAPTION_BOLD);
        tblTitle.setForeground(AeroColors.TEXT_MAIN);

        recordCountLabel = new JLabel("Showing 0 cancellations");
        recordCountLabel.setFont(AeroFonts.CAPTION);
        recordCountLabel.setForeground(AeroColors.TEXT_MUTED);

        tableHeaderBar.add(tblTitle, BorderLayout.WEST);
        tableHeaderBar.add(recordCountLabel, BorderLayout.EAST);
        tableCard.add(tableHeaderBar, BorderLayout.NORTH);

        cancellationTableModel = new CancellationTableModel();
        cancellationTable = new StyledTable(cancellationTableModel);

        // 11 Columns per specification
        cancellationTable.getColumnModel().getColumn(0).setPreferredWidth(85);  // Cancellation / ID
        cancellationTable.getColumnModel().getColumn(1).setPreferredWidth(90);  // PNR
        cancellationTable.getColumnModel().getColumn(2).setPreferredWidth(130); // Passenger
        cancellationTable.getColumnModel().getColumn(3).setPreferredWidth(90);  // Flight
        cancellationTable.getColumnModel().getColumn(4).setPreferredWidth(140); // Route
        cancellationTable.getColumnModel().getColumn(5).setPreferredWidth(125); // Cancellation Date
        cancellationTable.getColumnModel().getColumn(6).setPreferredWidth(100); // Reservation Fare
        cancellationTable.getColumnModel().getColumn(7).setPreferredWidth(105); // Payment Amount
        cancellationTable.getColumnModel().getColumn(8).setPreferredWidth(100); // Refund Amount
        cancellationTable.getColumnModel().getColumn(9).setPreferredWidth(150); // Cancellation Reason
        cancellationTable.getColumnModel().getColumn(10).setPreferredWidth(105); // Status

        // Custom Cell Renderer for Status Badge (Column 10)
        cancellationTable.getColumnModel().getColumn(10).setCellRenderer(new TableCellRenderer() {
            private final StatusBadge badge = new StatusBadge("");

            @Override
            public Component getTableCellRendererComponent(javax.swing.JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
                badge.setStatus(value != null ? value.toString() : "");
                JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 4));
                p.setOpaque(true);
                p.setBackground(isSelected ? table.getSelectionBackground() : (row % 2 == 0 ? Color.WHITE : AeroColors.TABLE_ROW_ALT));
                p.add(badge);
                return p;
            }
        });

        // Row Selection Listener
        cancellationTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = cancellationTable.getSelectedRow();
                if (row >= 0 && row < cancellationList.size()) {
                    selectCancellation(cancellationList.get(row));
                } else {
                    clearSelection();
                }
            }
        });

        // Double Click row to view details
        cancellationTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && selectedCancellation != null) {
                    showCancellationDetailsModal(selectedCancellation);
                }
            }
        });

        JScrollPane tableScroll = new JScrollPane(cancellationTable);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        tableScroll.getViewport().setBackground(Color.WHITE);
        tableScroll.setPreferredSize(new Dimension(750, 240));

        JPanel emptyStatePanel = createEmptyTableStatePanel();
        tableContainer.add(tableScroll, "TABLE");
        tableContainer.add(emptyStatePanel, "EMPTY");
        tableCardLayout.show(tableContainer, "EMPTY");
        tableCard.add(tableContainer, BorderLayout.CENTER);

        // Selection Action Bottom Bar
        JPanel bottomBar = new JPanel(new BorderLayout(14, 0));
        bottomBar.setOpaque(false);
        bottomBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, AeroColors.BORDER),
            new EmptyBorder(12, 0, 0, 0)
        ));

        selectedSummaryLabel = new JLabel("Click any historical cancellation row above to inspect details or linked records.");
        selectedSummaryLabel.setFont(AeroFonts.CAPTION);
        selectedSummaryLabel.setForeground(AeroColors.TEXT_SECONDARY);
        bottomBar.add(selectedSummaryLabel, BorderLayout.CENTER);

        JPanel actionBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actionBtns.setOpaque(false);

        viewDetailsBtn = new ModernButton("View Details", ModernButton.Style.PRIMARY);
        viewDetailsBtn.setPreferredSize(new Dimension(115, 36));
        viewDetailsBtn.setEnabled(false);
        viewDetailsBtn.addActionListener(e -> {
            if (selectedCancellation != null) showCancellationDetailsModal(selectedCancellation);
        });

        viewTripBtn = new ModernButton("View Trip", ModernButton.Style.SECONDARY);
        viewTripBtn.setPreferredSize(new Dimension(100, 36));
        viewTripBtn.setEnabled(false);
        viewTripBtn.addActionListener(e -> {
            if (selectedCancellation != null && navigationController != null) {
                navigationController.navigateTo(NavRoute.TRIPS, selectedCancellation.pnr());
            }
        });

        viewPassengerBtn = new ModernButton("View Passenger", ModernButton.Style.SECONDARY);
        viewPassengerBtn.setPreferredSize(new Dimension(130, 36));
        viewPassengerBtn.setEnabled(false);
        viewPassengerBtn.addActionListener(e -> {
            if (selectedCancellation != null && navigationController != null) {
                navigationController.navigateTo(NavRoute.PASSENGERS, selectedCancellation.passengerName());
            }
        });

        viewFlightBtn = new ModernButton("View Flight", ModernButton.Style.SECONDARY);
        viewFlightBtn.setPreferredSize(new Dimension(105, 36));
        viewFlightBtn.setEnabled(false);
        viewFlightBtn.addActionListener(e -> {
            if (selectedCancellation != null && navigationController != null) {
                navigationController.navigateTo(NavRoute.FLIGHTS, selectedCancellation.flightNumber());
            }
        });

        actionBtns.add(viewDetailsBtn);
        actionBtns.add(viewTripBtn);
        actionBtns.add(viewPassengerBtn);
        actionBtns.add(viewFlightBtn);
        bottomBar.add(actionBtns, BorderLayout.EAST);

        tableCard.add(bottomBar, BorderLayout.SOUTH);
        mainContent.add(tableCard);

        add(new AeroScrollPane(mainContent), BorderLayout.CENTER);

        // ----------------------------------------------------
        // DEEP LINKING NAVIGATION LISTENER
        // ----------------------------------------------------
        if (controller != null) {
            controller.addListener((route, param) -> {
                if (route == NavRoute.CANCELLATIONS) {
                    if (param instanceof String pnr && !pnr.isBlank()) {
                        pnrLookupField.setText(pnr.trim());
                        lookupBookingAsync(pnr.trim());
                    }
                    refreshDataAsync();
                }
            });
        }

        // Initial Data Load
        refreshDataAsync();
    }

    // ==========================================
    // UI SUB-COMPONENTS FOR LOOKUP CONSOLE
    // ==========================================
    private JPanel createEmptyLookupPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(new Color(0xF8, 0xFA, 0xFC));
        p.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createDashedBorder(AeroColors.BORDER, 3, 3),
            new EmptyBorder(28, 20, 28, 20)
        ));

        JLabel title = new JLabel("No Booking Loaded for Cancellation");
        title.setFont(AeroFonts.BODY_BOLD);
        title.setForeground(AeroColors.TEXT_SECONDARY);
        title.setAlignmentX(CENTER_ALIGNMENT);

        JLabel subtitle = new JLabel("Enter a reservation PNR above and click 'Find Booking' to inspect details and release seat.");
        subtitle.setFont(AeroFonts.CAPTION);
        subtitle.setForeground(AeroColors.TEXT_MUTED);
        subtitle.setAlignmentX(CENTER_ALIGNMENT);

        p.add(title);
        p.add(Box.createVerticalStrut(4));
        p.add(subtitle);
        return p;
    }

    private JPanel createDetailsLookupPanel() {
        JPanel container = new JPanel(new GridLayout(1, 4, 12, 0));
        container.setOpaque(false);

        // Column 1: Reservation Info
        JPanel resCol = createInfoCard("RESERVATION");
        JPanel rGrid = new JPanel(new GridLayout(4, 1, 0, 4));
        rGrid.setOpaque(false);
        addFieldRow(rGrid, "PNR:", resPnrLabel);
        addFieldRow(rGrid, "Date:", resDateLabel);
        addFieldRow(rGrid, "Status:", resStatusBadge);
        addFieldRow(rGrid, "Seat Number:", resSeatLabel);
        resCol.add(rGrid);

        // Column 2: Passenger Info
        JPanel passCol = createInfoCard("PASSENGER");
        JPanel pGrid = new JPanel(new GridLayout(3, 1, 0, 4));
        pGrid.setOpaque(false);
        addFieldRow(pGrid, "Name:", passNameLabel);
        addFieldRow(pGrid, "Phone:", passPhoneLabel);
        addFieldRow(pGrid, "Email:", passEmailLabel);
        passCol.add(pGrid);

        // Column 3: Flight Info
        JPanel flightCol = createInfoCard("FLIGHT");
        JPanel fGrid = new JPanel(new GridLayout(3, 1, 0, 4));
        fGrid.setOpaque(false);
        addFieldRow(fGrid, "Flight:", flightNoLabel);
        addFieldRow(fGrid, "Route:", flightRouteLabel);
        addFieldRow(fGrid, "Departure:", flightDepLabel);
        flightCol.add(fGrid);

        // Column 4: Payment & Fare (Strict Conceptual Separation)
        JPanel pmtCol = createInfoCard("PAYMENT & FARE");
        JPanel payGrid = new JPanel(new GridLayout(4, 1, 0, 4));
        payGrid.setOpaque(false);
        addFieldRow(payGrid, "Reservation Fare:", resFareLabel);
        addFieldRow(payGrid, "Payment Amount:", pmtAmountLabel);
        addFieldRow(payGrid, "Payment Status:", pmtStatusBadge);
        addFieldRow(payGrid, "Payment Ref:", pmtRefLabel);
        pmtCol.add(payGrid);

        container.add(resCol);
        container.add(passCol);
        container.add(flightCol);
        container.add(pmtCol);
        return container;
    }

    private JPanel createInfoCard(String title) {
        JPanel c = new JPanel(new BorderLayout(0, 8));
        c.setBackground(new Color(0xF8, 0xFA, 0xFC));
        c.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(10, 12, 10, 12)
        ));

        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(AeroFonts.MUTED);
        titleLbl.setForeground(AeroColors.TEXT_MUTED);
        c.add(titleLbl, BorderLayout.NORTH);
        return c;
    }

    private void addFieldRow(JPanel container, String label, Component valComp) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);

        JLabel l = new JLabel(label);
        l.setFont(AeroFonts.CAPTION);
        l.setForeground(AeroColors.TEXT_MUTED);
        l.setPreferredSize(new Dimension(105, 20));

        if (valComp instanceof JLabel lbl) {
            lbl.setFont(AeroFonts.CAPTION_BOLD);
            lbl.setForeground(AeroColors.TEXT_MAIN);
        }

        row.add(l, BorderLayout.WEST);
        row.add(valComp, BorderLayout.CENTER);
        container.add(row);
    }

    private JPanel createEmptyTableStatePanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Color.WHITE);
        p.setBorder(new EmptyBorder(40, 20, 40, 20));

        JLabel t = new JLabel("No cancellation records");
        t.setFont(AeroFonts.BODY_BOLD);
        t.setForeground(AeroColors.TEXT_SECONDARY);
        t.setAlignmentX(CENTER_ALIGNMENT);

        JLabel c = new JLabel("Cancelled trips and refund information will appear here when real cancellation records exist.");
        c.setFont(AeroFonts.CAPTION);
        c.setForeground(AeroColors.TEXT_MUTED);
        c.setAlignmentX(CENTER_ALIGNMENT);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        btnRow.setOpaque(false);

        ModernButton resetBtn = new ModernButton("Reset Filters", ModernButton.Style.SECONDARY);
        resetBtn.setPreferredSize(new Dimension(110, 32));
        resetBtn.addActionListener(e -> resetFilters());

        ModernButton bookBtn = new ModernButton("+ Book Ticket", ModernButton.Style.PRIMARY);
        bookBtn.setPreferredSize(new Dimension(120, 32));
        bookBtn.addActionListener(e -> {
            if (navigationController != null) navigationController.navigateTo(NavRoute.BOOKING);
        });

        btnRow.add(resetBtn);
        btnRow.add(bookBtn);

        p.add(Box.createVerticalGlue());
        p.add(t);
        p.add(Box.createVerticalStrut(4));
        p.add(c);
        p.add(Box.createVerticalStrut(14));
        p.add(btnRow);
        p.add(Box.createVerticalGlue());
        return p;
    }

    // ==========================================
    // SELECTION & ACTIONS
    // ==========================================
    private void selectCancellation(CancellationRecord rec) {
        this.selectedCancellation = rec;
        viewDetailsBtn.setEnabled(true);
        viewTripBtn.setEnabled(rec.pnr() != null && !rec.pnr().isBlank());
        viewPassengerBtn.setEnabled(rec.passengerName() != null && !rec.passengerName().isBlank());
        viewFlightBtn.setEnabled(rec.flightNumber() != null && !rec.flightNumber().isBlank());

        selectedSummaryLabel.setText(String.format(
            "Selected: Cancel ID #%d | PNR: %s | Passenger: %s | Fare: ₹%,.2f | Paid: ₹%,.2f | Refund: ₹%,.2f | Status: %s",
            rec.cancelId(),
            rec.pnr() != null ? rec.pnr() : "—",
            rec.passengerName() != null ? rec.passengerName() : "—",
            rec.reservationFare(),
            rec.paymentAmount() != null ? rec.paymentAmount() : 0.0,
            rec.refundAmount(),
            rec.status()
        ));
        selectedSummaryLabel.setForeground(AeroColors.TEXT_MAIN);
    }

    private void clearSelection() {
        this.selectedCancellation = null;
        viewDetailsBtn.setEnabled(false);
        viewTripBtn.setEnabled(false);
        viewPassengerBtn.setEnabled(false);
        viewFlightBtn.setEnabled(false);
        selectedSummaryLabel.setText("Click any historical cancellation row above to inspect details or linked records.");
        selectedSummaryLabel.setForeground(AeroColors.TEXT_SECONDARY);
    }

    private void resetFilters() {
        filterSearchField.setText("");
        filterStatusCombo.setSelectedIndex(0);
        loadCancellationsAsync();
    }

    private void performBookingLookup() {
        String pnr = pnrLookupField.getText().trim();
        if (pnr.isEmpty()) {
            notificationBanner.showMessage("Please enter a booking PNR to search.", NotificationBanner.Type.WARNING);
            return;
        }
        lookupBookingAsync(pnr);
    }

    private void clearBookingLookup() {
        pnrLookupField.setText("");
        currentLookupResult = null;
        lookupResultCardLayout.show(lookupResultContainer, "EMPTY");
        operationNoticeLabel.setText(" ");
        refundInputField.setText("");
        executeCancelBtn.setEnabled(false);
    }

    // ==========================================
    // ASYNC BOOKING LOOKUP (SwingWorker)
    // ==========================================
    public void lookupBookingAsync(String pnr) {
        findBookingBtn.setEnabled(false);
        findBookingBtn.setText("Searching...");

        SwingWorker<BookingLookupResult, Void> worker = new SwingWorker<>() {
            @Override
            protected BookingLookupResult doInBackground() throws Exception {
                String sql =
                    "SELECT r.id AS res_id, r.pnr, r.seat_number, r.fare, r.status AS res_status, r.booking_date, " +
                    "p.id AS pass_id, p.name AS pass_name, p.phone AS pass_phone, p.email AS pass_email, " +
                    "f.id AS flight_id, f.flight_number, f.airline, f.source, f.destination, f.departure_time, f.arrival_time, f.available_seats, " +
                    "pmt.id AS pmt_id, pmt.payment_status, pmt.payment_method, pmt.transaction_reference, pmt.amount AS pmt_amount, " +
                    "c.reason AS cancel_reason, c.refund_amount AS cancel_refund_amount " +
                    "FROM reservations r " +
                    "JOIN passengers p ON p.id = r.passenger_id " +
                    "JOIN flights f ON f.id = r.flight_id " +
                    "LEFT JOIN payments pmt ON pmt.reservation_id = r.id " +
                    "LEFT JOIN cancellations c ON c.reservation_id = r.id " +
                    "WHERE r.pnr = ?";

                try (Connection conn = DBConnection.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, pnr);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return new BookingLookupResult(
                                rs.getLong("res_id"),
                                rs.getString("pnr"),
                                rs.getString("seat_number"),
                                rs.getDouble("fare"),
                                rs.getString("res_status"),
                                rs.getTimestamp("booking_date"),
                                rs.getLong("pass_id"),
                                rs.getString("pass_name"),
                                rs.getString("pass_phone"),
                                rs.getString("pass_email"),
                                rs.getLong("flight_id"),
                                rs.getString("flight_number"),
                                rs.getString("airline"),
                                rs.getString("source"),
                                rs.getString("destination"),
                                rs.getTimestamp("departure_time"),
                                rs.getTimestamp("arrival_time"),
                                rs.getInt("available_seats"),
                                rs.getString("transaction_reference"),
                                rs.getDouble("pmt_amount"),
                                rs.getString("payment_method"),
                                rs.getString("payment_status"),
                                rs.getString("cancel_reason"),
                                rs.getObject("cancel_refund_amount") != null ? rs.getDouble("cancel_refund_amount") : null
                            );
                        }
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                findBookingBtn.setEnabled(true);
                findBookingBtn.setText("Find Booking");

                try {
                    BookingLookupResult res = get();
                    if (res == null) {
                        currentLookupResult = null;
                        lookupResultCardLayout.show(lookupResultContainer, "EMPTY");
                        executeCancelBtn.setEnabled(false);
                        operationNoticeLabel.setText(" ");
                        notificationBanner.showMessage("No reservation found matching PNR: " + pnr, NotificationBanner.Type.ERROR);
                    } else {
                        displayBookingLookupResult(res);
                    }
                } catch (Exception ex) {
                    notificationBanner.showMessage("Error searching reservation: " + ex.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    private void displayBookingLookupResult(BookingLookupResult res) {
        this.currentLookupResult = res;

        resPnrLabel.setText(res.pnr());
        resDateLabel.setText(res.bookingDate() != null ? res.bookingDate().toLocalDateTime().format(DISPLAY_FORMAT) : "—");
        resStatusBadge.setStatus(res.reservationStatus());
        resSeatLabel.setText(res.seatNumber() != null ? res.seatNumber() : "—");
        resFareLabel.setText("₹" + String.format("%,.2f", res.fare()));

        passNameLabel.setText(res.passengerName());
        passPhoneLabel.setText(res.passengerPhone() != null ? res.passengerPhone() : "—");
        passEmailLabel.setText(res.passengerEmail() != null && !res.passengerEmail().isBlank() ? res.passengerEmail() : "—");

        flightNoLabel.setText(res.flightNumber() + " (" + res.airline() + ")");
        flightRouteLabel.setText(res.source() + " → " + res.destination());
        flightDepLabel.setText(res.departureTime() != null ? res.departureTime().toLocalDateTime().format(DISPLAY_FORMAT) : "—");

        pmtAmountLabel.setText(res.paymentAmount() != null ? ("₹" + String.format("%,.2f", res.paymentAmount())) : "—");
        pmtStatusBadge.setStatus(res.paymentStatus() != null ? res.paymentStatus() : "PENDING");
        pmtMethodLabel.setText(res.paymentMethod() != null ? res.paymentMethod() : "—");
        pmtRefLabel.setText(res.paymentRef() != null ? res.paymentRef() : "—");

        lookupResultCardLayout.show(lookupResultContainer, "DETAILS");

        boolean isConfirmed = "CONFIRMED".equalsIgnoreCase(res.reservationStatus());
        executeCancelBtn.setEnabled(isConfirmed);

        if (isConfirmed) {
            if (res.paymentAmount() != null) {
                refundInputField.setText(String.format("%.2f", res.paymentAmount()));
            } else if (res.fare() != null) {
                refundInputField.setText(String.format("%.2f", res.fare()));
            } else {
                refundInputField.setText("");
            }
            String seatInfo = res.seatNumber() != null ? "seat " + res.seatNumber() : "allocated seat";
            operationNoticeLabel.setText(String.format(
                "Proceeding will atomically cancel this booking, release %s on flight %s, and mark payment as REFUNDED.",
                seatInfo, res.flightNumber()
            ));
            operationNoticeLabel.setForeground(new Color(0xB4, 0x53, 0x09));
            notificationBanner.showMessage("Confirmed booking loaded for PNR: " + res.pnr() + ". Ready for cancellation.", NotificationBanner.Type.INFO);
        } else {
            refundInputField.setText("");
            if ("CANCELLED".equalsIgnoreCase(res.reservationStatus())) {
                operationNoticeLabel.setText(String.format(
                    "Booking status is CANCELLED. Persisted Reason: \"%s\" | Refund: ₹%,.2f",
                    (res.cancelReason() != null && !res.cancelReason().isBlank()) ? res.cancelReason() : "—",
                    res.refundAmount() != null ? res.refundAmount() : 0.0
                ));
            } else {
                operationNoticeLabel.setText(String.format(
                    "ℹ️ Booking status is %s. Only CONFIRMED bookings can be cancelled.",
                    res.reservationStatus()
                ));
            }
            operationNoticeLabel.setForeground(AeroColors.TEXT_MUTED);
            notificationBanner.showMessage("Booking PNR " + res.pnr() + " is already " + res.reservationStatus() + ".", NotificationBanner.Type.WARNING);
        }
    }

    // ==========================================
    // EXECUTE CANCELLATION ACTION
    // ==========================================
    private void handleCancelBookingAction() {
        if (currentLookupResult == null) {
            notificationBanner.showMessage("No booking selected for cancellation.", NotificationBanner.Type.WARNING);
            return;
        }

        if (!"CONFIRMED".equalsIgnoreCase(currentLookupResult.reservationStatus())) {
            notificationBanner.showMessage("Only CONFIRMED bookings can be cancelled.", NotificationBanner.Type.WARNING);
            return;
        }

        final String pnr = currentLookupResult.pnr();
        final String passenger = currentLookupResult.passengerName();
        final String flight = currentLookupResult.flightNumber();
        final String seat = currentLookupResult.seatNumber() != null ? currentLookupResult.seatNumber() : "—";
        final Object reasonObj = cancelReasonCombo.getSelectedItem();
        final String rawReason = reasonObj != null ? reasonObj.toString() : "";
        if (rawReason == null || rawReason.trim().isEmpty()) {
            notificationBanner.showMessage("Please select or enter a valid cancellation reason.", NotificationBanner.Type.WARNING);
            return;
        }
        final String reason = rawReason.trim();

        // Check user-specified or authoritative refund input
        java.math.BigDecimal inputRefund = null;
        String rawRefund = refundInputField.getText().trim();
        if (!rawRefund.isEmpty() && !"auto".equalsIgnoreCase(rawRefund)) {
            try {
                inputRefund = new java.math.BigDecimal(rawRefund.replace("₹", "").replace(",", "").trim());
                if (inputRefund.compareTo(java.math.BigDecimal.ZERO) < 0) {
                    notificationBanner.showMessage("Refund amount cannot be negative.", NotificationBanner.Type.WARNING);
                    return;
                }
            } catch (NumberFormatException nfe) {
                notificationBanner.showMessage("Please enter a valid numeric refund amount or leave as 'Auto'.", NotificationBanner.Type.WARNING);
                return;
            }
        }
        final java.math.BigDecimal explicitRefund = inputRefund;

        // Check whether authoritative completed payment exists
        boolean hasCompletedPayment = currentLookupResult.paymentStatus() != null &&
            ("SUCCESS".equalsIgnoreCase(currentLookupResult.paymentStatus()) || "COMPLETED".equalsIgnoreCase(currentLookupResult.paymentStatus()));
        String refundText = explicitRefund != null
            ? String.format("₹%,.2f (Specified amount)", explicitRefund)
            : ((hasCompletedPayment && currentLookupResult.paymentAmount() != null)
                ? String.format("₹%,.2f (From completed payment record)", currentLookupResult.paymentAmount())
                : "Refund will be determined authoritatively.");

        int confirmChoice = JOptionPane.showConfirmDialog(
            this,
            String.format(
                "Are you sure you want to cancel the following reservation?\n\n" +
                "  • PNR: %s\n" +
                "  • Passenger: %s\n" +
                "  • Flight: %s\n" +
                "  • Seat: %s (Will be released to inventory)\n" +
                "  • Reason: %s\n" +
                "  • Refund: %s\n\n" +
                "This operation cannot be undone. Flight seat availability will increase by 1.",
                pnr, passenger, flight, seat, reason, refundText
            ),
            "Confirm Ticket Cancellation",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );

        if (confirmChoice != JOptionPane.YES_OPTION) {
            return;
        }

        executeCancelBtn.setEnabled(false);
        executeCancelBtn.setText("Cancelling...");

        SwingWorker<java.math.BigDecimal, Void> worker = new SwingWorker<>() {
            @Override
            protected java.math.BigDecimal doInBackground() throws Exception {
                // Authoritative transactional cancellation via ReservationService with user-selected reason and refund
                return ReservationService.cancel(pnr, reason, explicitRefund);
            }

            @Override
            protected void done() {
                executeCancelBtn.setText("Cancel Ticket & Release Seat");
                try {
                    java.math.BigDecimal actualRefund = get();
                    notificationBanner.showMessage(
                        String.format("Successfully cancelled booking %s. Seat %s released back to flight %s. Authoritative Refund: ₹%,.2f (Reason: %s).",
                            pnr, seat, flight, actualRefund != null ? actualRefund.doubleValue() : 0.0, reason),
                        NotificationBanner.Type.SUCCESS
                    );

                    // Re-fetch booking details to display updated CANCELLED state and persisted reason
                    lookupBookingAsync(pnr);

                    // Refresh KPIs and historical cancellations table
                    refreshDataAsync();

                } catch (Exception ex) {
                    executeCancelBtn.setEnabled(true);
                    String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                    notificationBanner.showMessage("Cancellation failed: " + msg, NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    // ==========================================
    // ASYNC DATA LOADING (SwingWorker)
    // ==========================================
    public void refreshDataAsync() {
        loadKpisAsync();
        loadCancellationsAsync();
    }

    private void loadKpisAsync() {
        SwingWorker<Object[], Void> worker = new SwingWorker<>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                int cancellationsCount = 0;
                int totalRefunds = 0;
                double refundedAmount = 0.0;
                int cancelledTrips = 0;

                String cancelSql =
                    "SELECT COUNT(*) AS total_cancels, " +
                    "COUNT(CASE WHEN refund_amount > 0 OR status IN ('COMPLETED', 'REFUNDED') THEN 1 END) AS total_refunds, " +
                    "COALESCE(SUM(refund_amount), 0) AS total_refunded " +
                    "FROM cancellations";

                try (Connection conn = DBConnection.getConnection();
                     PreparedStatement ps = conn.prepareStatement(cancelSql);
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        cancellationsCount = rs.getInt("total_cancels");
                        totalRefunds = rs.getInt("total_refunds");
                        refundedAmount = rs.getDouble("total_refunded");
                    }
                }

                String tripsSql = "SELECT COUNT(*) AS cancelled_res FROM reservations WHERE status = 'CANCELLED'";
                try (Connection conn = DBConnection.getConnection();
                     PreparedStatement ps = conn.prepareStatement(tripsSql);
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        cancelledTrips = rs.getInt("cancelled_res");
                    }
                }

                return new Object[]{cancellationsCount, totalRefunds, refundedAmount, cancelledTrips};
            }

            @Override
            protected void done() {
                try {
                    Object[] res = get();
                    int totalCancels = (int) res[0];
                    int totalRefunds = (int) res[1];
                    double refunded = (double) res[2];
                    int cancelledTrips = (int) res[3];

                    totalCancellationsCard.setValue(String.valueOf(totalCancels));
                    totalRefundsCard.setValue(String.valueOf(totalRefunds));
                    refundedAmountCard.setValue("₹" + String.format("%,.2f", refunded));
                    cancelledTripsCard.setValue(String.valueOf(cancelledTrips));
                } catch (Exception ignored) {
                }
            }
        };
        worker.execute();
    }

    private void loadCancellationsAsync() {
        refreshBtn.setEnabled(false);
        refreshBtn.setText("Loading...");

        final String search = filterSearchField.getText().trim();
        final String statusVal = (String) filterStatusCombo.getSelectedItem();

        SwingWorker<List<CancellationRecord>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<CancellationRecord> doInBackground() throws Exception {
                List<CancellationRecord> list = new ArrayList<>();
                StringBuilder sql = new StringBuilder(
                    "SELECT c.id AS cancel_id, c.cancellation_date, c.reason, c.refund_amount, c.status AS cancel_status, " +
                    "r.id AS res_id, r.pnr, r.seat_number, r.fare AS res_fare, r.status AS res_status, r.booking_date, " +
                    "p.id AS pass_id, p.name AS pass_name, p.phone AS pass_phone, p.email AS pass_email, " +
                    "f.id AS flight_id, f.flight_number, f.airline, f.source, f.destination, f.departure_time, f.arrival_time, " +
                    "pmt.id AS pmt_id, pmt.transaction_reference, pmt.payment_method, pmt.payment_status, pmt.amount AS pmt_amount " +
                    "FROM cancellations c " +
                    "JOIN reservations r ON r.id = c.reservation_id " +
                    "JOIN passengers p ON p.id = r.passenger_id " +
                    "JOIN flights f ON f.id = r.flight_id " +
                    "LEFT JOIN payments pmt ON pmt.reservation_id = r.id " +
                    "WHERE 1=1"
                );
                List<Object> params = new ArrayList<>();

                if (!search.isEmpty()) {
                    sql.append(" AND (r.pnr LIKE ? OR p.name LIKE ? OR p.phone LIKE ? OR f.flight_number LIKE ? OR c.reason LIKE ? OR CAST(c.id AS CHAR) LIKE ?)");
                    String like = "%" + search + "%";
                    for (int i = 0; i < 6; i++) params.add(like);
                }

                if (statusVal != null && !statusVal.startsWith("All")) {
                    sql.append(" AND c.status = ?");
                    params.add(statusVal);
                }

                sql.append(" ORDER BY c.id DESC");

                try (Connection conn = DBConnection.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            list.add(new CancellationRecord(
                                rs.getLong("cancel_id"),
                                rs.getTimestamp("cancellation_date"),
                                rs.getString("pnr"),
                                rs.getString("pass_name"),
                                rs.getString("pass_phone"),
                                rs.getString("pass_email"),
                                rs.getLong("pass_id"),
                                rs.getString("flight_number"),
                                rs.getString("airline"),
                                rs.getString("source"),
                                rs.getString("destination"),
                                rs.getTimestamp("departure_time"),
                                rs.getTimestamp("arrival_time"),
                                rs.getString("seat_number"),
                                rs.getDouble("refund_amount"),
                                rs.getDouble("res_fare"),
                                rs.getDouble("pmt_amount"),
                                rs.getString("reason"),
                                rs.getString("cancel_status"),
                                rs.getString("transaction_reference"),
                                rs.getString("payment_method"),
                                rs.getString("payment_status"),
                                rs.getString("res_status"),
                                rs.getTimestamp("booking_date")
                            ));
                        }
                    }
                }
                return list;
            }

            @Override
            protected void done() {
                refreshBtn.setEnabled(true);
                refreshBtn.setText("↻ Refresh");

                try {
                    List<CancellationRecord> results = get();
                    cancellationList.clear();
                    cancellationList.addAll(results);
                    cancellationTableModel.fireTableDataChanged();

                    recordCountLabel.setText("Showing " + results.size() + " cancellations");

                    if (results.isEmpty()) {
                        tableCardLayout.show(tableContainer, "EMPTY");
                    } else {
                        tableCardLayout.show(tableContainer, "TABLE");
                    }
                    clearSelection();

                } catch (Exception ex) {
                    notificationBanner.showMessage("Error loading cancellations: " + ex.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    // ==========================================
    // CANCELLATION DETAILS MODAL (ORGANIZED SECTIONS)
    // ==========================================
    private void showCancellationDetailsModal(CancellationRecord rec) {
        Component parent = SwingUtilities.getWindowAncestor(this);
        JDialog modal = new JDialog(parent instanceof java.awt.Frame ? (java.awt.Frame) parent : null, "Cancellation Audit Details — #" + rec.cancelId(), true);
        modal.setLayout(new BorderLayout());
        modal.setBackground(AeroColors.BACKGROUND);
        modal.setMinimumSize(new Dimension(560, 560));
        modal.setLocationRelativeTo(parent);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(AeroColors.BACKGROUND);
        content.setBorder(new EmptyBorder(20, 24, 20, 24));

        // 1. CANCELLATION SECTION
        JPanel cancelCard = createSectionCard("CANCELLATION");
        JPanel cGrid = new JPanel(new GridLayout(2, 2, 12, 8));
        cGrid.setOpaque(false);
        addDetailRow(cGrid, "Cancellation ID", "#" + rec.cancelId());
        addDetailRow(cGrid, "Cancellation Date", rec.cancellationDate() != null ? rec.cancellationDate().toLocalDateTime().format(DISPLAY_FORMAT) : "—");
        addDetailRow(cGrid, "Cancellation Reason", rec.reason() != null ? rec.reason() : "—");
        addDetailRow(cGrid, "Cancellation Status", rec.status());
        cancelCard.add(cGrid);
        content.add(cancelCard);
        content.add(Box.createVerticalStrut(12));

        // 2. TRIP / RESERVATION SECTION
        JPanel resCard = createSectionCard("TRIP / RESERVATION");
        JPanel rGrid = new JPanel(new GridLayout(3, 2, 12, 8));
        rGrid.setOpaque(false);
        addDetailRow(rGrid, "PNR", rec.pnr());
        addDetailRow(rGrid, "Reservation Status", rec.reservationStatus());
        addDetailRow(rGrid, "Reservation Fare", "₹" + String.format("%,.2f", rec.reservationFare()));
        addDetailRow(rGrid, "Booking Date", rec.bookingDate() != null ? rec.bookingDate().toLocalDateTime().format(DISPLAY_FORMAT) : "—");
        addDetailRow(rGrid, "Seat Number", rec.seatNumber() != null ? rec.seatNumber() : "—");
        rGrid.add(new JLabel()); // placeholder
        resCard.add(rGrid);
        content.add(resCard);
        content.add(Box.createVerticalStrut(12));

        // 3. PAYMENT SECTION
        JPanel pmtCard = createSectionCard("PAYMENT");
        JPanel pGrid = new JPanel(new GridLayout(2, 2, 12, 8));
        pGrid.setOpaque(false);
        addDetailRow(pGrid, "Payment Reference", rec.paymentRef() != null ? rec.paymentRef() : "—");
        addDetailRow(pGrid, "Payment Method", rec.paymentMethod() != null ? rec.paymentMethod() : "—");
        addDetailRow(pGrid, "Payment Status", rec.paymentStatus() != null ? rec.paymentStatus() : "REFUNDED");
        addDetailRow(pGrid, "Payment Amount", rec.paymentAmount() != null ? ("₹" + String.format("%,.2f", rec.paymentAmount())) : "—");
        pmtCard.add(pGrid);
        content.add(pmtCard);
        content.add(Box.createVerticalStrut(12));

        // 4. REFUND SECTION
        JPanel refCard = createSectionCard("REFUND");
        JPanel refGrid = new JPanel(new GridLayout(2, 2, 12, 8));
        refGrid.setOpaque(false);
        addDetailRow(refGrid, "Refund Amount", "₹" + String.format("%,.2f", rec.refundAmount()));
        addDetailRow(refGrid, "Refund Date", rec.cancellationDate() != null ? rec.cancellationDate().toLocalDateTime().format(DISPLAY_FORMAT) : "—");
        addDetailRow(refGrid, "Refund Status", rec.status());
        refGrid.add(new JLabel());
        refCard.add(refGrid);
        content.add(refCard);
        content.add(Box.createVerticalStrut(12));

        // 5. PASSENGER SECTION
        JPanel passCard = createSectionCard("PASSENGER");
        JPanel passGrid = new JPanel(new GridLayout(2, 2, 12, 8));
        passGrid.setOpaque(false);
        addDetailRow(passGrid, "Passenger Name", rec.passengerName());
        addDetailRow(passGrid, "Passenger ID", "#" + rec.passengerId());
        addDetailRow(passGrid, "Phone", rec.passengerPhone() != null ? rec.passengerPhone() : "—");
        addDetailRow(passGrid, "Email", rec.passengerEmail() != null && !rec.passengerEmail().isBlank() ? rec.passengerEmail() : "—");
        passCard.add(passGrid);
        content.add(passCard);
        content.add(Box.createVerticalStrut(12));

        // 6. FLIGHT SECTION
        JPanel flightCard = createSectionCard("FLIGHT");
        JPanel fGrid = new JPanel(new GridLayout(3, 2, 12, 8));
        fGrid.setOpaque(false);
        addDetailRow(fGrid, "Flight Number", rec.flightNumber());
        addDetailRow(fGrid, "Airline", rec.airline());
        addDetailRow(fGrid, "Origin", rec.source());
        addDetailRow(fGrid, "Destination", rec.destination());
        addDetailRow(fGrid, "Departure", rec.departureTime() != null ? rec.departureTime().toLocalDateTime().format(DISPLAY_FORMAT) : "—");
        addDetailRow(fGrid, "Arrival", rec.arrivalTime() != null ? rec.arrivalTime().toLocalDateTime().format(DISPLAY_FORMAT) : "—");
        flightCard.add(fGrid);
        content.add(flightCard);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(AeroColors.BACKGROUND);
        modal.add(scroll, BorderLayout.CENTER);

        // Close Button Bar
        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 12));
        btnBar.setBackground(Color.WHITE);
        btnBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, AeroColors.BORDER));

        ModernButton closeBtn = new ModernButton("Close", ModernButton.Style.SECONDARY);
        closeBtn.setPreferredSize(new Dimension(90, AeroConstants.BUTTON_HEIGHT));
        closeBtn.addActionListener(e -> modal.dispose());
        btnBar.add(closeBtn);

        modal.add(btnBar, BorderLayout.SOUTH);
        modal.pack();
        modal.setVisible(true);
    }

    private JPanel createSectionCard(String title) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(12, 14, 12, 14)
        ));

        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(AeroFonts.MUTED);
        titleLbl.setForeground(AeroColors.TEXT_MUTED);
        card.add(titleLbl);
        card.add(Box.createVerticalStrut(8));
        return card;
    }

    private void addDetailRow(JPanel grid, String label, String value) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);

        JLabel l = new JLabel(label);
        l.setFont(AeroFonts.CAPTION);
        l.setForeground(AeroColors.TEXT_MUTED);

        JLabel v = new JLabel(value != null ? value : "—");
        v.setFont(AeroFonts.CAPTION_BOLD);
        v.setForeground(AeroColors.TEXT_MAIN);

        row.add(l, BorderLayout.NORTH);
        row.add(v, BorderLayout.SOUTH);
        grid.add(row);
    }

    // ==========================================
    // TABLE MODEL FOR HISTORICAL CANCELLATIONS (11 COLUMNS)
    // ==========================================
    private class CancellationTableModel extends AbstractTableModel {
        private final String[] columns = {
            "Cancellation / ID",
            "PNR",
            "Passenger",
            "Flight",
            "Route",
            "Cancellation Date",
            "Reservation Fare",
            "Payment Amount",
            "Refund Amount",
            "Cancellation Reason",
            "Status"
        };

        @Override public int getRowCount() { return cancellationList.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            if (row < 0 || row >= cancellationList.size()) return null;
            CancellationRecord r = cancellationList.get(row);
            return switch (col) {
                case 0 -> "#" + r.cancelId();
                case 1 -> r.pnr() != null ? r.pnr() : "—";
                case 2 -> r.passengerName() != null ? r.passengerName() : "—";
                case 3 -> r.flightNumber() != null ? r.flightNumber() : "—";
                case 4 -> (r.source() != null ? r.source() : "—") + " → " + (r.destination() != null ? r.destination() : "—");
                case 5 -> r.cancellationDate() != null ? r.cancellationDate().toLocalDateTime().format(DISPLAY_FORMAT) : "—";
                case 6 -> "₹" + String.format("%,.2f", r.reservationFare());
                case 7 -> r.paymentAmount() != null ? ("₹" + String.format("%,.2f", r.paymentAmount())) : "—";
                case 8 -> r.refundAmount() != null ? ("₹" + String.format("%,.2f", r.refundAmount())) : "—";
                case 9 -> r.reason() != null ? r.reason() : "—";
                case 10 -> r.status() != null ? r.status() : "—";
                default -> "";
            };
        }
    }

    // ==========================================
    // IMMUTABLE DATA RECORDS
    // ==========================================
    public record CancellationRecord(
        long cancelId,
        Timestamp cancellationDate,
        String pnr,
        String passengerName,
        String passengerPhone,
        String passengerEmail,
        long passengerId,
        String flightNumber,
        String airline,
        String source,
        String destination,
        Timestamp departureTime,
        Timestamp arrivalTime,
        String seatNumber,
        Double refundAmount,
        Double reservationFare,
        Double paymentAmount,
        String reason,
        String status,
        String paymentRef,
        String paymentMethod,
        String paymentStatus,
        String reservationStatus,
        Timestamp bookingDate
    ) {}

    public record BookingLookupResult(
        long reservationId,
        String pnr,
        String seatNumber,
        Double fare,
        String reservationStatus,
        Timestamp bookingDate,
        long passengerId,
        String passengerName,
        String passengerPhone,
        String passengerEmail,
        long flightId,
        String flightNumber,
        String airline,
        String source,
        String destination,
        Timestamp departureTime,
        Timestamp arrivalTime,
        int availableSeats,
        String paymentRef,
        Double paymentAmount,
        String paymentMethod,
        String paymentStatus,
        String cancelReason,
        Double refundAmount
    ) {}
}
