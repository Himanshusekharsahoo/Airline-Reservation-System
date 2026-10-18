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
 * AeroDesk Enterprise Payment Management Screen.
 * Primarily a read-only payment operations and financial audit interface.
 * Consumes authoritative database records joined across payments, reservations,
 * passengers, flights, and cancellations.
 */
public class PaymentsPanel extends JPanel {
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NavigationController navigationController;
    private final NotificationBanner notificationBanner;

    // KPI Summary Metrics Cards
    private final DashboardCard totalPaymentsCard;
    private final DashboardCard successPaymentsCard;
    private final DashboardCard pendingPaymentsCard;
    private final DashboardCard totalCollectedCard;

    // Search and Filters
    private final ModernTextField searchField;
    private final ModernComboBox<String> statusFilter;
    private final ModernComboBox<String> methodFilter;
    private final ModernButton refreshBtn;
    private final JLabel recordCountLabel;

    // Table & Models
    private final StyledTable paymentTable;
    private final PaymentTableModel paymentTableModel;
    private final List<PaymentRecord> paymentList = new ArrayList<>();
    private final CardLayout tableCardLayout = new CardLayout();
    private final JPanel tableContainer = new JPanel(tableCardLayout);

    // Row Selection Context & Actions
    private PaymentRecord selectedPayment = null;
    private final JLabel selectedSummaryLabel;
    private final ModernButton viewDetailsBtn;
    private final ModernButton viewTripBtn;
    private final ModernButton viewPassengerBtn;
    private final ModernButton viewFlightBtn;
    private final ModernButton printReceiptBtn;

