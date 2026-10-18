package airline.management.system.ui.panels;

import airline.management.system.DBConnection;
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
import airline.management.system.operations.baggage.BaggageRecord;
import airline.management.system.operations.baggage.BaggageService;
import airline.management.system.operations.baggage.BaggageStatus;
import airline.management.system.operations.checkin.BoardingPassRecord;
import airline.management.system.operations.checkin.CheckinPassengerDetails;
import airline.management.system.operations.checkin.CheckinService;
import airline.management.system.operations.checkin.CheckinStatus;
import airline.management.system.operations.gate.FlightGateAssignment;
import airline.management.system.operations.gate.GateService;
import airline.management.system.operations.pir.PIRRecord;
import airline.management.system.operations.pir.PIRService;
import airline.management.system.operations.claim.ClaimCase;
import airline.management.system.operations.claim.ClaimService;
import airline.management.system.operations.irops.DisruptionService;
import airline.management.system.operations.irops.DisruptionVoucher;
import airline.management.system.operations.irops.DisruptionVoucherService;
import airline.management.system.operations.irops.FlightDisruption;
import airline.management.system.operations.irops.PassengerReaccommodation;
import airline.management.system.operations.irops.ReaccommodationEngine;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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

/**
 * AeroDesk Enterprise Trip History & Reservation Management Screen.
 * Primary read-only operations screen consuming authoritative database records.
 * Provides search, multi-faceted filtering, summary KPIs, detailed trip inspection modal,
 * boarding pass view, and seamless navigation to booking, passengers, flights, and cancellations.
 */
public class TripsPanel extends JPanel {
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NavigationController navigationController;
    private final NotificationBanner notificationBanner;

    // KPI Metrics Cards
    private final DashboardCard totalBookingsCard;
    private final DashboardCard confirmedTripsCard;
    private final DashboardCard cancelledTripsCard;
    private final DashboardCard totalRevenueCard;

    // Search and Filter Controls
    private final ModernTextField searchField;
    private final ModernComboBox<String> statusFilter;
    private final ModernComboBox<String> paymentFilter;
    private final ModernButton refreshBtn;
    private final JLabel recordCountLabel;

    // Table & Models
    private final StyledTable tripTable;
    private final TripTableModel tripTableModel;
    private final List<TripRecord> tripList = new ArrayList<>();
    private final CardLayout tableCardLayout = new CardLayout();
    private final JPanel tableContainer = new JPanel(tableCardLayout);

    // Selected Trip Context & Actions
    private TripRecord selectedTrip = null;
    private final JLabel selectedSummaryLabel;
    private final ModernButton viewTripBtn;
    private final ModernButton viewPassengerBtn;
    private final ModernButton viewFlightBtn;
    private final ModernButton cancelTripBtn;
    private final ModernButton boardingPassBtn;

    public TripsPanel(NavigationController controller) {
        this.navigationController = controller;
        setLayout(new BorderLayout());
        setBackground(AeroColors.BACKGROUND);

        JPanel mainContent = new JPanel();
        mainContent.setLayout(new BoxLayout(mainContent, BoxLayout.Y_AXIS));
        mainContent.setBackground(AeroColors.BACKGROUND);
        mainContent.setBorder(new EmptyBorder(24, 28, 28, 28));

        // 1. Top Header Area
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);

        JPanel titleBlock = new JPanel();
        titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
        titleBlock.setOpaque(false);

        JLabel title = new JLabel("TRIP MANAGEMENT");
        title.setFont(AeroFonts.TITLE_PAGE);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel subtitle = new JLabel("Search reservations, review trip details, and manage passenger journeys.");
        subtitle.setFont(AeroFonts.BODY);
        subtitle.setForeground(AeroColors.TEXT_SECONDARY);

        titleBlock.add(title);
        titleBlock.add(Box.createVerticalStrut(2));
        titleBlock.add(subtitle);

        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        headerActions.setOpaque(false);

        ModernButton bookTicketBtn = new ModernButton("+ Reservation Desk", ModernButton.Style.PRIMARY);
        bookTicketBtn.setPreferredSize(new Dimension(160, 36));
        bookTicketBtn.addActionListener(e -> {
            if (navigationController != null) {
                navigationController.navigateTo(NavRoute.BOOKING);
            }
        });

        ModernButton topRefreshBtn = new ModernButton("↻ Refresh", ModernButton.Style.SECONDARY);
        topRefreshBtn.setPreferredSize(new Dimension(96, 36));
        topRefreshBtn.addActionListener(e -> refreshDataAsync());

        headerActions.add(bookTicketBtn);
        headerActions.add(topRefreshBtn);

        headerPanel.add(titleBlock, BorderLayout.WEST);
        headerPanel.add(headerActions, BorderLayout.EAST);
        mainContent.add(headerPanel);
        mainContent.add(Box.createVerticalStrut(16));

        // 2. Notification Banner
        notificationBanner = new NotificationBanner();
        mainContent.add(notificationBanner);
        mainContent.add(Box.createVerticalStrut(14));

