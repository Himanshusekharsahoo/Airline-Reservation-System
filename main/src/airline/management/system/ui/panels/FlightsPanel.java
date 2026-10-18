package airline.management.system.ui.panels;

import airline.management.system.DBConnection;
import airline.management.system.operations.flight.FlightOperationsService;
import airline.management.system.operations.gate.GateService;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.tenant.TenantContext;
import airline.management.system.ui.components.AeroScrollPane;
import airline.management.system.ui.components.ModernButton;
import airline.management.system.ui.components.ModernComboBox;
import airline.management.system.ui.components.ModernTextField;
import airline.management.system.ui.components.NotificationBanner;
import airline.management.system.ui.components.StatusBadge;
import airline.management.system.ui.components.StyledTable;
import airline.management.system.ui.layout.OperationalStation;
import airline.management.system.ui.layout.StationContext;
import airline.management.system.ui.navigation.NavRoute;
import airline.management.system.ui.navigation.NavigationController;
import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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

/**
 * AeroDesk Flight Operations Management Panel
 * Provides enterprise flight search, capacity tracking, CRUD operations, and deletion safeguards.
 */
public class FlightsPanel extends JPanel {
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NavigationController navigationController;

    // Filters
    private final ModernTextField searchField;
    private final ModernComboBox<String> movementFilter;
    private final ModernComboBox<String> statusFilter;
    private final ModernButton refreshBtn;
    private final JLabel countLabel;

    // Station Context Listener
    private final Consumer<OperationalStation> stationListener;

    // Notification Banner
    private final NotificationBanner notificationBanner;

    // Table & Data
    private final StyledTable table;
    private final FlightTableModel tableModel;
    private final List<FlightRecord> flightList = new ArrayList<>();
    private final CardLayout tableCardLayout = new CardLayout();
    private final JPanel tableContainer = new JPanel(tableCardLayout);

    // Row Action Buttons
    private final ModernButton viewBtn;
    private final ModernButton editBtn;
    private final ModernButton deleteBtn;
    private final ModernButton affectedPaxBtn;
    private final ModernButton manageDisruptionBtn;

    // Search generation counter to discard stale results
    private final java.util.concurrent.atomic.AtomicLong flightSearchGen = new java.util.concurrent.atomic.AtomicLong();