    public PaymentsPanel(NavigationController controller) {
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

        JLabel title = new JLabel("PAYMENT MANAGEMENT");
        title.setFont(AeroFonts.TITLE_PAGE);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel subtitle = new JLabel("Review payment transactions, settlement status, and reservation-linked financial records.");
        subtitle.setFont(AeroFonts.BODY);
        subtitle.setForeground(AeroColors.TEXT_SECONDARY);

        titleBlock.add(title);
        titleBlock.add(Box.createVerticalStrut(2));
        titleBlock.add(subtitle);

        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        headerActions.setOpaque(false);

        ModernButton bookTicketBtn = new ModernButton("+ Book Ticket", ModernButton.Style.PRIMARY);
        bookTicketBtn.setPreferredSize(new Dimension(140, 36));
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

        // 3. KPI Summary Metrics Cards
        JPanel metricsPanel = new JPanel(new GridLayout(1, 4, 16, 0));
        metricsPanel.setOpaque(false);
        metricsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 134));
        metricsPanel.setPreferredSize(new Dimension(Integer.MAX_VALUE, 128));

        totalPaymentsCard = new DashboardCard("TOTAL PAYMENTS", "0", "All transaction records", "payment", AeroColors.INFO_BG);
        successPaymentsCard = new DashboardCard("SUCCESSFUL PAYMENTS", "0", "Settled transactions", "checkin", AeroColors.SUCCESS_BG);
        pendingPaymentsCard = new DashboardCard("PENDING PAYMENTS", "0", "Unsettled / In-flight", "alert", AeroColors.WARNING_BG);
        totalCollectedCard = new DashboardCard("TOTAL COLLECTED", "₹0.00", "Gross payment volume", "payment", AeroColors.SUCCESS_BG);

        metricsPanel.add(totalPaymentsCard);
        metricsPanel.add(successPaymentsCard);
        metricsPanel.add(pendingPaymentsCard);
        metricsPanel.add(totalCollectedCard);
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
        searchField.setPlaceholder("Search by payment ref, PNR, passenger name, flight #, phone...");
        searchField.setPreferredSize(new Dimension(360, AeroConstants.INPUT_HEIGHT));
        searchField.addActionListener(e -> loadPaymentsAsync());

        JPanel filterControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        filterControls.setOpaque(false);

        String[] statusOptions = {"All Payments", "PENDING", "SUCCESS", "COMPLETED", "REFUNDED", "FAILED"};
        statusFilter = new ModernComboBox<>(statusOptions);
        statusFilter.setPreferredSize(new Dimension(140, AeroConstants.INPUT_HEIGHT));
        statusFilter.addActionListener(e -> loadPaymentsAsync());

        String[] methodOptions = {"All Methods", "UPI", "Card", "Cash", "LEGACY"};
        methodFilter = new ModernComboBox<>(methodOptions);
        methodFilter.setPreferredSize(new Dimension(130, AeroConstants.INPUT_HEIGHT));
        methodFilter.addActionListener(e -> loadPaymentsAsync());

        ModernButton searchBtn = new ModernButton("Search", ModernButton.Style.SECONDARY);
        searchBtn.setPreferredSize(new Dimension(84, AeroConstants.BUTTON_HEIGHT));
        searchBtn.addActionListener(e -> loadPaymentsAsync());

        ModernButton resetBtn = new ModernButton("Reset", ModernButton.Style.SECONDARY);
        resetBtn.setPreferredSize(new Dimension(76, AeroConstants.BUTTON_HEIGHT));
        resetBtn.addActionListener(e -> resetFilters());

        refreshBtn = topRefreshBtn;

        filterControls.add(new JLabel("Status:"));
        filterControls.add(statusFilter);
        filterControls.add(new JLabel("Method:"));
        filterControls.add(methodFilter);
        filterControls.add(searchBtn);
        filterControls.add(resetBtn);

        filterCard.add(searchField, BorderLayout.CENTER);
        filterCard.add(filterControls, BorderLayout.EAST);
        mainContent.add(filterCard);
        mainContent.add(Box.createVerticalStrut(14));

        // 5. Payments Table Panel
        JPanel tableCard = new JPanel(new BorderLayout(0, 10));
        tableCard.setBackground(Color.WHITE);
        tableCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(16, 18, 16, 18)
        ));

        JPanel tableHeaderBar = new JPanel(new BorderLayout());
        tableHeaderBar.setOpaque(false);

        JLabel tblTitle = new JLabel("Payment Transactions");
        tblTitle.setFont(AeroFonts.CAPTION_BOLD);
        tblTitle.setForeground(AeroColors.TEXT_MAIN);

        recordCountLabel = new JLabel("Showing 0 payments");
        recordCountLabel.setFont(AeroFonts.CAPTION);
        recordCountLabel.setForeground(AeroColors.TEXT_MUTED);

        tableHeaderBar.add(tblTitle, BorderLayout.WEST);
        tableHeaderBar.add(recordCountLabel, BorderLayout.EAST);
        tableCard.add(tableHeaderBar, BorderLayout.NORTH);

        paymentTableModel = new PaymentTableModel();
        paymentTable = new StyledTable(paymentTableModel);

        // Column widths
        paymentTable.getColumnModel().getColumn(0).setPreferredWidth(130); // Payment / Reference
        paymentTable.getColumnModel().getColumn(1).setPreferredWidth(80);  // PNR
        paymentTable.getColumnModel().getColumn(2).setPreferredWidth(140); // Passenger
        paymentTable.getColumnModel().getColumn(3).setPreferredWidth(210); // Flight (expanded to prevent truncation)
        paymentTable.getColumnModel().getColumn(4).setPreferredWidth(100); // Payment Amount
        paymentTable.getColumnModel().getColumn(5).setPreferredWidth(95);  // Reservation Fare
        paymentTable.getColumnModel().getColumn(6).setPreferredWidth(90);  // Payment Method
        paymentTable.getColumnModel().getColumn(7).setPreferredWidth(115); // Payment Status
        paymentTable.getColumnModel().getColumn(8).setPreferredWidth(125); // Payment Date

        paymentTable.getColumnModel().getColumn(7).setCellRenderer(StatusBadge.createTableCellRenderer());

        paymentTable.getSelectionModel().addListSelectionListener(e -> {
            int row = paymentTable.getSelectedRow();
            if (row >= 0 && row < paymentList.size()) {
                int modelIdx = paymentTable.convertRowIndexToModel(row);
                selectPayment(paymentList.get(modelIdx));
            } else {
                clearSelection();
            }
        });

        paymentTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && selectedPayment != null) {
                    showPaymentDetailsModal(selectedPayment);
                }
            }
        });

        tableContainer.setOpaque(false);
        tableContainer.add(new JScrollPane(paymentTable), "table");
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

        selectedSummaryLabel = new JLabel("Click any payment transaction row above to inspect details, receipt, or linked reservation.");
        selectedSummaryLabel.setFont(AeroFonts.CAPTION);
        selectedSummaryLabel.setForeground(AeroColors.TEXT_SECONDARY);
        summaryCard.add(selectedSummaryLabel);
        bottomBar.add(summaryCard, BorderLayout.CENTER);

        JPanel actionBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actionBtns.setOpaque(false);

        viewDetailsBtn = new ModernButton("View Details", ModernButton.Style.PRIMARY);
        viewDetailsBtn.setPreferredSize(new Dimension(110, 36));
        viewDetailsBtn.setEnabled(false);
        viewDetailsBtn.addActionListener(e -> {
            if (selectedPayment != null) showPaymentDetailsModal(selectedPayment);
        });

        viewTripBtn = new ModernButton("View Trip", ModernButton.Style.SECONDARY);
        viewTripBtn.setPreferredSize(new Dimension(100, 36));
        viewTripBtn.setEnabled(false);
        viewTripBtn.addActionListener(e -> {
            if (selectedPayment != null && navigationController != null && selectedPayment.pnr() != null) {
                navigationController.navigateTo(NavRoute.TRIPS, selectedPayment.pnr());
            }
        });

        viewPassengerBtn = new ModernButton("View Passenger", ModernButton.Style.SECONDARY);
        viewPassengerBtn.setPreferredSize(new Dimension(135, 36));
        viewPassengerBtn.setEnabled(false);
        viewPassengerBtn.addActionListener(e -> {
            if (selectedPayment != null && navigationController != null && selectedPayment.passengerName() != null) {
                navigationController.navigateTo(NavRoute.PASSENGERS, selectedPayment.passengerName());
            }
        });

        viewFlightBtn = new ModernButton("View Flight", ModernButton.Style.SECONDARY);
        viewFlightBtn.setPreferredSize(new Dimension(110, 36));
        viewFlightBtn.setEnabled(false);
        viewFlightBtn.addActionListener(e -> {
            if (selectedPayment != null && navigationController != null && selectedPayment.flightNumber() != null) {
                navigationController.navigateTo(NavRoute.FLIGHTS, selectedPayment.flightNumber());
            }
        });

        printReceiptBtn = new ModernButton("Print Receipt", ModernButton.Style.SECONDARY);
        printReceiptBtn.setPreferredSize(new Dimension(120, 36));
        printReceiptBtn.setEnabled(false);
        printReceiptBtn.addActionListener(e -> {
            if (selectedPayment != null) showReceiptModal(selectedPayment);
        });

        actionBtns.add(viewDetailsBtn);
        actionBtns.add(viewTripBtn);
        actionBtns.add(viewPassengerBtn);
        actionBtns.add(viewFlightBtn);
        actionBtns.add(printReceiptBtn);
        bottomBar.add(actionBtns, BorderLayout.EAST);

        tableCard.add(bottomBar, BorderLayout.SOUTH);
        mainContent.add(tableCard);

        add(new AeroScrollPane(mainContent), BorderLayout.CENTER);

        // Register Navigation Listener for deep linking
        if (controller != null) {
            controller.addListener((route, param) -> {
                if (route == NavRoute.PAYMENTS) {
                    if (param instanceof String q && !q.isBlank()) {
                        searchField.setText(q.trim());
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

        JLabel t = new JLabel("No payments found");
        t.setFont(AeroFonts.BODY_BOLD);
        t.setForeground(AeroColors.TEXT_SECONDARY);
        t.setAlignmentX(CENTER_ALIGNMENT);

        JLabel c = new JLabel("Try changing your search or payment filters.");
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

    private void selectPayment(PaymentRecord pmt) {
        this.selectedPayment = pmt;
        viewDetailsBtn.setEnabled(true);
        viewTripBtn.setEnabled(pmt.pnr() != null && !pmt.pnr().isBlank());
        viewPassengerBtn.setEnabled(pmt.passengerName() != null && !pmt.passengerName().isBlank());
        viewFlightBtn.setEnabled(pmt.flightNumber() != null && !pmt.flightNumber().isBlank());
        printReceiptBtn.setEnabled(true);

        selectedSummaryLabel.setText(String.format(
            "Selected: Ref %s | PNR: %s | Passenger: %s | Paid: ₹%,.2f | Fare: ₹%,.2f | Status: %s",
            pmt.transactionReference(),
            pmt.pnr() != null ? pmt.pnr() : "—",
            pmt.passengerName() != null ? pmt.passengerName() : "—",
            pmt.amount(),
            pmt.reservationFare() != null ? pmt.reservationFare() : 0.0,
            pmt.paymentStatus()
        ));
        selectedSummaryLabel.setForeground(AeroColors.TEXT_MAIN);
    }

    private void clearSelection() {
        this.selectedPayment = null;
        viewDetailsBtn.setEnabled(false);
        viewTripBtn.setEnabled(false);
        viewPassengerBtn.setEnabled(false);
        viewFlightBtn.setEnabled(false);
        printReceiptBtn.setEnabled(false);
        selectedSummaryLabel.setText("Click any payment transaction row above to inspect details, receipt, or linked reservation.");
        selectedSummaryLabel.setForeground(AeroColors.TEXT_SECONDARY);
    }

    private void resetFilters() {
        searchField.setText("");
        statusFilter.setSelectedIndex(0);
        methodFilter.setSelectedIndex(0);
        loadPaymentsAsync();
    }

    // ==========================================
    // ASYNC DATA LOADING (SwingWorker)
    // ==========================================
    public void refreshDataAsync() {
        loadKpisAsync();
        loadPaymentsAsync();
    }

    private void loadKpisAsync() {
        SwingWorker<int[], Void> worker = new SwingWorker<>() {
            private double totalCollected = 0.0;

            @Override
            protected int[] doInBackground() throws Exception {
                int total = 0, success = 0, pending = 0;
                String sql = "SELECT COUNT(*) AS total_payments, " +
                             "COUNT(CASE WHEN payment_status IN ('SUCCESS', 'COMPLETED') THEN 1 END) AS success_count, " +
                             "COUNT(CASE WHEN payment_status = 'PENDING' THEN 1 END) AS pending_count, " +
                             "COALESCE(SUM(CASE WHEN payment_status IN ('SUCCESS', 'COMPLETED') THEN amount ELSE 0 END), 0) AS collected " +
                             "FROM payments";

                try (Connection conn = DBConnection.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        total = rs.getInt("total_payments");
                        success = rs.getInt("success_count");
                        pending = rs.getInt("pending_count");
                        totalCollected = rs.getDouble("collected");
                    }
                }
                return new int[]{total, success, pending};
            }

            @Override
            protected void done() {
                try {
                    int[] counts = get();
                    totalPaymentsCard.setValue(String.valueOf(counts[0]));
                    successPaymentsCard.setValue(String.valueOf(counts[1]));
                    pendingPaymentsCard.setValue(String.valueOf(counts[2]));
                    totalCollectedCard.setValue("₹" + String.format("%,.2f", totalCollected));
                } catch (Exception ignored) {
                }
            }
        };
        worker.execute();
    }

    private void loadPaymentsAsync() {
        refreshBtn.setEnabled(false);
        refreshBtn.setText("Loading...");

        final String search = searchField.getText().trim();
        final String statusVal = (String) statusFilter.getSelectedItem();
        final String methodVal = (String) methodFilter.getSelectedItem();

        SwingWorker<List<PaymentRecord>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<PaymentRecord> doInBackground() throws Exception {
                List<PaymentRecord> list = new ArrayList<>();
                StringBuilder sql = new StringBuilder(
                    "SELECT pmt.id AS payment_id, pmt.transaction_reference, pmt.amount AS payment_amount, " +
                    "pmt.payment_method, pmt.payment_status, pmt.payment_date, " +
                    "r.id AS res_id, r.pnr, r.fare AS res_fare, r.status AS res_status, r.booking_date, " +
                    "p.id AS pass_id, p.name AS pass_name, p.phone AS pass_phone, p.email AS pass_email, p.legacy_pnr AS pass_legacy_pnr, " +
                    "f.id AS flight_id, f.flight_number, f.airline, f.source, f.destination, f.departure_time, " +
                    "c.id AS cancel_id, c.refund_amount, c.cancellation_date, c.reason AS cancel_reason, c.status AS cancel_status " +
                    "FROM payments pmt " +
                    "LEFT JOIN reservations r ON r.id = pmt.reservation_id " +
                    "LEFT JOIN passengers p ON p.id = r.passenger_id " +
                    "LEFT JOIN flights f ON f.id = r.flight_id " +
                    "LEFT JOIN cancellations c ON c.reservation_id = r.id " +
                    "WHERE 1=1"
                );
                List<Object> params = new ArrayList<>();

                if (!search.isEmpty()) {
                    sql.append(" AND (pmt.transaction_reference LIKE ? OR r.pnr LIKE ? OR p.name LIKE ? OR f.flight_number LIKE ? OR p.phone LIKE ?)");
                    String like = "%" + search + "%";
                    for (int i = 0; i < 5; i++) params.add(like);
                }

                if (statusVal != null && !statusVal.startsWith("All")) {
                    sql.append(" AND pmt.payment_status = ?");
                    params.add(statusVal);
                }

                if (methodVal != null && !methodVal.startsWith("All")) {
                    sql.append(" AND pmt.payment_method = ?");
                    params.add(methodVal);
                }

                sql.append(" ORDER BY pmt.payment_date DESC, pmt.id DESC");

                try (Connection conn = DBConnection.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Timestamp pTs = rs.getTimestamp("payment_date");
                            LocalDateTime pDate = pTs != null ? pTs.toLocalDateTime() : null;

                            Timestamp bTs = rs.getTimestamp("booking_date");
                            LocalDateTime bDate = bTs != null ? bTs.toLocalDateTime() : null;

                            Timestamp depTs = rs.getTimestamp("departure_time");
                            LocalDateTime depTime = depTs != null ? depTs.toLocalDateTime() : null;

                            Timestamp cTs = rs.getTimestamp("cancellation_date");
                            LocalDateTime cDate = cTs != null ? cTs.toLocalDateTime() : null;

                            double rFare = rs.getDouble("res_fare");
                            Double resFare = rs.wasNull() ? null : rFare;

                            double refAmt = rs.getDouble("refund_amount");
                            Double refundAmount = rs.wasNull() ? null : refAmt;

                            long resIdVal = rs.getLong("res_id");
                            Long resId = rs.wasNull() ? null : resIdVal;

                            long passIdVal = rs.getLong("pass_id");
                            Long passId = rs.wasNull() ? null : passIdVal;

                            long flightIdVal = rs.getLong("flight_id");
                            Long flightId = rs.wasNull() ? null : flightIdVal;

                            list.add(new PaymentRecord(
                                rs.getLong("payment_id"),
                                rs.getString("transaction_reference"),
                                rs.getDouble("payment_amount"),
                                rs.getString("payment_method"),
                                rs.getString("payment_status"),
                                pDate,
                                resId,
                                rs.getString("pnr"),
                                resFare,
                                rs.getString("res_status"),
                                bDate,
                                passId,
                                rs.getString("pass_name"),
                                rs.getString("pass_phone"),
                                rs.getString("pass_email"),
                                rs.getString("pass_legacy_pnr"),
                                flightId,
                                rs.getString("flight_number"),
                                rs.getString("airline"),
                                rs.getString("source"),
                                rs.getString("destination"),
                                depTime,
                                refundAmount,
                                cDate,
                                rs.getString("cancel_reason"),
                                rs.getString("cancel_status")
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
                    paymentList.clear();
                    paymentList.addAll(get());
                    paymentTableModel.fireTableDataChanged();

                    int count = paymentList.size();
                    recordCountLabel.setText("Showing " + count + (count == 1 ? " payment" : " payments"));
                    tableCardLayout.show(tableContainer, count == 0 ? "empty" : "table");

                    clearSelection();

                    if (count == 1) {
                        paymentTable.setRowSelectionInterval(0, 0);
                        selectPayment(paymentList.get(0));
                    }
                } catch (Exception ex) {
                    notificationBanner.showMessage("Unable to load payment records. Please check the database connection and try again.", NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    // ==========================================
    // PAYMENT DETAILS MODAL DIALOG
    // ==========================================
    private void showPaymentDetailsModal(PaymentRecord pmt) {
        Component parentComp = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parentComp instanceof java.awt.Frame ? (java.awt.Frame) parentComp : null, "PAYMENT DETAILS — Ref " + pmt.transactionReference(), true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(680, 600);
        dialog.setLocationRelativeTo(parentComp);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(AeroColors.BACKGROUND);
        content.setBorder(new EmptyBorder(20, 24, 20, 24));

        // 1. PAYMENT SECTION
        JPanel payCard = createSectionCard("PAYMENT");
        JPanel payGrid = new JPanel(new GridLayout(3, 2, 12, 8));
        payGrid.setOpaque(false);
        addDetailRow(payGrid, "Payment ID", "#" + pmt.paymentId());
        addDetailRow(payGrid, "Payment Reference", pmt.transactionReference());
        addDetailRow(payGrid, "Payment Status", pmt.paymentStatus());
        addDetailRow(payGrid, "Payment Date", pmt.paymentDate() != null ? pmt.paymentDate().format(DISPLAY_FORMAT) : "—");
        addDetailRow(payGrid, "Payment Method", pmt.paymentMethod() != null ? pmt.paymentMethod() : "—");
        addDetailRow(payGrid, "Payment Amount", "₹" + String.format("%,.2f", pmt.amount()));
        payCard.add(payGrid);
        content.add(payCard);
        content.add(Box.createVerticalStrut(12));

        // 2. RESERVATION SECTION
        JPanel resCard = createSectionCard("RESERVATION");
        JPanel rGrid = new JPanel(new GridLayout(2, 2, 12, 8));
        rGrid.setOpaque(false);
        addDetailRow(rGrid, "PNR", pmt.pnr() != null ? pmt.pnr() : "—");
        addDetailRow(rGrid, "Reservation Status", pmt.reservationStatus() != null ? pmt.reservationStatus() : "—");
        addDetailRow(rGrid, "Reservation Fare", pmt.reservationFare() != null ? ("₹" + String.format("%,.2f", pmt.reservationFare())) : "—");
        addDetailRow(rGrid, "Booking Date", pmt.bookingDate() != null ? pmt.bookingDate().format(DISPLAY_FORMAT) : "—");
        resCard.add(rGrid);
        content.add(resCard);
        content.add(Box.createVerticalStrut(12));

        // 3. PASSENGER SECTION
        JPanel passCard = createSectionCard("PASSENGER");
        JPanel pGrid = new JPanel(new GridLayout(2, 2, 12, 8));
        pGrid.setOpaque(false);
        addDetailRow(pGrid, "Passenger Name", pmt.passengerName() != null ? pmt.passengerName() : "—");
        addDetailRow(pGrid, "Phone", pmt.passengerPhone() != null ? pmt.passengerPhone() : "—");
        addDetailRow(pGrid, "Email", (pmt.passengerEmail() != null && !pmt.passengerEmail().isBlank()) ? pmt.passengerEmail() : "—");
        addDetailRow(pGrid, "Passenger ID", pmt.passengerId() != null ? ("#" + pmt.passengerId()) : "—");
        passCard.add(pGrid);
        content.add(passCard);
        content.add(Box.createVerticalStrut(12));

        // 4. FLIGHT SECTION
        JPanel flightCard = createSectionCard("FLIGHT");
        JPanel fGrid = new JPanel(new GridLayout(2, 2, 12, 8));
        fGrid.setOpaque(false);
        addDetailRow(fGrid, "Flight Number", pmt.flightNumber() != null ? pmt.flightNumber() : "—");
        addDetailRow(fGrid, "Airline", pmt.airline() != null ? pmt.airline() : "—");
        addDetailRow(fGrid, "Origin", pmt.source() != null ? pmt.source() : "—");
        addDetailRow(fGrid, "Destination", pmt.destination() != null ? pmt.destination() : "—");
        flightCard.add(fGrid);
        content.add(flightCard);
        content.add(Box.createVerticalStrut(12));

        // 5. REFUND SECTION
        JPanel refundCard = createSectionCard("REFUND");
        JPanel refGrid = new JPanel(new GridLayout(2, 2, 12, 8));
        refGrid.setOpaque(false);
        if (pmt.refundAmount() != null || pmt.cancellationDate() != null) {
            addDetailRow(refGrid, "Refund Amount", pmt.refundAmount() != null ? ("₹" + String.format("%,.2f", pmt.refundAmount())) : "₹0.00");
            addDetailRow(refGrid, "Refund Date", pmt.cancellationDate() != null ? pmt.cancellationDate().format(DISPLAY_FORMAT) : "—");
            addDetailRow(refGrid, "Cancellation Reason", pmt.cancelReason() != null ? pmt.cancelReason() : "—");
            addDetailRow(refGrid, "Refund Status", pmt.cancelStatus() != null ? pmt.cancelStatus() : "COMPLETED");
        } else {
            addDetailRow(refGrid, "Refund Amount", "—");
            addDetailRow(refGrid, "Refund Date", "—");
            addDetailRow(refGrid, "Cancellation Reason", "—");
            addDetailRow(refGrid, "Refund Status", "—");
        }
        refundCard.add(refGrid);
        content.add(refundCard);

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

        ModernButton receiptBtn = new ModernButton("Print Receipt", ModernButton.Style.SECONDARY);
        receiptBtn.setPreferredSize(new Dimension(120, 36));
        receiptBtn.addActionListener(e -> {
            dialog.dispose();
            showReceiptModal(pmt);
        });
        leftBtns.add(receiptBtn);

        if (pmt.pnr() != null && !pmt.pnr().isBlank()) {
            ModernButton tripBtn = new ModernButton("View Trip", ModernButton.Style.SECONDARY);
            tripBtn.setPreferredSize(new Dimension(100, 36));
            tripBtn.addActionListener(e -> {
                dialog.dispose();
                if (navigationController != null) {
                    navigationController.navigateTo(NavRoute.TRIPS, pmt.pnr());
                }
            });
            leftBtns.add(tripBtn);
        }

        ModernButton closeBtn = new ModernButton("Close", ModernButton.Style.SECONDARY);
        closeBtn.setPreferredSize(new Dimension(90, 36));
        closeBtn.addActionListener(e -> dialog.dispose());

        footer.add(leftBtns, BorderLayout.WEST);
        footer.add(closeBtn, BorderLayout.EAST);
        dialog.add(footer, BorderLayout.SOUTH);

        dialog.setVisible(true);
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
    // DIGITAL PAYMENT RECEIPT MODAL
    // ==========================================
    private void showReceiptModal(PaymentRecord pmt) {
        Component parentComp = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parentComp instanceof java.awt.Frame ? (java.awt.Frame) parentComp : null, "Payment Receipt — " + pmt.transactionReference(), true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(560, 480);
        dialog.setLocationRelativeTo(parentComp);
        dialog.getContentPane().setBackground(AeroColors.BACKGROUND);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(new EmptyBorder(20, 24, 20, 24));

        JPanel receiptCard = new JPanel(new BorderLayout());
        receiptCard.setBackground(Color.WHITE);
        receiptCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(0, 0, 0, 0)
        ));

        // Receipt Banner
        JPanel rHead = new JPanel(new BorderLayout());
        rHead.setBackground(AeroColors.SIDEBAR);
        rHead.setBorder(new EmptyBorder(14, 20, 14, 20));

        JLabel brand = new JLabel("AERODESK  •  PAYMENT RECEIPT");
        brand.setFont(AeroFonts.TITLE_SECTION);
        brand.setForeground(Color.WHITE);

        JLabel statusBadge = new JLabel(pmt.paymentStatus());
        statusBadge.setFont(AeroFonts.CAPTION_BOLD);
        statusBadge.setForeground("COMPLETED".equalsIgnoreCase(pmt.paymentStatus()) || "SUCCESS".equalsIgnoreCase(pmt.paymentStatus()) ? new Color(0x86, 0xEF, 0xAC) : Color.WHITE);

        rHead.add(brand, BorderLayout.WEST);
        rHead.add(statusBadge, BorderLayout.EAST);
        receiptCard.add(rHead, BorderLayout.NORTH);

        // Receipt Body
        JPanel rBody = new JPanel();
        rBody.setLayout(new BoxLayout(rBody, BoxLayout.Y_AXIS));
        rBody.setOpaque(false);
        rBody.setBorder(new EmptyBorder(18, 20, 18, 20));

        JPanel heroRow = new JPanel(new BorderLayout());
        heroRow.setOpaque(false);

        JLabel amountLbl = new JLabel("₹" + String.format("%,.2f", pmt.amount()));
        amountLbl.setFont(AeroFonts.KPI_VALUE);
        amountLbl.setForeground(AeroColors.PRIMARY);

        JLabel refLbl = new JLabel("REF: " + pmt.transactionReference(), SwingConstants.RIGHT);
        refLbl.setFont(AeroFonts.BODY_BOLD);
        refLbl.setForeground(AeroColors.TEXT_SECONDARY);

        heroRow.add(amountLbl, BorderLayout.WEST);
        heroRow.add(refLbl, BorderLayout.EAST);
        rBody.add(heroRow);
        rBody.add(Box.createVerticalStrut(16));

        JPanel rGrid = new JPanel(new GridLayout(3, 2, 14, 10));
        rGrid.setOpaque(false);

        addDetailRow(rGrid, "PASSENGER", pmt.passengerName() != null ? pmt.passengerName() : "—");
        addDetailRow(rGrid, "BOOKING PNR", pmt.pnr() != null ? pmt.pnr() : "—");
        addDetailRow(rGrid, "OPERATING FLIGHT", pmt.flightNumber() != null ? (pmt.flightNumber() + " (" + pmt.airline() + ")") : "—");
        addDetailRow(rGrid, "PAYMENT METHOD", pmt.paymentMethod() != null ? pmt.paymentMethod() : "—");
        addDetailRow(rGrid, "RESERVATION FARE", pmt.reservationFare() != null ? ("₹" + String.format("%,.2f", pmt.reservationFare())) : "—");
        addDetailRow(rGrid, "TRANSACTION DATE", pmt.paymentDate() != null ? pmt.paymentDate().format(DISPLAY_FORMAT) : "—");

        rBody.add(rGrid);
        receiptCard.add(rBody, BorderLayout.CENTER);
        wrapper.add(receiptCard, BorderLayout.CENTER);
        dialog.add(wrapper, BorderLayout.CENTER);

        // Footer Actions
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 12));
        footer.setBackground(Color.WHITE);
        footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, AeroColors.BORDER));

        ModernButton printBtn = new ModernButton("Print Receipt", ModernButton.Style.PRIMARY);
        printBtn.setPreferredSize(new Dimension(120, 36));
        printBtn.addActionListener(e -> {
            JOptionPane.showMessageDialog(dialog, "Payment receipt spooler dispatched for Reference " + pmt.transactionReference() + ".", "Receipt Print", JOptionPane.INFORMATION_MESSAGE);
        });

        ModernButton closeBtn = new ModernButton("Close", ModernButton.Style.SECONDARY);
        closeBtn.setPreferredSize(new Dimension(90, 36));
        closeBtn.addActionListener(e -> dialog.dispose());

        footer.add(printBtn);
        footer.add(closeBtn);
        dialog.add(footer, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    // ==========================================
    // DATA MODEL & TABLE MODEL
    // ==========================================
    public record PaymentRecord(
        long paymentId,
        String transactionReference,
        double amount,
        String paymentMethod,
        String paymentStatus,
        LocalDateTime paymentDate,
        Long reservationId,
        String pnr,
        Double reservationFare,
        String reservationStatus,
        LocalDateTime bookingDate,
        Long passengerId,
        String passengerName,
        String passengerPhone,
        String passengerEmail,
        String passengerLegacyPnr,
        Long flightId,
        String flightNumber,
        String airline,
        String source,
        String destination,
        LocalDateTime departureTime,
        Double refundAmount,
        LocalDateTime cancellationDate,
        String cancelReason,
        String cancelStatus
    ) {}

    private class PaymentTableModel extends AbstractTableModel {
        private final String[] columns = {
            "Payment / Reference", "PNR", "Passenger", "Flight", "Payment Amount", "Reservation Fare", "Payment Method", "Payment Status", "Payment Date"
        };

        @Override public int getRowCount() { return paymentList.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            PaymentRecord p = paymentList.get(row);
            return switch (col) {
                case 0 -> p.transactionReference();
                case 1 -> p.pnr() != null ? p.pnr() : "—";
                case 2 -> p.passengerName() != null ? p.passengerName() : "—";
                case 3 -> p.flightNumber() != null ? (p.flightNumber() + " (" + p.airline() + ")") : "—";
                case 4 -> "₹" + String.format("%,.2f", p.amount());
                case 5 -> p.reservationFare() != null ? ("₹" + String.format("%,.2f", p.reservationFare())) : "—";
                case 6 -> p.paymentMethod() != null ? p.paymentMethod() : "—";
                case 7 -> p.paymentStatus();
                case 8 -> p.paymentDate() != null ? p.paymentDate().format(DISPLAY_FORMAT) : "—";
                default -> "—";
            };
        }

        @Override public boolean isCellEditable(int r, int c) { return false; }
    }
}