        // 3. KPI Metrics Summary Cards
        JPanel metricsPanel = new JPanel(new GridLayout(1, 4, 16, 0));
        metricsPanel.setOpaque(false);
        metricsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 134));
        metricsPanel.setPreferredSize(new Dimension(Integer.MAX_VALUE, 128));

        totalBookingsCard = new DashboardCard("TOTAL RESERVATIONS", "0", "All historical records", "reservation", AeroColors.INFO_BG);
        confirmedTripsCard = new DashboardCard("CONFIRMED TRIPS", "0", "Active booked seats", "flight", AeroColors.SUCCESS_BG);
        cancelledTripsCard = new DashboardCard("CANCELLED TRIPS", "0", "Released reservations", "cancel", AeroColors.DANGER_BG);
        totalRevenueCard = new DashboardCard("TOTAL REVENUE", "₹0.00", "Settled booking value", "payment", AeroColors.WARNING_BG);

        metricsPanel.add(totalBookingsCard);
        metricsPanel.add(confirmedTripsCard);
        metricsPanel.add(cancelledTripsCard);
        metricsPanel.add(totalRevenueCard);
        mainContent.add(metricsPanel);
        mainContent.add(Box.createVerticalStrut(18));

        // 4. Search and Filter Bar Card
        JPanel filterCard = new JPanel(new BorderLayout(14, 0));
        filterCard.setBackground(Color.WHITE);
        filterCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(12, 16, 12, 16)
        ));
        filterCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));

        searchField = new ModernTextField();
        searchField.setPlaceholder("Search by PNR, passenger name, phone, flight #, airline, route...");
        searchField.setPreferredSize(new Dimension(360, AeroConstants.INPUT_HEIGHT));
        searchField.addActionListener(e -> loadTripsAsync());

        JPanel filterControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        filterControls.setOpaque(false);

        String[] statusOptions = {"All Statuses", "PENDING_PAYMENT", "CONFIRMED", "CANCELLED"};
        statusFilter = new ModernComboBox<>(statusOptions);
        statusFilter.setPreferredSize(new Dimension(150, AeroConstants.INPUT_HEIGHT));
        statusFilter.addActionListener(e -> loadTripsAsync());

        String[] paymentOptions = {"All Payments", "PENDING", "SUCCESS", "COMPLETED", "REFUNDED", "FAILED"};
        paymentFilter = new ModernComboBox<>(paymentOptions);
        paymentFilter.setPreferredSize(new Dimension(150, AeroConstants.INPUT_HEIGHT));
        paymentFilter.addActionListener(e -> loadTripsAsync());

        ModernButton searchBtn = new ModernButton("Search", ModernButton.Style.SECONDARY);
        searchBtn.setPreferredSize(new Dimension(84, AeroConstants.BUTTON_HEIGHT));
        searchBtn.addActionListener(e -> loadTripsAsync());

        ModernButton resetBtn = new ModernButton("Reset", ModernButton.Style.SECONDARY);
        resetBtn.setPreferredSize(new Dimension(76, AeroConstants.BUTTON_HEIGHT));
        resetBtn.addActionListener(e -> resetFilters());

        refreshBtn = topRefreshBtn;

        filterControls.add(new JLabel("Status:"));
        filterControls.add(statusFilter);
        filterControls.add(new JLabel("Payment:"));
        filterControls.add(paymentFilter);
        filterControls.add(searchBtn);
        filterControls.add(resetBtn);

        filterCard.add(searchField, BorderLayout.CENTER);
        filterCard.add(filterControls, BorderLayout.EAST);
        mainContent.add(filterCard);
        mainContent.add(Box.createVerticalStrut(14));

        // 5. Trips Table Panel
        JPanel tableCard = new JPanel(new BorderLayout(0, 10));
        tableCard.setBackground(Color.WHITE);
        tableCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(16, 18, 16, 18)
        ));

        JPanel tableHeaderBar = new JPanel(new BorderLayout());
        tableHeaderBar.setOpaque(false);

        JLabel tblTitle = new JLabel("Reservation Records");
        tblTitle.setFont(AeroFonts.CAPTION_BOLD);
        tblTitle.setForeground(AeroColors.TEXT_MAIN);

        recordCountLabel = new JLabel("Showing 0 reservations");
        recordCountLabel.setFont(AeroFonts.CAPTION);
        recordCountLabel.setForeground(AeroColors.TEXT_MUTED);

        tableHeaderBar.add(tblTitle, BorderLayout.WEST);
        tableHeaderBar.add(recordCountLabel, BorderLayout.EAST);
        tableCard.add(tableHeaderBar, BorderLayout.NORTH);

        tripTableModel = new TripTableModel();
        tripTable = new StyledTable(tripTableModel);

        // Column widths
        tripTable.getColumnModel().getColumn(0).setPreferredWidth(90);  // PNR
        tripTable.getColumnModel().getColumn(1).setPreferredWidth(140); // Passenger
        tripTable.getColumnModel().getColumn(2).setPreferredWidth(210); // Flight (expanded to prevent truncation)
        tripTable.getColumnModel().getColumn(3).setPreferredWidth(160); // Route
        tripTable.getColumnModel().getColumn(4).setPreferredWidth(130); // Departure
        tripTable.getColumnModel().getColumn(5).setPreferredWidth(60);  // Seat
        tripTable.getColumnModel().getColumn(6).setPreferredWidth(80);  // Fare
        tripTable.getColumnModel().getColumn(7).setPreferredWidth(110); // Reservation Status
        tripTable.getColumnModel().getColumn(8).setPreferredWidth(110); // Payment Status
        tripTable.getColumnModel().getColumn(9).setPreferredWidth(125); // Booking Date

        tripTable.getColumnModel().getColumn(7).setCellRenderer(StatusBadge.createTableCellRenderer());
        tripTable.getColumnModel().getColumn(8).setCellRenderer(StatusBadge.createTableCellRenderer());

        tripTable.getSelectionModel().addListSelectionListener(e -> {
            int row = tripTable.getSelectedRow();
            if (row >= 0 && row < tripList.size()) {
                int modelIdx = tripTable.convertRowIndexToModel(row);
                selectTrip(tripList.get(modelIdx));
            } else {
                clearSelection();
            }
        });

        tripTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && selectedTrip != null) {
                    showTripDetailsModal(selectedTrip);
                }
            }
        });

        tableContainer.setOpaque(false);
        tableContainer.add(new JScrollPane(tripTable), "table");
        tableContainer.add(createEmptyStatePanel(), "empty");
        tableCardLayout.show(tableContainer, "table");
        tableCard.add(tableContainer, BorderLayout.CENTER);

        // 6. Bottom Selection Context & Operations Bar
        JPanel bottomBar = new JPanel(new BorderLayout(14, 0));
        bottomBar.setOpaque(false);
        bottomBar.setBorder(new EmptyBorder(10, 0, 0, 0));

        JPanel summaryCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        summaryCard.setBackground(new Color(0xF8, 0xFA, 0xFC));
        summaryCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(4, 10, 4, 10)
        ));

        selectedSummaryLabel = new JLabel("Click any reservation row above to view details, inspect itinerary, or manage cancellation.");
        selectedSummaryLabel.setFont(AeroFonts.CAPTION);
        selectedSummaryLabel.setForeground(AeroColors.TEXT_SECONDARY);
        summaryCard.add(selectedSummaryLabel);
        bottomBar.add(summaryCard, BorderLayout.CENTER);

        JPanel actionBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actionBtns.setOpaque(false);

        viewTripBtn = new ModernButton("View Trip", ModernButton.Style.PRIMARY);
        viewTripBtn.setPreferredSize(new Dimension(105, 36));
        viewTripBtn.setEnabled(false);
        viewTripBtn.addActionListener(e -> {
            if (selectedTrip != null) showTripDetailsModal(selectedTrip);
        });

        viewPassengerBtn = new ModernButton("View Passenger", ModernButton.Style.SECONDARY);
        viewPassengerBtn.setPreferredSize(new Dimension(135, 36));
        viewPassengerBtn.setEnabled(false);
        viewPassengerBtn.addActionListener(e -> {
            if (selectedTrip != null && navigationController != null) {
                navigationController.navigateTo(NavRoute.PASSENGERS, selectedTrip.passengerName());
            }
        });

        viewFlightBtn = new ModernButton("View Flight", ModernButton.Style.SECONDARY);
        viewFlightBtn.setPreferredSize(new Dimension(110, 36));
        viewFlightBtn.setEnabled(false);
        viewFlightBtn.addActionListener(e -> {
            if (selectedTrip != null && navigationController != null) {
                navigationController.navigateTo(NavRoute.FLIGHTS, selectedTrip.flightNumber());
            }
        });

        boardingPassBtn = new ModernButton("Boarding Pass", ModernButton.Style.SECONDARY);
        boardingPassBtn.setPreferredSize(new Dimension(120, 36));
        boardingPassBtn.setEnabled(false);
        boardingPassBtn.addActionListener(e -> {
            if (selectedTrip != null) showBoardingPassModal(selectedTrip);
        });

        cancelTripBtn = new ModernButton("Cancel Trip", ModernButton.Style.DANGER);
        cancelTripBtn.setPreferredSize(new Dimension(115, 36));
        cancelTripBtn.setEnabled(false);
        cancelTripBtn.addActionListener(e -> handleCancelAction());

        actionBtns.add(viewTripBtn);
        actionBtns.add(viewPassengerBtn);
        actionBtns.add(viewFlightBtn);
        actionBtns.add(boardingPassBtn);
        actionBtns.add(cancelTripBtn);
        bottomBar.add(actionBtns, BorderLayout.EAST);

        tableCard.add(bottomBar, BorderLayout.SOUTH);
        mainContent.add(tableCard);

        add(new AeroScrollPane(mainContent), BorderLayout.CENTER);

        // Register Navigation Listener to support deep linking with PNR parameter
        if (controller != null) {
            controller.addListener((route, param) -> {
                if (route == NavRoute.TRIPS) {
                    if (param instanceof String pnr && !pnr.isBlank()) {
                        searchField.setText(pnr.trim());
                    }
                    refreshDataAsync();
                }
            });
        }

        // Initial Data Load
        refreshDataAsync();
    }

    private JPanel createEmptyStatePanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Color.WHITE);
        p.setBorder(new EmptyBorder(40, 20, 40, 20));

        JLabel t = new JLabel("No trips found");
        t.setFont(AeroFonts.BODY_BOLD);
        t.setForeground(AeroColors.TEXT_SECONDARY);
        t.setAlignmentX(CENTER_ALIGNMENT);

        JLabel c = new JLabel("Try changing your search or filter criteria.");
        c.setFont(AeroFonts.CAPTION);
        c.setForeground(AeroColors.TEXT_MUTED);
        c.setAlignmentX(CENTER_ALIGNMENT);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        btnRow.setOpaque(false);

        ModernButton resetBtn = new ModernButton("Reset Filters", ModernButton.Style.SECONDARY);
        resetBtn.setPreferredSize(new Dimension(110, 32));
        resetBtn.addActionListener(e -> resetFilters());

        ModernButton bookBtn = new ModernButton("+ Reservation Desk", ModernButton.Style.PRIMARY);
        bookBtn.setPreferredSize(new Dimension(150, 32));
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

    private void selectTrip(TripRecord trip) {
        this.selectedTrip = trip;
        viewTripBtn.setEnabled(true);
        viewPassengerBtn.setEnabled(true);
        viewFlightBtn.setEnabled(true);

        boolean isConfirmed = "CONFIRMED".equalsIgnoreCase(trip.reservationStatus());
        boardingPassBtn.setEnabled(isConfirmed);
        cancelTripBtn.setEnabled(isConfirmed);

        String seatText = (trip.seatNumber() != null && !trip.seatNumber().isBlank()) ? trip.seatNumber() : "—";
        selectedSummaryLabel.setText(String.format(
            "Selected: PNR %s | Passenger: %s | Flight: %s | Seat: %s | Status: %s | Fare: ₹%,.2f | Paid: ₹%,.2f",
            trip.pnr(), trip.passengerName(), trip.flightNumber(), seatText, trip.reservationStatus(),
            trip.fare(), trip.paidAmount() != null ? trip.paidAmount() : 0.0
        ));
        selectedSummaryLabel.setForeground(AeroColors.TEXT_MAIN);
    }

    private void clearSelection() {
        this.selectedTrip = null;
        viewTripBtn.setEnabled(false);
        viewPassengerBtn.setEnabled(false);
        viewFlightBtn.setEnabled(false);
        boardingPassBtn.setEnabled(false);
        cancelTripBtn.setEnabled(false);
        selectedSummaryLabel.setText("Click any reservation row above to view details, inspect itinerary, or manage cancellation.");
        selectedSummaryLabel.setForeground(AeroColors.TEXT_SECONDARY);
    }

    private void resetFilters() {
        searchField.setText("");
        statusFilter.setSelectedIndex(0);
        paymentFilter.setSelectedIndex(0);
        loadTripsAsync();
    }

    private void handleCancelAction() {
        if (selectedTrip == null) return;

        if (!"CONFIRMED".equalsIgnoreCase(selectedTrip.reservationStatus())) {
            notificationBanner.showMessage("Only CONFIRMED bookings can be cancelled.", NotificationBanner.Type.WARNING);
            return;
        }

        int opt = JOptionPane.showConfirmDialog(
            this,
            "Proceed to Ticket Cancellation screen for PNR " + selectedTrip.pnr() + "?\n" +
            "Passenger: " + selectedTrip.passengerName() + "\n" +
            "Flight: " + selectedTrip.flightNumber() + " (" + selectedTrip.airline() + ")\n" +
            "Seat: " + (selectedTrip.seatNumber() != null ? selectedTrip.seatNumber() : "—") + "\n\n" +
            "Cancellation will release the allocated seat and process refund logic.",
            "Confirm Cancellation Redirection",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        );

        if (opt == JOptionPane.YES_OPTION) {
            if (navigationController != null) {
                notificationBanner.showMessage("Navigating to Cancellation screen for PNR: " + selectedTrip.pnr(), NotificationBanner.Type.INFO);
                navigationController.navigateTo(NavRoute.CANCELLATIONS, selectedTrip.pnr());
            }
        }
    }

    // ==========================================
    // ASYNC DATA LOADING (SwingWorker)
    // ==========================================
    public void refreshDataAsync() {
        loadKpisAsync();
        loadTripsAsync();
    }

    private void loadKpisAsync() {
        SwingWorker<int[], Void> worker = new SwingWorker<>() {
            private double revenue = 0.0;

            @Override
            protected int[] doInBackground() throws Exception {
                int total = 0, confirmed = 0, cancelled = 0;
                String sql = "SELECT COUNT(*) AS total_count, " +
                             "COUNT(CASE WHEN status = 'CONFIRMED' THEN 1 END) AS confirmed_count, " +
                             "COUNT(CASE WHEN status = 'CANCELLED' THEN 1 END) AS cancelled_count, " +
                             "COALESCE(SUM(CASE WHEN status = 'CONFIRMED' THEN fare ELSE 0 END), 0) AS total_rev " +
                             "FROM reservations";

                try (Connection conn = DBConnection.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        total = rs.getInt("total_count");
                        confirmed = rs.getInt("confirmed_count");
                        cancelled = rs.getInt("cancelled_count");
                        revenue = rs.getDouble("total_rev");
                    }
                }
                return new int[]{total, confirmed, cancelled};
            }

            @Override
            protected void done() {
                try {
                    int[] counts = get();
                    totalBookingsCard.setValue(String.valueOf(counts[0]));
                    confirmedTripsCard.setValue(String.valueOf(counts[1]));
                    cancelledTripsCard.setValue(String.valueOf(counts[2]));
                    totalRevenueCard.setValue("₹" + String.format("%,.2f", revenue));
                } catch (Exception ignored) {
                }
            }
        };
        worker.execute();
    }

    private void loadTripsAsync() {
        refreshBtn.setEnabled(false);
        refreshBtn.setText("Loading...");

        final String search = searchField.getText().trim();
        final String statusVal = (String) statusFilter.getSelectedItem();
        final String paymentVal = (String) paymentFilter.getSelectedItem();

        SwingWorker<List<TripRecord>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<TripRecord> doInBackground() throws Exception {
                List<TripRecord> list = new ArrayList<>();
                StringBuilder sql = new StringBuilder(
                    "SELECT r.id AS res_id, r.pnr, r.seat_number, r.fare AS res_fare, r.booking_date, r.status AS res_status, " +
                    "p.id AS pass_id, p.legacy_pnr AS pass_legacy_pnr, p.name AS pass_name, p.phone AS pass_phone, p.email AS pass_email, " +
                    "f.id AS flight_id, f.flight_number, f.airline, f.source, f.destination, f.departure_time, f.arrival_time, " +
                    "pmt.payment_method, COALESCE(pmt.payment_status, 'PENDING') AS payment_status, pmt.transaction_reference, pmt.amount AS paid_amount, " +
                    "c.cancellation_date, c.reason AS cancel_reason, c.refund_amount " +
                    "FROM reservations r " +
                    "JOIN passengers p ON p.id = r.passenger_id " +
                    "JOIN flights f ON f.id = r.flight_id " +
                    "LEFT JOIN payments pmt ON pmt.reservation_id = r.id " +
                    "LEFT JOIN cancellations c ON c.reservation_id = r.id " +
                    "WHERE 1=1"
                );
                List<Object> params = new ArrayList<>();

                if (!search.isEmpty()) {
                    sql.append(" AND (r.pnr LIKE ? OR p.name LIKE ? OR p.phone LIKE ? OR f.flight_number LIKE ? OR f.airline LIKE ? OR f.source LIKE ? OR f.destination LIKE ?)");
                    String like = "%" + search + "%";
                    for (int i = 0; i < 7; i++) params.add(like);
                }

                if (statusVal != null && !statusVal.startsWith("All")) {
                    sql.append(" AND r.status = ?");
                    params.add(statusVal);
                }

                if (paymentVal != null && !paymentVal.startsWith("All")) {
                    sql.append(" AND pmt.payment_status = ?");
                    params.add(paymentVal);
                }

                sql.append(" ORDER BY r.booking_date DESC, r.id DESC");

                try (Connection conn = DBConnection.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Timestamp bTs = rs.getTimestamp("booking_date");
                            LocalDateTime bDate = bTs != null ? bTs.toLocalDateTime() : null;

                            Timestamp depTs = rs.getTimestamp("departure_time");
                            LocalDateTime depTime = depTs != null ? depTs.toLocalDateTime() : null;

                            Timestamp arrTs = rs.getTimestamp("arrival_time");
                            LocalDateTime arrTime = arrTs != null ? arrTs.toLocalDateTime() : null;

                            Timestamp cTs = rs.getTimestamp("cancellation_date");
                            LocalDateTime cDate = cTs != null ? cTs.toLocalDateTime() : null;

                            double paid = rs.getDouble("paid_amount");
                            Double paidAmount = rs.wasNull() ? null : paid;

                            double refund = rs.getDouble("refund_amount");
                            Double refundAmount = rs.wasNull() ? null : refund;

                            list.add(new TripRecord(
                                rs.getLong("res_id"),
                                rs.getString("pnr"),
                                rs.getString("seat_number"),
                                rs.getDouble("res_fare"),
                                bDate,
                                rs.getString("res_status"),
                                rs.getLong("pass_id"),
                                rs.getString("pass_legacy_pnr"),
                                rs.getString("pass_name"),
                                rs.getString("pass_phone"),
                                rs.getString("pass_email"),
                                rs.getLong("flight_id"),
                                rs.getString("flight_number"),
                                rs.getString("airline"),
                                rs.getString("source"),
                                rs.getString("destination"),
                                depTime,
                                arrTime,
                                rs.getString("payment_method"),
                                rs.getString("payment_status"),
                                rs.getString("transaction_reference"),
                                paidAmount,
                                cDate,
                                rs.getString("cancel_reason"),
                                refundAmount
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
                    tripList.clear();
                    tripList.addAll(get());
                    tripTableModel.fireTableDataChanged();

                    int count = tripList.size();
                    recordCountLabel.setText("Showing " + count + (count == 1 ? " reservation" : " reservations"));
                    tableCardLayout.show(tableContainer, count == 0 ? "empty" : "table");

                    clearSelection();

                    // If a single reservation was searched (e.g. from deep link), auto-select it
                    if (count == 1) {
                        tripTable.setRowSelectionInterval(0, 0);
                        selectTrip(tripList.get(0));
                    }
                } catch (Exception ex) {
                    notificationBanner.showMessage("Unable to load trip history. Please check the database connection and try again.", NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    // ==========================================
    // TRIP DETAILS MODAL DIALOG
    // ==========================================
    private void showTripDetailsModal(TripRecord trip) {
        Component parentComp = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parentComp instanceof java.awt.Frame ? (java.awt.Frame) parentComp : null, "TRIP DETAILS — PNR " + trip.pnr(), true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(780, 720);
        dialog.setLocationRelativeTo(parentComp);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(AeroColors.BACKGROUND);
        content.setBorder(new EmptyBorder(20, 24, 20, 24));

        // 0. PASSENGER JOURNEY PIPELINE CONTAINER (Async Loaded)
        JPanel journeyContainer = new JPanel(new BorderLayout());
        journeyContainer.setOpaque(false);
        JPanel journeyLoadingCard = createSectionCard("PASSENGER JOURNEY PIPELINE");
        JLabel jLoadLbl = new JLabel("Loading operational journey status...");
        jLoadLbl.setFont(AeroFonts.CAPTION);
        jLoadLbl.setForeground(AeroColors.TEXT_MUTED);
        journeyLoadingCard.add(jLoadLbl);
        journeyContainer.add(journeyLoadingCard, BorderLayout.CENTER);
        content.add(journeyContainer);
        content.add(Box.createVerticalStrut(12));

        // 1. RESERVATION SECTION
        JPanel resCard = createSectionCard("RESERVATION");
        JPanel rGrid = new JPanel(new GridLayout(2, 2, 12, 8));
        rGrid.setOpaque(false);
        addDetailRow(rGrid, "PNR", trip.pnr());
        addDetailRow(rGrid, "Reservation Status", trip.reservationStatus());
        addDetailRow(rGrid, "Booking Date", trip.bookingDate() != null ? trip.bookingDate().format(DISPLAY_FORMAT) : "—");
        addDetailRow(rGrid, "Reservation ID", "#" + trip.reservationId());
        resCard.add(rGrid);
        content.add(resCard);
        content.add(Box.createVerticalStrut(12));

        // 2. PASSENGER SECTION
        JPanel passCard = createSectionCard("PASSENGER");
        JPanel pGrid = new JPanel(new GridLayout(2, 2, 12, 8));
        pGrid.setOpaque(false);
        addDetailRow(pGrid, "Name", trip.passengerName());
        addDetailRow(pGrid, "Passenger PNR / Legacy PNR", trip.passengerLegacyPnr() != null ? trip.passengerLegacyPnr() : trip.pnr());
        addDetailRow(pGrid, "Phone", trip.passengerPhone());
        addDetailRow(pGrid, "Email", (trip.passengerEmail() != null && !trip.passengerEmail().isBlank()) ? trip.passengerEmail() : "—");
        passCard.add(pGrid);
        content.add(passCard);
        content.add(Box.createVerticalStrut(12));

        // 3. FLIGHT SECTION
        JPanel flightCard = createSectionCard("FLIGHT");
        JPanel fGrid = new JPanel(new GridLayout(3, 2, 12, 8));
        fGrid.setOpaque(false);
        addDetailRow(fGrid, "Flight Number", trip.flightNumber());
        addDetailRow(fGrid, "Airline", trip.airline());
        addDetailRow(fGrid, "Origin", trip.source());
        addDetailRow(fGrid, "Destination", trip.destination());
        addDetailRow(fGrid, "Departure", trip.departureTime() != null ? trip.departureTime().format(DISPLAY_FORMAT) : "—");
        addDetailRow(fGrid, "Arrival", trip.arrivalTime() != null ? trip.arrivalTime().format(DISPLAY_FORMAT) : "—");
        flightCard.add(fGrid);
        content.add(flightCard);
        content.add(Box.createVerticalStrut(12));

        // 4. SEAT SECTION
        JPanel seatCard = createSectionCard("SEAT");
        JPanel sGrid = new JPanel(new GridLayout(1, 2, 12, 8));
        sGrid.setOpaque(false);
        String seatVal = (trip.seatNumber() != null && !trip.seatNumber().isBlank()) ? trip.seatNumber() : "Not assigned";
        addDetailRow(sGrid, "Seat Number", seatVal);
        addDetailRow(sGrid, "Cabin Class", "Standard");
        seatCard.add(sGrid);
        content.add(seatCard);
        content.add(Box.createVerticalStrut(12));

        // 5. BAGGAGE OPERATIONS CONTAINER (Async Loaded)
        JPanel baggageContainer = new JPanel(new BorderLayout());
        baggageContainer.setOpaque(false);
        JPanel bagLoadingCard = createSectionCard("BAGGAGE OPERATIONS");
        JLabel bLoadLbl = new JLabel("Loading registered baggage records...");
        bLoadLbl.setFont(AeroFonts.CAPTION);
        bLoadLbl.setForeground(AeroColors.TEXT_MUTED);
        bagLoadingCard.add(bLoadLbl);
        baggageContainer.add(bagLoadingCard, BorderLayout.CENTER);
        content.add(baggageContainer);
        content.add(Box.createVerticalStrut(12));

        // 5b. IROPS DISRUPTION & SERVICE RECOVERY CONTAINER (Async Loaded)
        JPanel iropsContainer = new JPanel(new BorderLayout());
        iropsContainer.setOpaque(false);
        content.add(iropsContainer);
        content.add(Box.createVerticalStrut(12));

        // 6. PAYMENT SECTION (Fare vs Payment Amount kept strictly separate)
        JPanel payCard = createSectionCard("PAYMENT");
        JPanel payGrid = new JPanel(new GridLayout(2, 2, 12, 8));
        payGrid.setOpaque(false);
        addDetailRow(payGrid, "Fare", "₹" + String.format("%,.2f", trip.fare()));
        addDetailRow(payGrid, "Payment Amount", trip.paidAmount() != null ? ("₹" + String.format("%,.2f", trip.paidAmount())) : "—");
        addDetailRow(payGrid, "Payment Status", trip.paymentStatus());
        addDetailRow(payGrid, "Transaction / Payment Reference", trip.transactionReference() != null ? trip.transactionReference() : "—");
        payCard.add(payGrid);
        content.add(payCard);

        // 7. CANCELLATION RECORD (if applicable)
        if ("CANCELLED".equalsIgnoreCase(trip.reservationStatus())) {
            content.add(Box.createVerticalStrut(12));
            JPanel cancelCard = createSectionCard("CANCELLATION");
            JPanel cGrid = new JPanel(new GridLayout(2, 2, 12, 8));
            cGrid.setOpaque(false);
            addDetailRow(cGrid, "Cancellation Date", trip.cancellationDate() != null ? trip.cancellationDate().format(DISPLAY_FORMAT) : "—");
            addDetailRow(cGrid, "Refund Amount", trip.refundAmount() != null ? ("₹" + String.format("%,.2f", trip.refundAmount())) : "₹0.00");
            addDetailRow(cGrid, "Reason", trip.cancelReason() != null ? trip.cancelReason() : "—");
            addDetailRow(cGrid, "Seat Inventory", "Released back to flights");
            cancelCard.add(cGrid);
            content.add(cancelCard);
        }

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        dialog.add(scroll, BorderLayout.CENTER);

        // Footer Actions
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(Color.WHITE);
        footer.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, AeroColors.BORDER),
            new EmptyBorder(12, 20, 12, 20)
        ));

        JPanel leftBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftBtns.setOpaque(false);

        if ("CONFIRMED".equalsIgnoreCase(trip.reservationStatus())) {
            ModernButton bpBtn = new ModernButton("Boarding Pass", ModernButton.Style.SECONDARY);
            bpBtn.setPreferredSize(new Dimension(130, 36));
            bpBtn.addActionListener(e -> {
                dialog.dispose();
                showBoardingPassModal(trip);
            });
            leftBtns.add(bpBtn);

            ModernButton redCancelBtn = new ModernButton("Cancel Trip", ModernButton.Style.DANGER);
            redCancelBtn.setPreferredSize(new Dimension(120, 36));
            redCancelBtn.addActionListener(e -> {
                dialog.dispose();
                handleCancelAction();
            });
            leftBtns.add(redCancelBtn);
        }

        ModernButton closeBtn = new ModernButton("Close", ModernButton.Style.SECONDARY);
        closeBtn.setPreferredSize(new Dimension(90, 36));
        closeBtn.addActionListener(e -> dialog.dispose());

        footer.add(leftBtns, BorderLayout.WEST);
        footer.add(closeBtn, BorderLayout.EAST);
        dialog.add(footer, BorderLayout.SOUTH);

        // Background worker to populate Passenger Journey and Baggage details without blocking EDT
        SwingWorker<Object[], Void> worker = new SwingWorker<>() {
            @Override
            protected Object[] doInBackground() {
                CheckinPassengerDetails details = null;
                boolean isBoarded = false;
                try {
                    details = CheckinService.getInstance().lookupReservation(trip.pnr());
                    if (details != null && details.checkinId() != null) {
                        try (Connection conn = DBConnection.getConnection();
                             PreparedStatement ps = conn.prepareStatement("SELECT status FROM checkins WHERE id = ?")) {
                            ps.setLong(1, details.checkinId());
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next() && "BOARDED".equalsIgnoreCase(rs.getString("status"))) {
                                    isBoarded = true;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}

                List<BaggageRecord> baggage = new ArrayList<>();
                try {
                    baggage = BaggageService.getInstance().getBaggageByReservation(trip.reservationId());
                } catch (Exception ignored) {}

                List<PIRRecord> pirs = new ArrayList<>();
                ClaimCase activeClaim = null;
                try (Connection conn = DBConnection.getConnection()) {
                    if (tableExists(conn, "pir_reports")) {
                        pirs = PIRService.getInstance().getPIRsByReservation(trip.reservationId());
                        if (tableExists(conn, "claim_cases")) {
                            for (PIRRecord p : pirs) {
                                var clmOpt = ClaimService.getInstance().findActiveByPirId(p.id());
                                if (clmOpt.isPresent()) {
                                    activeClaim = clmOpt.get();
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}

                FlightDisruption activeDisruption = null;
                PassengerReaccommodation passengerReacc = null;
                List<DisruptionVoucher> vouchers = new ArrayList<>();
                try (Connection conn = DBConnection.getConnection()) {
                    if (tableExists(conn, "flight_disruptions")) {
                        var dOpt = DisruptionService.getInstance().findActiveByFlightId(trip.flightId());
                        if (dOpt.isPresent()) activeDisruption = dOpt.get();
                    }
                    if (tableExists(conn, "passenger_reaccommodations")) {
                        var rOpt = ReaccommodationEngine.getInstance().findByOriginalReservationId(trip.reservationId());
                        if (rOpt.isPresent()) passengerReacc = rOpt.get();
                    }
                    if (tableExists(conn, "disruption_vouchers")) {
                        vouchers = DisruptionVoucherService.getInstance().getVouchersForReservation(trip.reservationId());
                    }
                } catch (Exception ignored) {}

                return new Object[]{details, baggage, isBoarded, pirs, activeClaim, activeDisruption, passengerReacc, vouchers};
            }

            @Override
            protected void done() {
                try {
                    Object[] res = get();
                    CheckinPassengerDetails details = (CheckinPassengerDetails) res[0];
                    @SuppressWarnings("unchecked")
                    List<BaggageRecord> baggage = (List<BaggageRecord>) res[1];
                    boolean isBoarded = (Boolean) res[2];
                    @SuppressWarnings("unchecked")
                    List<PIRRecord> pirs = (List<PIRRecord>) res[3];
                    ClaimCase activeClaim = (ClaimCase) res[4];
                    FlightDisruption activeDisruption = (FlightDisruption) res[5];
                    PassengerReaccommodation passengerReacc = (PassengerReaccommodation) res[6];
                    @SuppressWarnings("unchecked")
                    List<DisruptionVoucher> vouchers = (List<DisruptionVoucher>) res[7];

                    journeyContainer.removeAll();
                    journeyContainer.add(buildJourneyStepper(trip, details, baggage, isBoarded, pirs, activeClaim, activeDisruption, passengerReacc), BorderLayout.CENTER);

                    baggageContainer.removeAll();
                    baggageContainer.add(buildBaggageSection(trip, details, baggage, pirs, activeClaim, dialog), BorderLayout.CENTER);

                    iropsContainer.removeAll();
                    if (activeDisruption != null || passengerReacc != null || !vouchers.isEmpty()) {
                        iropsContainer.add(buildIROPSSection(trip, activeDisruption, passengerReacc, vouchers, dialog), BorderLayout.CENTER);
                    }

                    content.revalidate();
                    content.repaint();
                } catch (Exception ignored) {}
            }
        };
        worker.execute();

        dialog.setVisible(true);
    }

    private JPanel buildJourneyStepper(TripRecord trip, CheckinPassengerDetails checkin, List<BaggageRecord> baggageList, boolean isBoarded, List<PIRRecord> pirs, ClaimCase activeClaim, FlightDisruption activeDisruption, PassengerReaccommodation passengerReacc) {
        JPanel card = createSectionCard("PASSENGER JOURNEY PIPELINE (RESERVATION → BOARDING → DEPARTURE)");
        JPanel grid = new JPanel(new GridLayout(1, 7, 6, 0));
        grid.setOpaque(false);

        // 1. RESERVATION
        boolean isConfirmed = "CONFIRMED".equalsIgnoreCase(trip.reservationStatus());
        boolean isCancelled = "CANCELLED".equalsIgnoreCase(trip.reservationStatus());
        String resState = isConfirmed ? "CONFIRMED" : (isCancelled ? "CANCELLED" : trip.reservationStatus());
        Color resCol = isConfirmed ? AeroColors.SUCCESS : (isCancelled ? AeroColors.DANGER : AeroColors.WARNING);
        String resSub = trip.pnr();
        if (passengerReacc != null && passengerReacc.isSuccessful()) {
            resState = "REBOOKED";
            resCol = AeroColors.PRIMARY;
            resSub = passengerReacc.newPnr();
        }
        grid.add(createStepBox("1. RESERVATION", resState, resCol, resSub));

        // 2. CHECK-IN
        boolean isCheckedIn = checkin != null && checkin.checkinStatus() == CheckinStatus.CHECKED_IN;
        String chkState = isCheckedIn ? "CHECKED IN" : (isCancelled ? "N/A" : "PENDING");
        Color chkCol = isCheckedIn ? AeroColors.SUCCESS : (isCancelled ? AeroColors.TEXT_MUTED : AeroColors.WARNING);
        String chkSeat = isCheckedIn ? ("Seat " + (checkin.seatNumber() != null ? checkin.seatNumber() : (trip.seatNumber() != null ? trip.seatNumber() : "—"))) : "Not checked in";
        grid.add(createStepBox("2. CHECK-IN", chkState, chkCol, chkSeat));

        // 3. BAGGAGE
        int bagCount = baggageList != null ? baggageList.size() : 0;
        boolean hasMissing = baggageList != null && baggageList.stream().anyMatch(b -> b.status() == BaggageStatus.MISSING);
        boolean hasDelayed = baggageList != null && baggageList.stream().anyMatch(b -> b.status() == BaggageStatus.DELAYED);
        String bagState;
        Color bagCol;
        String bagSub;
        if (hasMissing || hasDelayed) {
            bagCol = hasMissing ? AeroColors.DANGER : AeroColors.WARNING;
            if (activeClaim != null) {
                bagState = "CLAIM: " + activeClaim.status().name();
                bagSub = activeClaim.claimReference();
            } else if (pirs != null && !pirs.isEmpty() && pirs.stream().anyMatch(PIRRecord::isActive)) {
                PIRRecord activePir = pirs.stream().filter(PIRRecord::isActive).findFirst().get();
                bagState = "PIR: " + activePir.status().name();
                bagSub = activePir.pirReference();
            } else {
                bagState = hasMissing ? "MISSING" : "DELAYED";
                bagSub = "Exception Alert";
            }
        } else if (bagCount > 0) {
            bagState = bagCount + (bagCount == 1 ? " BAG" : " BAGS");
            bagCol = AeroColors.PRIMARY;
            double sumKg = baggageList.stream().mapToDouble(b -> b.weightKg() != null ? b.weightKg().doubleValue() : 0.0).sum();
            bagSub = String.format("%.1f kg total", sumKg);
        } else if (isCheckedIn) {
            bagState = "NO BAGS";
            bagCol = AeroColors.TEXT_MUTED;
            bagSub = "Cabin baggage only";
        } else {
            bagState = "PENDING";
            bagCol = AeroColors.TEXT_MUTED;
            bagSub = "At check-in desk";
        }
        grid.add(createStepBox("3. BAGGAGE", bagState, bagCol, bagSub));

        // 4. BOARDING PASS
        boolean bpIssued = checkin != null && checkin.boardingPassNumber() != null && !checkin.boardingPassNumber().isBlank();
        String bpState = bpIssued ? "ISSUED" : (isCancelled ? "N/A" : "NOT ISSUED");
        Color bpCol = bpIssued ? AeroColors.SUCCESS : (isCancelled ? AeroColors.TEXT_MUTED : AeroColors.TEXT_MUTED);
        String bpSub = bpIssued ? checkin.boardingPassNumber() : "Pending check-in";
        grid.add(createStepBox("4. BOARDING PASS", bpState, bpCol, bpSub));

        // 5. GATE
        String assignedGate = checkin != null && checkin.assignedGate() != null && !checkin.assignedGate().isBlank() ? checkin.assignedGate() : null;
        String gateState = assignedGate != null ? ("GATE " + assignedGate) : "UNASSIGNED";
        Color gateCol = assignedGate != null ? AeroColors.PRIMARY : AeroColors.TEXT_MUTED;
        String gateSub = assignedGate != null ? "Assigned" : "Standby";
        grid.add(createStepBox("5. GATE", gateState, gateCol, gateSub));

        // 6. BOARDING
        String brdState = isBoarded ? "BOARDED" : (isCheckedIn ? "STANDBY" : "PENDING");
        Color brdCol = isBoarded ? AeroColors.SUCCESS : (isCheckedIn ? AeroColors.WARNING : AeroColors.TEXT_MUTED);
        String brdSub = isBoarded ? "Onboard aircraft" : "Gate pending";
        grid.add(createStepBox("6. BOARDING", brdState, brdCol, brdSub));

        // 7. DEPARTURE
        String depTimeStr = trip.departureTime() != null ? trip.departureTime().format(DateTimeFormatter.ofPattern("HH:mm")) : "—";
        boolean depPassed = trip.departureTime() != null && trip.departureTime().isBefore(LocalDateTime.now());
        String depState = (depPassed && isBoarded) ? "DEPARTED" : "SCHEDULED";
        Color depCol = (depPassed && isBoarded) ? AeroColors.SUCCESS : AeroColors.PRIMARY;
        if (activeDisruption != null && activeDisruption.isActive()) {
            depState = activeDisruption.type().name();
            depCol = AeroColors.DANGER;
            depTimeStr = activeDisruption.delayMinutes() > 0 ? ("+" + activeDisruption.delayMinutes() + "m delay") : activeDisruption.reason().name();
        }
        grid.add(createStepBox("7. DEPARTURE", depState, depCol, depTimeStr));

        card.add(grid);
        return card;
    }

    private JPanel buildIROPSSection(TripRecord trip, FlightDisruption disruption, PassengerReaccommodation reacc, List<DisruptionVoucher> vouchers, JDialog parentDialog) {
        JPanel card = createSectionCard("FLIGHT DISRUPTION & IROPS RECOVERY");

        if (disruption != null) {
            JPanel dRow = new JPanel(new BorderLayout(8, 0));
            dRow.setOpaque(false);
            JLabel dTitle = new JLabel("Disruption: [" + disruption.disruptionReference() + "] " + disruption.type().getDisplayName() + " (" + disruption.reason().getDisplayName() + ")");
            dTitle.setFont(AeroFonts.BODY_BOLD);
            dTitle.setForeground(disruption.isTerminal() ? AeroColors.SUCCESS : AeroColors.DANGER);
            dRow.add(dTitle, BorderLayout.WEST);

            JLabel dStatus = new JLabel(disruption.status().name() + (disruption.delayMinutes() > 0 ? " (+" + disruption.delayMinutes() + " mins)" : ""));
            dStatus.setFont(AeroFonts.CAPTION_BOLD);
            dStatus.setForeground(AeroColors.TEXT_SECONDARY);
            dRow.add(dStatus, BorderLayout.EAST);
            card.add(dRow);
            card.add(Box.createVerticalStrut(6));
        }

        if (reacc != null) {
            JPanel rRow = new JPanel(new BorderLayout(8, 0));
            rRow.setOpaque(false);
            String reaccText = "Re-Accommodation: " + reacc.status().name();
            if (reacc.newFlightNumber() != null) {
                reaccText += " → Flight " + reacc.newFlightNumber() + " (Seat " + (reacc.newSeatNumber() != null ? reacc.newSeatNumber() : "—") + ", PNR: " + reacc.newPnr() + ")";
            }
            JLabel rLbl = new JLabel(reaccText);
            rLbl.setFont(AeroFonts.BODY);
            rLbl.setForeground(reacc.isSuccessful() ? AeroColors.SUCCESS : AeroColors.TEXT_MAIN);
            rRow.add(rLbl, BorderLayout.WEST);
            card.add(rRow);
            card.add(Box.createVerticalStrut(6));
        }

        if (vouchers != null && !vouchers.isEmpty()) {
            JPanel vRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
            vRow.setOpaque(false);
            JLabel vLbl = new JLabel("Recovery Vouchers (" + vouchers.size() + "): ");
            vLbl.setFont(AeroFonts.CAPTION_BOLD);
            vRow.add(vLbl);
            for (DisruptionVoucher v : vouchers) {
                JLabel vTag = new JLabel("[" + v.voucherReference() + ": " + v.type() + " " + v.currency() + " " + v.amount() + " (" + v.status() + ")]");
                vTag.setFont(AeroFonts.CAPTION);
                vTag.setForeground(AeroColors.TEXT_SECONDARY);
                vRow.add(vTag);
            }
            card.add(vRow);
            card.add(Box.createVerticalStrut(6));
        }

        if (navigationController != null) {
            JPanel actRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
            actRow.setOpaque(false);
            ModernButton viewInIropsBtn = new ModernButton("Open in IROPS Desk →", ModernButton.Style.PRIMARY);
            viewInIropsBtn.setPreferredSize(new Dimension(195, 28));
            viewInIropsBtn.addActionListener(e -> {
                parentDialog.dispose();
                String target = (disruption != null) ? disruption.disruptionReference() : trip.flightNumber();
                navigationController.navigateTo(NavRoute.IROPS, target);
            });
            actRow.add(viewInIropsBtn);
            card.add(actRow);
        }

        return card;
    }

    private JPanel createStepBox(String stepTitle, String status, Color statusColor, String subtext) {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBackground(new Color(0xF8, 0xFA, 0xFC));
        box.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(8, 6, 8, 6)
        ));

        JLabel titleLbl = new JLabel(stepTitle);
        titleLbl.setFont(new java.awt.Font("Segoe UI", java.awt.Font.BOLD, 10));
        titleLbl.setForeground(AeroColors.TEXT_MUTED);
        titleLbl.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel statusLbl = new JLabel(status);
        statusLbl.setFont(new java.awt.Font("Segoe UI", java.awt.Font.BOLD, 11));
        statusLbl.setForeground(statusColor);
        statusLbl.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel subLbl = new JLabel(subtext != null ? subtext : "—");
        subLbl.setFont(new java.awt.Font("Segoe UI", java.awt.Font.PLAIN, 10));
        subLbl.setForeground(AeroColors.TEXT_SECONDARY);
        subLbl.setAlignmentX(Component.CENTER_ALIGNMENT);

        box.add(titleLbl);
        box.add(Box.createVerticalStrut(4));
        box.add(statusLbl);
        box.add(Box.createVerticalStrut(2));
        box.add(subLbl);
        return box;
    }

    private JPanel buildBaggageSection(TripRecord trip, CheckinPassengerDetails checkin, List<BaggageRecord> baggageList, List<PIRRecord> pirs, ClaimCase activeClaim, JDialog parentDialog) {
        int bagCount = baggageList != null ? baggageList.size() : 0;
        double totalKg = (baggageList != null) ? baggageList.stream().mapToDouble(b -> b.weightKg() != null ? b.weightKg().doubleValue() : 0.0).sum() : 0.0;
        String headerText = "BAGGAGE OPERATIONS (" + bagCount + (bagCount == 1 ? " bag" : " bags") + (bagCount > 0 ? String.format(", %.1f kg total", totalKg) : "") + ")";
        JPanel card = createSectionCard(headerText);

        if (baggageList == null || baggageList.isEmpty()) {
            JLabel emptyLbl = new JLabel("No checked baggage registered for this passenger / reservation.");
            emptyLbl.setFont(AeroFonts.BODY);
            emptyLbl.setForeground(AeroColors.TEXT_MUTED);
            card.add(emptyLbl);
            return card;
        }

        // Exception alert banner if any baggage delayed or missing, or if active PIR/claim exists
        boolean hasDelayed = baggageList.stream().anyMatch(b -> b.status() == BaggageStatus.DELAYED);
        boolean hasMissing = baggageList.stream().anyMatch(b -> b.status() == BaggageStatus.MISSING);
        if (hasMissing || hasDelayed || (pirs != null && !pirs.isEmpty())) {
            JPanel alert = new JPanel();
            alert.setLayout(new BoxLayout(alert, BoxLayout.Y_AXIS));
            alert.setBackground(hasMissing ? new Color(0xFE, 0xF2, 0xF2) : new Color(0xFF, 0xFB, 0xEB));
            alert.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(hasMissing ? new Color(0xEF, 0x44, 0x44) : new Color(0xF5, 0x9E, 0x0B), 1),
                new EmptyBorder(8, 12, 8, 12)
            ));

            JLabel alertLbl = new JLabel(hasMissing ? "MISSING BAGGAGE EXCEPTION: Luggage reported missing in transit."
                    : (hasDelayed ? "DELAYED BAGGAGE EXCEPTION: Luggage delayed in handling." : "BAGGAGE IRREGULARITY DETECTED"));
            alertLbl.setFont(AeroFonts.CAPTION_BOLD);
            alertLbl.setForeground(hasMissing ? new Color(0xDC, 0x26, 0x26) : new Color(0xD9, 0x77, 0x06));
            alert.add(alertLbl);

            if (pirs != null && !pirs.isEmpty()) {
                for (PIRRecord pir : pirs) {
                    JLabel pirLbl = new JLabel("• PIR Record: " + pir.pirReference() + " (" + pir.type().getDisplayName() + ") — Status: " + pir.status().name());
                    pirLbl.setFont(AeroFonts.CAPTION);
                    pirLbl.setForeground(AeroColors.TEXT_MAIN);
                    alert.add(Box.createVerticalStrut(2));
                    alert.add(pirLbl);
                }
            }
            if (activeClaim != null) {
                JLabel claimLbl = new JLabel("• Baggage Claim: " + activeClaim.claimReference() + " — Status: " + activeClaim.status().name() +
                    (activeClaim.approvedAmount() != null ? (" (Approved: " + activeClaim.currency() + " " + activeClaim.approvedAmount() + ")") : (" (Requested: " + activeClaim.currency() + " " + activeClaim.requestedAmount() + ")")));
                claimLbl.setFont(AeroFonts.CAPTION);
                claimLbl.setForeground(AeroColors.TEXT_MAIN);
                alert.add(Box.createVerticalStrut(2));
                alert.add(claimLbl);
            }

            card.add(alert);
            card.add(Box.createVerticalStrut(8));
        }

        // List each registered baggage unit
        for (BaggageRecord bag : baggageList) {
            JPanel bRow = new JPanel(new BorderLayout());
            bRow.setOpaque(false);
            bRow.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AeroColors.BORDER),
                new EmptyBorder(6, 4, 6, 4)
            ));

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            left.setOpaque(false);
            JLabel tagLbl = new JLabel(bag.tagNumber());
            tagLbl.setFont(AeroFonts.BODY_BOLD);
            tagLbl.setForeground(AeroColors.TEXT_MAIN);

            JLabel typeLbl = new JLabel("[" + bag.type() + "]");
            typeLbl.setFont(AeroFonts.CAPTION);
            typeLbl.setForeground(AeroColors.TEXT_MUTED);

            JLabel wtLbl = new JLabel(bag.weightKg() != null ? bag.weightKg() + " kg" : "—");
            wtLbl.setFont(AeroFonts.CAPTION_BOLD);
            wtLbl.setForeground(AeroColors.TEXT_SECONDARY);

            left.add(tagLbl);
            left.add(typeLbl);
            left.add(wtLbl);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
            right.setOpaque(false);
            StatusBadge statusBadge = new StatusBadge(bag.status().name());
            right.add(statusBadge);

            if (bag.status().getDescription() != null && !bag.status().getDescription().isBlank()) {
                JLabel remLbl = new JLabel("(" + bag.status().getDescription() + ")");
                remLbl.setFont(AeroFonts.MUTED);
                remLbl.setForeground(bag.isException() ? AeroColors.DANGER : AeroColors.TEXT_MUTED);
                right.add(remLbl);
            }

            bRow.add(left, BorderLayout.WEST);
            bRow.add(right, BorderLayout.EAST);
            card.add(bRow);
        }

        if (navigationController != null) {
            JPanel actRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
            actRow.setOpaque(false);
            if (hasMissing || hasDelayed || (pirs != null && !pirs.isEmpty())) {
                ModernButton viewInPirBtn = new ModernButton("Open in PIR & Claims Desk →", ModernButton.Style.PRIMARY);
                viewInPirBtn.setPreferredSize(new Dimension(210, 28));
                viewInPirBtn.addActionListener(e -> {
                    parentDialog.dispose();
                    String target = (pirs != null && !pirs.isEmpty()) ? pirs.get(0).pirReference() : trip.pnr();
                    navigationController.navigateTo(NavRoute.PIR_CLAIMS, target);
                });
                actRow.add(viewInPirBtn);
            }
            ModernButton viewInBaggageBtn = new ModernButton("Open in Baggage Desk →", ModernButton.Style.SECONDARY);
            viewInBaggageBtn.setPreferredSize(new Dimension(175, 28));
            viewInBaggageBtn.addActionListener(e -> {
                parentDialog.dispose();
                navigationController.navigateTo(NavRoute.BAGGAGE, trip.pnr());
            });
            actRow.add(viewInBaggageBtn);
            card.add(actRow);
        }

        return card;
    }

    private JPanel createSectionCard(String heading) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(12, 16, 12, 16)
        ));

        JLabel h = new JLabel(heading);
        h.setFont(AeroFonts.CAPTION_BOLD);
        h.setForeground(AeroColors.TEXT_SECONDARY);
        card.add(h);
        card.add(Box.createVerticalStrut(8));
        return card;
    }

    private void addDetailRow(JPanel grid, String label, String value) {
        JPanel item = new JPanel();
        item.setLayout(new BoxLayout(item, BoxLayout.Y_AXIS));
        item.setOpaque(false);

        JLabel l = new JLabel(label);
        l.setFont(AeroFonts.MUTED);
        l.setForeground(AeroColors.TEXT_MUTED);

        JLabel v = new JLabel(value != null && !value.isBlank() ? value : "—");
        v.setFont(AeroFonts.BODY_BOLD);
        v.setForeground(AeroColors.TEXT_MAIN);

        item.add(l);
        item.add(Box.createVerticalStrut(2));
        item.add(v);
        grid.add(item);
    }

    // ==========================================
    // BOARDING PASS / TICKET MODAL
    // ==========================================
    private void showBoardingPassModal(TripRecord trip) {
        Component parentComp = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parentComp instanceof java.awt.Frame ? (java.awt.Frame) parentComp : null, "Boarding Pass — PNR " + trip.pnr(), true);
        dialog.setSize(620, 480);
        dialog.setLocationRelativeTo(parentComp);
        dialog.getContentPane().setBackground(AeroColors.BACKGROUND);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel loading = new JLabel("Loading boarding pass and gate details…", SwingConstants.CENTER);
        ModernButton cancelButton = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        JPanel loadingPanel = new JPanel(new BorderLayout(8, 8));
        loadingPanel.setBorder(new EmptyBorder(32, 32, 32, 32));
        loadingPanel.add(loading, BorderLayout.CENTER);
        loadingPanel.add(cancelButton, BorderLayout.SOUTH);
        dialog.setContentPane(loadingPanel);

        SwingWorker<BoardingPassLookup, Void> worker = new SwingWorker<>() {
            @Override
            protected BoardingPassLookup doInBackground() {
                BoardingPassRecord pass = null;
                FlightGateAssignment assignment = null;
                StringBuilder warning = new StringBuilder();
                try {
                    pass = CheckinService.getInstance().getBoardingPass(trip.pnr());
                } catch (Exception ex) {
                    warning.append("Boarding pass details could not be loaded. ");
                    System.err.println("Warning: Unable to lookup boarding pass record for PNR " + trip.pnr() + ": " + ex.getMessage());
                }
                if (pass == null || pass.gate() == null || pass.gate().isBlank()) {
                    try {
                        assignment = GateService.getInstance().getFlightGateAssignment(trip.flightId());
                    } catch (Exception ex) {
                        warning.append("Gate details could not be loaded.");
                        System.err.println("Warning: Unable to lookup gate assignment for flight #" + trip.flightId() + ": " + ex.getMessage());
                    }
                }
                return new BoardingPassLookup(pass, assignment, warning.toString().trim());
            }

            @Override
            protected void done() {
                if (!dialog.isDisplayable()) return;
                try {
                    dialog.setContentPane(buildBoardingPassPanel(trip, get(), dialog));
                    dialog.revalidate();
                    dialog.repaint();
                } catch (java.util.concurrent.CancellationException cancelled) {
                    dialog.dispose();
                } catch (Exception failure) {
                    System.err.println("Unable to display boarding pass for PNR " + trip.pnr() + ": " + failure.getMessage());
                    JLabel error = new JLabel("Unable to load boarding pass details. Close this window and try again.", SwingConstants.CENTER);
                    ModernButton close = new ModernButton("Close", ModernButton.Style.SECONDARY);
                    close.addActionListener(e -> dialog.dispose());
                    JPanel errorPanel = new JPanel(new BorderLayout(8, 8));
                    errorPanel.add(error, BorderLayout.CENTER);
                    errorPanel.add(close, BorderLayout.SOUTH);
                    dialog.setContentPane(errorPanel);
                    dialog.revalidate();
                }
            }
        };
        cancelButton.addActionListener(e -> {
            worker.cancel(true);
            dialog.dispose();
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { worker.cancel(true); }
        });
        worker.execute();
        dialog.setVisible(true);
    }

    private JPanel buildBoardingPassPanel(TripRecord trip, BoardingPassLookup lookup, JDialog dialog) {
        JPanel content = new JPanel(new BorderLayout());

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(new EmptyBorder(24, 28, 20, 28));

        // AeroDesk Boarding Pass Card
        JPanel ticketCard = new JPanel(new BorderLayout());
        ticketCard.setBackground(Color.WHITE);
        ticketCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(0, 0, 0, 0)
        ));

        // Ticket Header Banner
        JPanel tHeader = new JPanel(new BorderLayout());
        tHeader.setBackground(AeroColors.SIDEBAR);
        tHeader.setBorder(new EmptyBorder(14, 20, 14, 20));

        JLabel brand = new JLabel(trip.airline().toUpperCase() + "  •  BOARDING PASS");
        brand.setFont(AeroFonts.TITLE_SECTION);
        brand.setForeground(Color.WHITE);

        JLabel pnrBadge = new JLabel("PNR: " + trip.pnr());
        pnrBadge.setFont(AeroFonts.TITLE_SECTION);
        pnrBadge.setForeground(new Color(0x60, 0xA5, 0xFA));

        tHeader.add(brand, BorderLayout.WEST);
        tHeader.add(pnrBadge, BorderLayout.EAST);
        ticketCard.add(tHeader, BorderLayout.NORTH);

        // Ticket Body
        JPanel tBody = new JPanel();
        tBody.setLayout(new BoxLayout(tBody, BoxLayout.Y_AXIS));
        tBody.setOpaque(false);
        tBody.setBorder(new EmptyBorder(18, 22, 18, 22));

        // Route Hero Row
        JPanel routeRow = new JPanel(new BorderLayout());
        routeRow.setOpaque(false);

        JLabel srcLbl = new JLabel(trip.source().toUpperCase());
        srcLbl.setFont(AeroFonts.KPI_VALUE);
        srcLbl.setForeground(AeroColors.TEXT_MAIN);

        JLabel arrowLbl = new JLabel("→", SwingConstants.CENTER);
        arrowLbl.setFont(AeroFonts.TITLE_PAGE);
        arrowLbl.setForeground(AeroColors.PRIMARY);

        JLabel dstLbl = new JLabel(trip.destination().toUpperCase(), SwingConstants.RIGHT);
        dstLbl.setFont(AeroFonts.KPI_VALUE);
        dstLbl.setForeground(AeroColors.TEXT_MAIN);

        routeRow.add(srcLbl, BorderLayout.WEST);
        routeRow.add(arrowLbl, BorderLayout.CENTER);
        routeRow.add(dstLbl, BorderLayout.EAST);
        tBody.add(routeRow);
        tBody.add(Box.createVerticalStrut(18));

        // Authoritative resolution of boarding pass and gate information
        String seatVal = (trip.seatNumber() != null && !trip.seatNumber().isBlank()) ? trip.seatNumber() : "Not assigned";
        String gateVal = "Not assigned";
        String cabinClass = "Standard";
        String bpNumber = null;

        BoardingPassRecord bp = lookup.boardingPass();
        if (bp != null) {
                if (bp.seatNumber() != null && !bp.seatNumber().isBlank()) {
                    seatVal = bp.seatNumber();
                }
                if (bp.gate() != null && !bp.gate().isBlank()) {
                    gateVal = bp.getFormattedGate();
                }
                bpNumber = bp.boardingPassNumber();
        }

        if ("Not assigned".equals(gateVal)) {
            FlightGateAssignment fga = lookup.gateAssignment();
            if (fga != null && fga.gateNumber() != null && !fga.gateNumber().isBlank()) {
                gateVal = fga.getFormattedGate();
            }
        }

        if (bpNumber == null) {
            bpNumber = "BP-" + trip.pnr() + "-" + (trip.flightNumber() != null ? trip.flightNumber() : "FL");
        }
        String barcodePayload = trip.pnr() + "|" + trip.flightNumber() + "|" + seatVal + "|" + bpNumber;

        // Grid of Ticket Details
        JPanel tGrid = new JPanel(new GridLayout(2, 4, 16, 12));
        tGrid.setOpaque(false);

        addDetailRow(tGrid, "PASSENGER", trip.passengerName());
        addDetailRow(tGrid, "FLIGHT NUMBER", trip.flightNumber());
        addDetailRow(tGrid, "DEPARTURE TIME", trip.departureTime() != null ? trip.departureTime().format(DISPLAY_FORMAT) : "Not available");
        addDetailRow(tGrid, "SEAT", seatVal);

        addDetailRow(tGrid, "CABIN CLASS", cabinClass);
        addDetailRow(tGrid, "BOARDING GATE", gateVal);
        addDetailRow(tGrid, "RESERVATION STATUS", trip.reservationStatus());
        addDetailRow(tGrid, "PAYMENT STATUS", trip.paymentStatus() != null ? trip.paymentStatus() : "PENDING");

        tBody.add(tGrid);
        tBody.add(Box.createVerticalStrut(16));

        // Derived visual barcode representation
        JPanel barcodeCard = new JPanel();
        barcodeCard.setLayout(new BoxLayout(barcodeCard, BoxLayout.Y_AXIS));
        barcodeCard.setBackground(Color.WHITE);
        barcodeCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(10, 14, 10, 14)
        ));

        BarcodeStrip barcodeStrip = new BarcodeStrip(barcodePayload);
        barcodeStrip.setAlignmentX(CENTER_ALIGNMENT);

        JLabel barcodeLabel = new JLabel("BARCODE: " + barcodePayload, SwingConstants.CENTER);
        barcodeLabel.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11));
        barcodeLabel.setForeground(AeroColors.TEXT_MUTED);
        barcodeLabel.setAlignmentX(CENTER_ALIGNMENT);

        barcodeCard.add(barcodeStrip);
        barcodeCard.add(Box.createVerticalStrut(4));
        barcodeCard.add(barcodeLabel);
        tBody.add(barcodeCard);

        ticketCard.add(tBody, BorderLayout.CENTER);
        wrapper.add(ticketCard, BorderLayout.CENTER);
        if (!lookup.warning().isBlank()) {
            JLabel warning = new JLabel(lookup.warning(), SwingConstants.CENTER);
            warning.setForeground(new Color(0x9A, 0x5B, 0x00));
            warning.setBorder(new EmptyBorder(8, 12, 0, 12));
            wrapper.add(warning, BorderLayout.NORTH);
        }
        content.add(wrapper, BorderLayout.CENTER);

        // Footer Actions
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 12));
        footer.setBackground(Color.WHITE);
        footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, AeroColors.BORDER));

        ModernButton printBtn = new ModernButton("Print Ticket", ModernButton.Style.PRIMARY);
        printBtn.setPreferredSize(new Dimension(120, 36));
        printBtn.addActionListener(e -> {
            JOptionPane.showMessageDialog(dialog, "Ticket spooler dispatched. Boarding pass for PNR " + trip.pnr() + " sent to printer.", "Print Dispatch", JOptionPane.INFORMATION_MESSAGE);
        });

        ModernButton closeBtn = new ModernButton("Close", ModernButton.Style.SECONDARY);
        closeBtn.setPreferredSize(new Dimension(90, 36));
        closeBtn.addActionListener(e -> dialog.dispose());

        footer.add(printBtn);
        footer.add(closeBtn);
        content.add(footer, BorderLayout.SOUTH);
        return content;
    }

    // ==========================================
    // TABLE MODEL & RECORDS
    // ==========================================
    public record TripRecord(
        long reservationId,
        String pnr,
        String seatNumber,
        double fare,
        LocalDateTime bookingDate,
        String reservationStatus,
        long passengerId,
        String passengerLegacyPnr,
        String passengerName,
        String passengerPhone,
        String passengerEmail,
        long flightId,
        String flightNumber,
        String airline,
        String source,
        String destination,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        String paymentMethod,
        String paymentStatus,
        String transactionReference,
        Double paidAmount,
        LocalDateTime cancellationDate,
        String cancelReason,
        Double refundAmount
    ) {}

    private record BoardingPassLookup(BoardingPassRecord boardingPass, FlightGateAssignment gateAssignment, String warning) {}

    private class TripTableModel extends AbstractTableModel {
        private final String[] columns = {
            "PNR", "Passenger", "Flight", "Route", "Departure", "Seat", "Fare", "Reservation Status", "Payment Status", "Booking Date"
        };

        @Override public int getRowCount() { return tripList.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            TripRecord t = tripList.get(row);
            return switch (col) {
                case 0 -> t.pnr();
                case 1 -> t.passengerName();
                case 2 -> t.flightNumber() + " (" + t.airline() + ")";
                case 3 -> t.source() + " → " + t.destination();
                case 4 -> t.departureTime() != null ? t.departureTime().format(DISPLAY_FORMAT) : "—";
                case 5 -> (t.seatNumber() != null && !t.seatNumber().isBlank()) ? t.seatNumber() : "—";
                case 6 -> "₹" + String.format("%,.2f", t.fare());
                case 7 -> t.reservationStatus();
                case 8 -> t.paymentStatus();
                case 9 -> t.bookingDate() != null ? t.bookingDate().format(DISPLAY_FORMAT) : "—";
                default -> "—";
            };
        }

        @Override public boolean isCellEditable(int r, int c) { return false; }
    }

    private static boolean tableExists(Connection conn, String tableName) {
        try (ResultSet rs = conn.getMetaData().getTables(conn.getCatalog(), null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    private static final class BarcodeStrip extends JPanel {
        private final String payload;

        BarcodeStrip(String payload) {
            this.payload = payload != null ? payload : "";
            setPreferredSize(new Dimension(520, 52));
            setMaximumSize(new Dimension(580, 52));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            int width = getWidth();
            int height = getHeight();

            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, width, height);

            byte[] bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > 0 && width > 40) {
                int startX = 20;
                int usableWidth = width - 40;
                int totalBits = bytes.length * 8;
                double bitWidth = Math.max(1.5, (double) usableWidth / totalBits);
                double currentX = startX;

                g2.setColor(AeroColors.TEXT_MAIN);
                for (byte b : bytes) {
                    for (int bit = 7; bit >= 0; bit--) {
                        boolean black = ((b >> bit) & 1) == 1;
                        if (black) {
                            int barX = (int) Math.round(currentX);
                            int barW = Math.max(1, (int) Math.round(bitWidth));
                            g2.fillRect(barX, 4, barW, height - 8);
                        }
                        currentX += bitWidth;
                        if (currentX >= width - 20) break;
                    }
                    if (currentX >= width - 20) break;
                }
            }
            g2.dispose();
        }
    }
}