    public FlightsPanel(NavigationController controller) {
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

        JLabel title = new JLabel("Flight Operations");
        title.setFont(AeroFonts.TITLE_PAGE);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel subtitle = new JLabel("Manage scheduled flights, operational status, routes, capacity and fares.");
        subtitle.setFont(AeroFonts.BODY);
        subtitle.setForeground(AeroColors.TEXT_SECONDARY);

        titleBlock.add(title);
        titleBlock.add(Box.createVerticalStrut(2));
        titleBlock.add(subtitle);

        ModernButton addFlightBtn = new ModernButton("+ Add Flight", ModernButton.Style.PRIMARY);
        addFlightBtn.setPreferredSize(new Dimension(130, 36));
        addFlightBtn.setEnabled(AuthorizationService.hasPermission(Permission.MANAGE_FLIGHTS));
        addFlightBtn.addActionListener(e -> showFlightForm(null));

        headerPanel.add(titleBlock, BorderLayout.WEST);
        headerPanel.add(addFlightBtn, BorderLayout.EAST);
        mainContent.add(headerPanel);
        mainContent.add(Box.createVerticalStrut(16));

        // 2. Notification Banner
        notificationBanner = new NotificationBanner();
        mainContent.add(notificationBanner);
        mainContent.add(Box.createVerticalStrut(14));

        // 3. Search & Filter Bar
        JPanel filterCard = new JPanel(new BorderLayout(14, 0));
        filterCard.setBackground(Color.WHITE);
        filterCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(12, 16, 12, 16)
        ));

        searchField = new ModernTextField(24);
        searchField.setPlaceholder("Search flights by flight number, airline, origin, or destination...");
        searchField.addActionListener(e -> loadFlightsAsync());

        JPanel filterControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        filterControls.setOpaque(false);

        movementFilter = new ModernComboBox<>(new String[]{"All Movements", "Departures (Origin)", "Arrivals (Destination)"});
        movementFilter.setPreferredSize(new Dimension(165, AeroConstants.INPUT_HEIGHT));
        movementFilter.addActionListener(e -> loadFlightsAsync());

        statusFilter = new ModernComboBox<>(new String[]{"All Statuses", "SCHEDULED", "BOARDING", "DEPARTED", "ARRIVED", "DELAYED", "CANCELLED", "ACTIVE", "INACTIVE"});
        statusFilter.setPreferredSize(new Dimension(140, AeroConstants.INPUT_HEIGHT));
        statusFilter.addActionListener(e -> loadFlightsAsync());

        ModernButton searchBtn = new ModernButton("Search", ModernButton.Style.PRIMARY);
        searchBtn.setPreferredSize(new Dimension(84, AeroConstants.BUTTON_HEIGHT));
        searchBtn.addActionListener(e -> loadFlightsAsync());

        ModernButton resetBtn = new ModernButton("Reset", ModernButton.Style.SECONDARY);
        resetBtn.setPreferredSize(new Dimension(80, AeroConstants.BUTTON_HEIGHT));
        resetBtn.addActionListener(e -> {
            searchField.setText("");
            movementFilter.setSelectedIndex(0);
            statusFilter.setSelectedIndex(0);
            loadFlightsAsync();
        });

        refreshBtn = new ModernButton("↻ Refresh", ModernButton.Style.SECONDARY);
        refreshBtn.setPreferredSize(new Dimension(96, AeroConstants.BUTTON_HEIGHT));
        refreshBtn.addActionListener(e -> loadFlightsAsync());

        filterControls.add(movementFilter);
        filterControls.add(statusFilter);
        filterControls.add(searchBtn);
        filterControls.add(resetBtn);
        filterControls.add(refreshBtn);

        filterCard.add(searchField, BorderLayout.CENTER);
        filterCard.add(filterControls, BorderLayout.EAST);

        mainContent.add(filterCard);
        mainContent.add(Box.createVerticalStrut(18));

        // 4. Main Table Card Container
        JPanel tableCard = new JPanel(new BorderLayout());
        tableCard.setBackground(Color.WHITE);
        tableCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(16, 18, 16, 18)
        ));

        // Table Card Header with Action Buttons
        JPanel tableCardHeader = new JPanel(new BorderLayout());
        tableCardHeader.setOpaque(false);
        tableCardHeader.setBorder(new EmptyBorder(0, 0, 12, 0));

        JPanel leftCount = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftCount.setOpaque(false);

        JLabel opTitle = new JLabel("Flight Operations");
        opTitle.setFont(AeroFonts.TITLE_CARD);
        opTitle.setForeground(AeroColors.TEXT_MAIN);

        countLabel = new JLabel("Loading flights...");
        countLabel.setFont(AeroFonts.CAPTION);
        countLabel.setForeground(AeroColors.TEXT_MUTED);

        leftCount.add(opTitle);
        leftCount.add(new JLabel("•"));
        leftCount.add(countLabel);

        JPanel rowActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rowActions.setOpaque(false);

        viewBtn = new ModernButton("View Details", ModernButton.Style.SECONDARY);
        viewBtn.setPreferredSize(new Dimension(110, 30));
        viewBtn.setFont(AeroFonts.CAPTION_BOLD);
        viewBtn.setEnabled(false);
        viewBtn.addActionListener(e -> {
            FlightRecord sel = getSelectedFlight();
            if (sel != null) showFlightDetails(sel);
        });

        editBtn = new ModernButton("Edit Flight", ModernButton.Style.SECONDARY);
        editBtn.setPreferredSize(new Dimension(96, 30));
        editBtn.setFont(AeroFonts.CAPTION_BOLD);
        editBtn.setEnabled(false);
        editBtn.addActionListener(e -> {
            FlightRecord sel = getSelectedFlight();
            if (sel != null) showFlightForm(sel);
        });

        deleteBtn = new ModernButton("Delete Flight", ModernButton.Style.DANGER);
        deleteBtn.setPreferredSize(new Dimension(106, 30));
        deleteBtn.setFont(AeroFonts.CAPTION_BOLD);
        deleteBtn.setEnabled(false);
        deleteBtn.addActionListener(e -> {
            FlightRecord sel = getSelectedFlight();
            if (sel != null) deleteFlight(sel);
        });

        ModernButton statusBtn = new ModernButton("Operational Status", ModernButton.Style.SECONDARY);
        statusBtn.setPreferredSize(new Dimension(132, 30));
        statusBtn.setFont(AeroFonts.CAPTION_BOLD);
        statusBtn.setEnabled(AuthorizationService.hasPermission(Permission.MANAGE_FLIGHT_OPERATIONS) || AuthorizationService.hasPermission(Permission.MANAGE_FLIGHTS));
        statusBtn.addActionListener(e -> updateOperationalStatus());

        ModernButton releaseGateBtn = new ModernButton("Release Gate", ModernButton.Style.SECONDARY);
        releaseGateBtn.setPreferredSize(new Dimension(104, 30));
        releaseGateBtn.setFont(AeroFonts.CAPTION_BOLD);
        releaseGateBtn.setEnabled(AuthorizationService.hasPermission(Permission.MANAGE_GATES));
        releaseGateBtn.addActionListener(e -> releaseGate());

        affectedPaxBtn = new ModernButton("Affected Pax", ModernButton.Style.SECONDARY);
        affectedPaxBtn.setPreferredSize(new Dimension(104, 30));
        affectedPaxBtn.setFont(AeroFonts.CAPTION_BOLD);
        affectedPaxBtn.setEnabled(false);
        affectedPaxBtn.addActionListener(e -> showAffectedPassengers());

        manageDisruptionBtn = new ModernButton("Manage Disruption", ModernButton.Style.SECONDARY);
        manageDisruptionBtn.setPreferredSize(new Dimension(135, 30));
        manageDisruptionBtn.setFont(AeroFonts.CAPTION_BOLD);
        manageDisruptionBtn.setEnabled(false);
        manageDisruptionBtn.addActionListener(e -> {
            FlightRecord sel = getSelectedFlight();
            if (sel != null && navigationController != null) {
                navigationController.navigateTo(NavRoute.IROPS, sel.flightNumber());
            }
        });

        rowActions.add(viewBtn);
        rowActions.add(editBtn);
        rowActions.add(statusBtn);
        rowActions.add(affectedPaxBtn);
        rowActions.add(manageDisruptionBtn);
        rowActions.add(releaseGateBtn);
        rowActions.add(deleteBtn);

        tableCardHeader.add(leftCount, BorderLayout.WEST);
        tableCardHeader.add(rowActions, BorderLayout.EAST);
        tableCard.add(tableCardHeader, BorderLayout.NORTH);

        // Table Setup
        tableModel = new FlightTableModel();
        table = new StyledTable(tableModel);
        table.getSelectionModel().addListSelectionListener(e -> {
            boolean hasSel = table.getSelectedRow() >= 0;
            boolean canManageFlights = AuthorizationService.hasPermission(Permission.MANAGE_FLIGHTS);
            boolean canManageDisruptions = AuthorizationService.hasPermission(Permission.MANAGE_DISRUPTIONS) || AuthorizationService.hasPermission(Permission.VIEW_DISRUPTIONS);
            viewBtn.setEnabled(hasSel);
            editBtn.setEnabled(hasSel && canManageFlights);
            deleteBtn.setEnabled(hasSel && canManageFlights);
            affectedPaxBtn.setEnabled(hasSel && canManageDisruptions);
            manageDisruptionBtn.setEnabled(hasSel && canManageDisruptions);
        });

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    FlightRecord sel = getSelectedFlight();
                    if (sel != null) showFlightDetails(sel);
                }
            }
        });

        tableContainer.setOpaque(false);
        tableContainer.add(new JScrollPane(table), "table");
        tableContainer.add(createEmptyState(), "empty");
        tableCardLayout.show(tableContainer, "table");

        tableCard.add(tableContainer, BorderLayout.CENTER);
        mainContent.add(tableCard);

        add(new AeroScrollPane(mainContent), BorderLayout.CENTER);

        // Station context change listener
        stationListener = st -> loadFlightsAsync();
        StationContext.addListener(stationListener);

        loadFlightsAsync();
    }

    private FlightRecord getSelectedFlight() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= flightList.size()) return null;
        int modelIdx = table.convertRowIndexToModel(row);
        return flightList.get(modelIdx);
    }

    private JPanel createEmptyState() {
        JPanel emptyPanel = new JPanel();
        emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
        emptyPanel.setBackground(Color.WHITE);
        emptyPanel.setBorder(new EmptyBorder(60, 20, 60, 20));

        JLabel title = new JLabel("No flights found.");
        title.setFont(AeroFonts.BODY_BOLD);
        title.setForeground(AeroColors.TEXT_SECONDARY);
        title.setAlignmentX(CENTER_ALIGNMENT);

        JLabel desc = new JLabel("Try changing your search keywords or status filter, or add a new scheduled flight.");
        desc.setFont(AeroFonts.CAPTION);
        desc.setForeground(AeroColors.TEXT_MUTED);
        desc.setAlignmentX(CENTER_ALIGNMENT);

        emptyPanel.add(Box.createVerticalGlue());
        emptyPanel.add(title);
        emptyPanel.add(Box.createVerticalStrut(4));
        emptyPanel.add(desc);
        emptyPanel.add(Box.createVerticalGlue());

        return emptyPanel;
    }

    public void loadFlightsAsync() {
        refreshBtn.setEnabled(false);
        refreshBtn.setText("Loading...");

        final long gen = flightSearchGen.incrementAndGet();
        String query = searchField.getText().trim();
        String selectedStatus = (String) statusFilter.getSelectedItem();
        String selectedMovement = (String) (movementFilter != null ? movementFilter.getSelectedItem() : "All Movements");

        long tenantOrgId = airline.management.system.tenant.TenantContext.getOrganizationId() != null ?
                airline.management.system.tenant.TenantContext.getOrganizationId() : 1L;

        OperationalStation activeSt = StationContext.getCurrentStation();
        if (activeSt == null) activeSt = OperationalStation.DEL;
        final String stCode = activeSt.getCode();
        final String stCity = activeSt.getCity().split(",")[0].trim();

        SwingWorker<List<FlightRecord>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<FlightRecord> doInBackground() throws Exception {
                List<FlightRecord> records = new ArrayList<>();
                StringBuilder sql = new StringBuilder(
                    "SELECT id, flight_number, airline, source, destination, departure_time, arrival_time, " +
                    "total_seats, available_seats, fare, status FROM flights WHERE organization_id = ?"
                );
                List<Object> params = new ArrayList<>();
                params.add(tenantOrgId);

                // Scope to active operational station based on selected movement direction
                if ("Departures (Origin)".equalsIgnoreCase(selectedMovement)) {
                    sql.append(" AND (source = ? OR source = ?)");
                    params.add(stCode);
                    params.add(stCity);
                } else if ("Arrivals (Destination)".equalsIgnoreCase(selectedMovement)) {
                    sql.append(" AND (destination = ? OR destination = ?)");
                    params.add(stCode);
                    params.add(stCity);
                } else {
                    // All Movements (Departures and Arrivals)
                    sql.append(" AND ((source = ? OR source = ?) OR (destination = ? OR destination = ?))");
                    params.add(stCode);
                    params.add(stCity);
                    params.add(stCode);
                    params.add(stCity);
                }

                if (!query.isEmpty()) {
                    sql.append(" AND (flight_number LIKE ? OR airline LIKE ? OR source LIKE ? OR destination LIKE ?)");
                    String like = "%" + query + "%";
                    params.add(like);
                    params.add(like);
                    params.add(like);
                    params.add(like);
                }

                if (selectedStatus != null && !selectedStatus.equalsIgnoreCase("All Statuses")) {
                    sql.append(" AND status = ?");
                    params.add(selectedStatus);
                }

                sql.append(" ORDER BY departure_time ASC");

                try (Connection c = DBConnection.getConnection();
                     PreparedStatement p = c.prepareStatement(sql.toString())) {
                    for (int i = 0; i < params.size(); i++) {
                        p.setObject(i + 1, params.get(i));
                    }
                    try (ResultSet r = p.executeQuery()) {
                        while (r.next()) {
                            LocalDateTime dep = r.getTimestamp("departure_time").toLocalDateTime();
                            LocalDateTime arr = r.getTimestamp("arrival_time").toLocalDateTime();
                            records.add(new FlightRecord(
                                r.getLong("id"),
                                r.getString("flight_number"),
                                r.getString("airline"),
                                r.getString("source"),
                                r.getString("destination"),
                                dep,
                                arr,
                                r.getInt("total_seats"),
                                r.getInt("available_seats"),
                                r.getDouble("fare"),
                                r.getString("status")
                            ));
                        }
                    }
                }
                return records;
            }

            @Override
            protected void done() {
                refreshBtn.setEnabled(true);
                refreshBtn.setText("↻ Refresh");
                if (gen != flightSearchGen.get()) {
                    return; // Stale search result discarded
                }
                try {
                    flightList.clear();
                    flightList.addAll(get());
                    tableModel.fireTableDataChanged();

                    int count = flightList.size();
                    countLabel.setText("Showing " + count + (count == 1 ? " flight" : " flights") + " • Hub: " + stCode);

                    if (count == 0) {
                        tableCardLayout.show(tableContainer, "empty");
                    } else {
                        tableCardLayout.show(tableContainer, "table");
                    }

                    viewBtn.setEnabled(false);
                    editBtn.setEnabled(false);
                    deleteBtn.setEnabled(false);
                } catch (Exception e) {
                    notificationBanner.showMessage("Unable to load flights. Please verify the database connection and try again.", NotificationBanner.Type.ERROR);
                }
            }
        };

        worker.execute();
    }

    private void showFlightDetails(FlightRecord f) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "Flight Details — " + f.flightNumber, JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setSize(520, 480);
        dialog.setLocationRelativeTo(this);
        dialog.setResizable(false);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Color.WHITE);
        content.setBorder(new EmptyBorder(24, 28, 24, 28));

        // Header Title
        JLabel title = new JLabel(f.flightNumber);
        title.setFont(AeroFonts.TITLE_PAGE);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel sub = new JLabel(f.airline + " • " + f.source + " → " + f.destination);
        sub.setFont(AeroFonts.BODY);
        sub.setForeground(AeroColors.TEXT_SECONDARY);

        content.add(title);
        content.add(Box.createVerticalStrut(4));
        content.add(sub);
        content.add(Box.createVerticalStrut(18));

        // Grid Details Card
        JPanel card = new JPanel(new GridLayout(6, 2, 12, 10));
        card.setBackground(new Color(0xF8, 0xFA, 0xFC));
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(16, 16, 16, 16)
        ));

        addDetailRow(card, "Flight Number", f.flightNumber);
        addDetailRow(card, "Operating Airline", f.airline);
        addDetailRow(card, "Origin", f.source);
        addDetailRow(card, "Destination", f.destination);
        addDetailRow(card, "Departure Schedule", f.departureTime.format(DISPLAY_FORMAT));
        addDetailRow(card, "Arrival Schedule", f.arrivalTime.format(DISPLAY_FORMAT));
        addDetailRow(card, "Total Capacity", f.totalSeats + " seats");
        addDetailRow(card, "Available Capacity", f.availableSeats + " seats");
        addDetailRow(card, "Occupied Seats", f.occupiedSeats() + " seats");
        addDetailRow(card, "Ticket Fare", "₹" + String.format("%,.2f", f.fare));

        content.add(card);
        content.add(Box.createVerticalStrut(16));

        // Status Row
        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        statusRow.setOpaque(false);
        JLabel statusLbl = new JLabel("Operational Status:");
        statusLbl.setFont(AeroFonts.BODY_BOLD);
        statusRow.add(statusLbl);
        statusRow.add(new StatusBadge(f.status));
        content.add(statusRow);
        content.add(Box.createVerticalStrut(20));

        // Close Button
        ModernButton closeBtn = new ModernButton("Close", ModernButton.Style.SECONDARY);
        closeBtn.setAlignmentX(CENTER_ALIGNMENT);
        closeBtn.setPreferredSize(new Dimension(100, 36));
        closeBtn.addActionListener(e -> dialog.dispose());
        content.add(closeBtn);

        dialog.setContentPane(content);
        dialog.setVisible(true);
    }

    private void addDetailRow(JPanel grid, String label, String val) {
        JLabel l = new JLabel(label);
        l.setFont(AeroFonts.CAPTION);
        l.setForeground(AeroColors.TEXT_SECONDARY);

        JLabel v = new JLabel(val);
        v.setFont(AeroFonts.BODY_BOLD);
        v.setForeground(AeroColors.TEXT_MAIN);

        grid.add(l);
        grid.add(v);
    }

    private void showFlightForm(FlightRecord existing) {
        AuthorizationService.requirePermission(Permission.MANAGE_FLIGHTS);
        boolean isEdit = existing != null;
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), isEdit ? "Edit Flight — " + existing.flightNumber : "Add Flight", JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setSize(600, 640);
        dialog.setLocationRelativeTo(this);
        dialog.setResizable(false);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Color.WHITE);
        content.setBorder(new EmptyBorder(24, 28, 24, 28));

        JLabel title = new JLabel(isEdit ? "Edit Flight Details" : "Create Scheduled Flight");
        title.setFont(AeroFonts.TITLE_SECTION);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel sub = new JLabel(isEdit ? "Update flight routes, timetable, capacity, and pricing." : "Configure new operating flight route and passenger capacity.");
        sub.setFont(AeroFonts.CAPTION);
        sub.setForeground(AeroColors.TEXT_SECONDARY);

        content.add(title);
        content.add(Box.createVerticalStrut(2));
        content.add(sub);
        content.add(Box.createVerticalStrut(14));

        NotificationBanner dialogBanner = new NotificationBanner();
        content.add(dialogBanner);
        content.add(Box.createVerticalStrut(10));

        // Form Fields
        ModernTextField numField = new ModernTextField(isEdit ? existing.flightNumber : "", 14);
        ModernTextField airField = new ModernTextField(isEdit ? existing.airline : "", 14);
        ModernTextField srcField = new ModernTextField(isEdit ? existing.source : (StationContext.getCurrentStation() != null ? StationContext.getCurrentStation().getCode() : "DEL"), 14);
        ModernTextField dstField = new ModernTextField(isEdit ? existing.destination : "", 14);
        ModernTextField depField = new ModernTextField(isEdit ? existing.departureTime.format(DISPLAY_FORMAT) : "2030-01-01 09:00", 14);
        ModernTextField arrField = new ModernTextField(isEdit ? existing.arrivalTime.format(DISPLAY_FORMAT) : "2030-01-01 11:00", 14);
        ModernTextField seatsField = new ModernTextField(isEdit ? String.valueOf(existing.totalSeats) : "180", 14);
        ModernTextField fareField = new ModernTextField(isEdit ? String.valueOf(existing.fare) : "2499.00", 14);
        ModernComboBox<String> statusCombo = new ModernComboBox<>(new String[]{"SCHEDULED", "ACTIVE", "CANCELLED", "INACTIVE"});
        if (isEdit) statusCombo.setSelectedItem(existing.status);

        JPanel formGrid = new JPanel(new GridLayout(5, 2, 16, 10));
        formGrid.setOpaque(false);

        addFormField(formGrid, "Flight Number *", numField);
        addFormField(formGrid, "Operating Airline *", airField);
        addFormField(formGrid, "Origin (Source) *", srcField);
        addFormField(formGrid, "Destination *", dstField);
        addFormField(formGrid, "Departure (yyyy-MM-dd HH:mm) *", depField);
        addFormField(formGrid, "Arrival (yyyy-MM-dd HH:mm) *", arrField);
        addFormField(formGrid, "Total Seats *", seatsField);
        addFormField(formGrid, "Ticket Fare (₹) *", fareField);
        addFormField(formGrid, "Operational Status *", statusCombo);

        content.add(formGrid);
        content.add(Box.createVerticalStrut(24));

        // Action Buttons
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        btnRow.setOpaque(false);

        ModernButton cancelBtn = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        cancelBtn.setPreferredSize(new Dimension(90, 36));
        cancelBtn.addActionListener(e -> dialog.dispose());

        ModernButton submitBtn = new ModernButton(isEdit ? "Save Changes" : "Create Flight", ModernButton.Style.PRIMARY);
        submitBtn.setPreferredSize(new Dimension(120, 36));

        submitBtn.addActionListener(e -> {
            try {
                String n = numField.getText().trim();
                String a = airField.getText().trim();
                String s = srcField.getText().trim();
                String d = dstField.getText().trim();

                if (n.isEmpty()) throw new IllegalArgumentException("Flight number is required.");
                if (a.isEmpty()) throw new IllegalArgumentException("Airline name is required.");
                if (s.isEmpty()) throw new IllegalArgumentException("Origin source is required.");
                if (d.isEmpty()) throw new IllegalArgumentException("Destination is required.");
                if (s.equalsIgnoreCase(d)) throw new IllegalArgumentException("Source and destination must differ.");

                LocalDateTime depTime = parseFlexibleDateTime(depField.getText().trim(), "Departure time");
                LocalDateTime arrTime = parseFlexibleDateTime(arrField.getText().trim(), "Arrival time");
                if (!arrTime.isAfter(depTime)) throw new IllegalArgumentException("Arrival must be strictly after departure time.");

                int totalSeats;
                try {
                    totalSeats = Integer.parseInt(seatsField.getText().trim());
                    if (totalSeats <= 0) throw new IllegalArgumentException("Total seats must be greater than 0.");
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Total seats must be a valid positive integer.");
                }

                double fareVal;
                try {
                    fareVal = Double.parseDouble(fareField.getText().trim());
                    if (fareVal < 0) throw new IllegalArgumentException("Fare cannot be negative.");
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Fare must be a valid positive number.");
                }

                String st = (String) statusCombo.getSelectedItem();
                final int finalTotalSeats = totalSeats;
                final double finalFareVal = fareVal;

                submitBtn.setEnabled(false);
                submitBtn.setText("Saving...");
                cancelBtn.setEnabled(false);
                numField.setEnabled(false);
                airField.setEnabled(false);
                srcField.setEnabled(false);
                dstField.setEnabled(false);
                depField.setEnabled(false);
                arrField.setEnabled(false);
                seatsField.setEnabled(false);
                fareField.setEnabled(false);
                statusCombo.setEnabled(false);

                SwingWorker<Void, Void> saveWorker = new SwingWorker<>() {
                    @Override
                    protected Void doInBackground() throws Exception {
                        long tenantOrgId = TenantContext.getOrganizationId() != null ?
                                TenantContext.getOrganizationId() : 1L;
                        try (Connection conn = DBConnection.getConnection()) {
                            if (isEdit) {
                                int occupied = existing.occupiedSeats();
                                if (finalTotalSeats < occupied) {
                                    throw new IllegalArgumentException("Total seats cannot be reduced below currently occupied seats (" + occupied + ").");
                                }
                                String updateSql = "UPDATE flights SET flight_number=?, airline=?, source=?, destination=?, " +
                                        "departure_time=?, arrival_time=?, total_seats=?, available_seats=available_seats+(?-total_seats), " +
                                        "fare=?, status=? WHERE id=? AND organization_id=? AND ? >= total_seats-available_seats";
                                try (PreparedStatement p = conn.prepareStatement(updateSql)) {
                                    p.setString(1, n);
                                    p.setString(2, a);
                                    p.setString(3, s);
                                    p.setString(4, d);
                                    p.setTimestamp(5, Timestamp.valueOf(depTime));
                                    p.setTimestamp(6, Timestamp.valueOf(arrTime));
                                    p.setInt(7, finalTotalSeats);
                                    p.setInt(8, finalTotalSeats);
                                    p.setDouble(9, finalFareVal);
                                    p.setString(10, st);
                                    p.setLong(11, existing.id);
                                    p.setLong(12, tenantOrgId);
                                    p.setInt(13, finalTotalSeats);
                                    if (p.executeUpdate() == 0) {
                                        throw new IllegalArgumentException("Capacity cannot be below occupied seats, or flight was not found.");
                                    }
                                }
                            } else {
                                String insertSql = "INSERT INTO flights (organization_id, flight_number, airline, source, destination, departure_time, " +
                                        "arrival_time, total_seats, available_seats, fare, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                                try (PreparedStatement p = conn.prepareStatement(insertSql)) {
                                    p.setLong(1, tenantOrgId);
                                    p.setString(2, n);
                                    p.setString(3, a);
                                    p.setString(4, s);
                                    p.setString(5, d);
                                    p.setTimestamp(6, Timestamp.valueOf(depTime));
                                    p.setTimestamp(7, Timestamp.valueOf(arrTime));
                                    p.setInt(8, finalTotalSeats);
                                    p.setInt(9, finalTotalSeats);
                                    p.setDouble(10, finalFareVal);
                                    p.setString(11, st);
                                    p.executeUpdate();
                                }
                            }
                        }
                        return null;
                    }

                    @Override
                    protected void done() {
                        submitBtn.setEnabled(true);
                        submitBtn.setText(isEdit ? "Save Changes" : "Create Flight");
                        cancelBtn.setEnabled(true);
                        numField.setEnabled(true);
                        airField.setEnabled(true);
                        srcField.setEnabled(true);
                        dstField.setEnabled(true);
                        depField.setEnabled(true);
                        arrField.setEnabled(true);
                        seatsField.setEnabled(true);
                        fareField.setEnabled(true);
                        statusCombo.setEnabled(true);
                        try {
                            get();
                            dialog.dispose();
                            notificationBanner.showMessage(isEdit ? "Flight updated successfully." : "Flight created successfully.", NotificationBanner.Type.SUCCESS);
                            loadFlightsAsync();
                        } catch (Exception ex) {
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            if (cause instanceof IllegalArgumentException) {
                                dialogBanner.showMessage(cause.getMessage(), NotificationBanner.Type.WARNING);
                            } else if (cause instanceof SQLException) {
                                dialogBanner.showMessage(isEdit ? "Unable to update flight. Flight number may already exist." : "Unable to create flight. Flight number may already exist.", NotificationBanner.Type.ERROR);
                            } else {
                                dialogBanner.showMessage("Failed to save flight: " + cause.getMessage(), NotificationBanner.Type.ERROR);
                            }
                        }
                    }
                };
                saveWorker.execute();

            } catch (IllegalArgumentException ex) {
                dialogBanner.showMessage(ex.getMessage(), NotificationBanner.Type.WARNING);
            }
        });

        btnRow.add(cancelBtn);
        btnRow.add(submitBtn);
        content.add(btnRow);

        dialog.setContentPane(content);
        dialog.setVisible(true);
    }

    private void addFormField(JPanel container, String labelText, Component field) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);

        JLabel l = new JLabel(labelText);
        l.setFont(AeroFonts.CAPTION_BOLD);
        l.setForeground(AeroColors.TEXT_MAIN);

        p.add(l);
        p.add(Box.createVerticalStrut(4));
        p.add(field);

        container.add(p);
    }

    private LocalDateTime parseFlexibleDateTime(String text, String fieldName) {
        text = text.trim().replace('T', ' ');
        try {
            if (text.length() == 16) {
                return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            }
            if (text.length() == 19) {
                return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            return LocalDateTime.parse(text);
        } catch (Exception ex) {
            throw new IllegalArgumentException(fieldName + " must match format 'yyyy-MM-dd HH:mm'.");
        }
    }

    private void updateOperationalStatus() {
        FlightRecord flight = getSelectedFlight();
        if (flight == null) {
            notificationBanner.showMessage("Select a flight first.", NotificationBanner.Type.WARNING);
            return;
        }
        ModernComboBox<String> status = new ModernComboBox<>(new String[]{"SCHEDULED", "BOARDING", "DEPARTED", "ARRIVED", "DELAYED", "CANCELLED"});
        status.setSelectedItem("ACTIVE".equalsIgnoreCase(flight.status) ? "SCHEDULED" : flight.status);
        ModernTextField reason = new ModernTextField(24);
        JPanel form = new JPanel(new GridLayout(2, 2, 8, 8));
        form.add(new JLabel("New status")); form.add(status);
        form.add(new JLabel("Reason (optional)")); form.add(reason);
        if (JOptionPane.showConfirmDialog(this, form, "Update " + flight.flightNumber + " operational status", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                FlightOperationsService.transition(flight.id, FlightOperationsService.Status.valueOf((String) status.getSelectedItem()), reason.getText());
                return null;
            }
            @Override protected void done() {
                try { get(); notificationBanner.showMessage("Operational status updated.", NotificationBanner.Type.SUCCESS); loadFlightsAsync(); }
                catch (Exception e) { notificationBanner.showMessage(rootMessage(e), NotificationBanner.Type.ERROR); }
            }
        }.execute();
    }

    private static String rootMessage(Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? "Operation could not be completed." : cause.getMessage();
    }

    private void releaseGate() {
        FlightRecord flight = getSelectedFlight();
        if (flight == null) { notificationBanner.showMessage("Select a flight first.", NotificationBanner.Type.WARNING); return; }
        if (JOptionPane.showConfirmDialog(this, "Release the assigned gate for " + flight.flightNumber + "?", "Release Gate", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { GateService.getInstance().releaseFlightGate(flight.id); return null; }
            @Override protected void done() {
                try { get(); notificationBanner.showMessage("Gate released and available for the next operation.", NotificationBanner.Type.SUCCESS); }
                catch (Exception e) { notificationBanner.showMessage(rootMessage(e), NotificationBanner.Type.ERROR); }
            }
        }.execute();
    }

    private void deleteFlight(FlightRecord f) {
        AuthorizationService.requirePermission(Permission.MANAGE_FLIGHTS);
        int confirm = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to delete flight " + f.flightNumber + "?\nThis action cannot be undone.",
            "Delete Flight",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (confirm != JOptionPane.YES_OPTION) return;

        viewBtn.setEnabled(false);
        editBtn.setEnabled(false);
        deleteBtn.setEnabled(false);
        deleteBtn.setText("Deleting...");

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            private String errorMessage = null;

            @Override
            protected Void doInBackground() {
                long tenantOrgId = TenantContext.getOrganizationId() != null ?
                        TenantContext.getOrganizationId() : 1L;
                try (Connection conn = DBConnection.getConnection()) {
                    // Check deletion safeguard: cannot delete flight with reservations
                    try (PreparedStatement check = conn.prepareStatement("SELECT 1 FROM reservations WHERE flight_id=? AND organization_id=? LIMIT 1")) {
                        check.setLong(1, f.id);
                        check.setLong(2, tenantOrgId);
                        try (ResultSet r = check.executeQuery()) {
                            if (r.next()) {
                                errorMessage = "This flight cannot be deleted because it has existing reservations.";
                                return null;
                            }
                        }
                    }

                    // Perform deletion
                    try (PreparedStatement del = conn.prepareStatement("DELETE FROM flights WHERE id=? AND organization_id=?")) {
                        del.setLong(1, f.id);
                        del.setLong(2, tenantOrgId);
                        if (del.executeUpdate() == 0) {
                            errorMessage = "Flight not found or already deleted.";
                        }
                    }
                } catch (SQLException ex) {
                    errorMessage = "Unable to delete flight. Please verify database connection.";
                }
                return null;
            }

            @Override
            protected void done() {
                deleteBtn.setText("Delete Flight");
                if (errorMessage != null) {
                    notificationBanner.showMessage(errorMessage, NotificationBanner.Type.ERROR);
                    boolean hasSel = table.getSelectedRow() >= 0;
                    boolean canManage = AuthorizationService.hasPermission(Permission.MANAGE_FLIGHTS);
                    boolean canManageDisruptions = AuthorizationService.hasPermission(Permission.MANAGE_DISRUPTIONS) || AuthorizationService.hasPermission(Permission.VIEW_DISRUPTIONS);
                    viewBtn.setEnabled(hasSel);
                    editBtn.setEnabled(hasSel && canManage);
                    deleteBtn.setEnabled(hasSel && canManage);
                    affectedPaxBtn.setEnabled(hasSel && canManageDisruptions);
                    manageDisruptionBtn.setEnabled(hasSel && canManageDisruptions);
                } else {
                    notificationBanner.showMessage("Flight deleted successfully.", NotificationBanner.Type.SUCCESS);
                    loadFlightsAsync();
                }
            }
        };

        worker.execute();
    }

    private void showAffectedPassengers() {
        FlightRecord sel = getSelectedFlight();
        if (sel == null) return;
        java.awt.Frame owner = (java.awt.Frame) SwingUtilities.getWindowAncestor(this);
        AffectedPassengersDialog dialog = new AffectedPassengersDialog(
            owner,
            sel.id(),
            sel.flightNumber(),
            sel.source() + " → " + sel.destination(),
            sel.status()
        );
        dialog.setVisible(true);
    }

    public record FlightRecord(
        long id,
        String flightNumber,
        String airline,
        String source,
        String destination,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        int totalSeats,
        int availableSeats,
        double fare,
        String status
    ) {
        public int occupiedSeats() {
            return Math.max(0, totalSeats - availableSeats);
        }
    }

    private class FlightTableModel extends AbstractTableModel {
        private final String[] columns = {"Flight", "Airline", "Route", "Departure", "Arrival", "Seats", "Fare", "Status"};

        @Override public int getRowCount() { return flightList.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            FlightRecord r = flightList.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> r.flightNumber;
                case 1 -> r.airline;
                case 2 -> r.source + " → " + r.destination;
                case 3 -> r.departureTime.format(DISPLAY_FORMAT);
                case 4 -> r.arrivalTime.format(DISPLAY_FORMAT);
                case 5 -> r.availableSeats + " / " + r.totalSeats;
                case 6 -> "₹" + String.format("%,.2f", r.fare);
                case 7 -> r.status;
                default -> "—";
            };
        }

        @Override public boolean isCellEditable(int rowIndex, int columnIndex) { return false; }
    }

    public void unregisterStationListener() {
        StationContext.removeListener(stationListener);
    }
}
