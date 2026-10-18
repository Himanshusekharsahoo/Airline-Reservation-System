package airline.management.system.ui.panels;

import airline.management.system.PassengerService;
import airline.management.system.PassengerService.PassengerRecord;
import airline.management.system.PassengerService.PassengerReservationRecord;
import airline.management.system.ui.components.AeroScrollPane;
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
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;

/**
 * AeroDesk Enterprise Passenger Management Panel
 * Provides passenger directory search, filtering, CRUD operations,
 * reservation history inspection, and deletion safeguards.
 */
public class PassengersPanel extends JPanel {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NavigationController navigationController;

    // Filters
    private final ModernTextField searchField;
    private final ModernComboBox<String> resFilter;
    private final ModernButton refreshBtn;
    private final JLabel countLabel;

    // Notification Banner
    private final NotificationBanner notificationBanner;

    // Table & Data
    private final StyledTable table;
    private final PassengerTableModel tableModel;
    private final List<PassengerRecord> passengerList = new ArrayList<>();
    private final CardLayout tableCardLayout = new CardLayout();
    private final JPanel tableContainer = new JPanel(tableCardLayout);

    // Row Action Buttons
    private final ModernButton viewBtn;
    private final ModernButton editBtn;
    private final ModernButton deleteBtn;

    // Search generation counter to discard stale results
    private final java.util.concurrent.atomic.AtomicLong passengerSearchGen = new java.util.concurrent.atomic.AtomicLong();

    public PassengersPanel(NavigationController controller) {
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

        JLabel title = new JLabel("Passengers");
        title.setFont(AeroFonts.TITLE_PAGE);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel subtitle = new JLabel("Manage passenger records, contact information, and reservation history.");
        subtitle.setFont(AeroFonts.BODY);
        subtitle.setForeground(AeroColors.TEXT_SECONDARY);

        titleBlock.add(title);
        titleBlock.add(Box.createVerticalStrut(2));
        titleBlock.add(subtitle);

        ModernButton addPassengerBtn = new ModernButton("+ Add Passenger", ModernButton.Style.PRIMARY);
        addPassengerBtn.setPreferredSize(new Dimension(150, 36));
        addPassengerBtn.addActionListener(e -> showPassengerForm(null));

        headerPanel.add(titleBlock, BorderLayout.WEST);
        headerPanel.add(addPassengerBtn, BorderLayout.EAST);
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
        searchField.setPlaceholder("Search passengers by name, phone, email, or PNR...");
        searchField.addActionListener(e -> loadPassengersAsync(false));

        JPanel filterControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        filterControls.setOpaque(false);

        resFilter = new ModernComboBox<>(new String[]{"All Passengers", "With Reservations", "No Reservations"});
        resFilter.setPreferredSize(new Dimension(160, AeroConstants.INPUT_HEIGHT));
        resFilter.addActionListener(e -> loadPassengersAsync(false));

        ModernButton searchBtn = new ModernButton("Search", ModernButton.Style.PRIMARY);
        searchBtn.setPreferredSize(new Dimension(84, AeroConstants.BUTTON_HEIGHT));
        searchBtn.addActionListener(e -> loadPassengersAsync(false));

        ModernButton resetBtn = new ModernButton("Reset", ModernButton.Style.SECONDARY);
        resetBtn.setPreferredSize(new Dimension(80, AeroConstants.BUTTON_HEIGHT));
        resetBtn.addActionListener(e -> {
            searchField.setText("");
            resFilter.setSelectedIndex(0);
            loadPassengersAsync(false);
        });

        refreshBtn = new ModernButton("↻ Refresh", ModernButton.Style.SECONDARY);
        refreshBtn.setPreferredSize(new Dimension(96, AeroConstants.BUTTON_HEIGHT));
        refreshBtn.addActionListener(e -> loadPassengersAsync(true));

        filterControls.add(resFilter);
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

        JLabel opTitle = new JLabel("Passenger Directory");
        opTitle.setFont(AeroFonts.TITLE_CARD);
        opTitle.setForeground(AeroColors.TEXT_MAIN);

        countLabel = new JLabel("Loading passengers...");
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
            PassengerRecord sel = getSelectedPassenger();
            if (sel != null) showPassengerDetails(sel);
        });

        editBtn = new ModernButton("Edit Passenger", ModernButton.Style.SECONDARY);
        editBtn.setPreferredSize(new Dimension(116, 30));
        editBtn.setFont(AeroFonts.CAPTION_BOLD);
        editBtn.setEnabled(false);
        editBtn.addActionListener(e -> {
            PassengerRecord sel = getSelectedPassenger();
            if (sel != null) showPassengerForm(sel);
        });

        deleteBtn = new ModernButton("Delete Passenger", ModernButton.Style.DANGER);
        deleteBtn.setPreferredSize(new Dimension(130, 30));
        deleteBtn.setFont(AeroFonts.CAPTION_BOLD);
        deleteBtn.setEnabled(false);
        deleteBtn.addActionListener(e -> {
            PassengerRecord sel = getSelectedPassenger();
            if (sel != null) deletePassenger(sel);
        });

        rowActions.add(viewBtn);
        rowActions.add(editBtn);
        rowActions.add(deleteBtn);

        tableCardHeader.add(leftCount, BorderLayout.WEST);
        tableCardHeader.add(rowActions, BorderLayout.EAST);
        tableCard.add(tableCardHeader, BorderLayout.NORTH);

        // Table Setup
        tableModel = new PassengerTableModel();
        table = new StyledTable(tableModel);
        table.getSelectionModel().addListSelectionListener(e -> {
            boolean hasSel = table.getSelectedRow() >= 0;
            viewBtn.setEnabled(hasSel);
            editBtn.setEnabled(hasSel);
            deleteBtn.setEnabled(hasSel);
        });

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    PassengerRecord sel = getSelectedPassenger();
                    if (sel != null) showPassengerDetails(sel);
                }
            }
        });

        // Set column widths
        table.getColumnModel().getColumn(0).setPreferredWidth(210); // Passenger / Name
        table.getColumnModel().getColumn(1).setPreferredWidth(100); // PNR
        table.getColumnModel().getColumn(2).setPreferredWidth(130); // Phone
        table.getColumnModel().getColumn(3).setPreferredWidth(210); // Email
        table.getColumnModel().getColumn(4).setPreferredWidth(70);  // Age
        table.getColumnModel().getColumn(5).setPreferredWidth(120); // Reservations

        tableContainer.setOpaque(false);
        tableContainer.add(new JScrollPane(table), "table");
        tableContainer.add(createEmptyState(), "empty");
        tableCardLayout.show(tableContainer, "table");

        tableCard.add(tableContainer, BorderLayout.CENTER);
        mainContent.add(tableCard);

        add(new AeroScrollPane(mainContent), BorderLayout.CENTER);

        // Register navigation refresh
        if (controller != null) {
            controller.addListener((route, param) -> {
                if (route == NavRoute.PASSENGERS) {
                    loadPassengersAsync(false);
                }
            });
        }

        loadPassengersAsync(false);
    }

    private PassengerRecord getSelectedPassenger() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= passengerList.size()) return null;
        int modelIdx = table.convertRowIndexToModel(row);
        return passengerList.get(modelIdx);
    }

    private JPanel createEmptyState() {
        JPanel emptyPanel = new JPanel();
        emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
        emptyPanel.setBackground(Color.WHITE);
        emptyPanel.setBorder(new EmptyBorder(60, 20, 60, 20));

        JLabel title = new JLabel("No passengers found.");
        title.setFont(AeroFonts.BODY_BOLD);
        title.setForeground(AeroColors.TEXT_SECONDARY);
        title.setAlignmentX(CENTER_ALIGNMENT);

        JLabel desc = new JLabel("Try changing your search criteria or add a new passenger.");
        desc.setFont(AeroFonts.CAPTION);
        desc.setForeground(AeroColors.TEXT_MUTED);
        desc.setAlignmentX(CENTER_ALIGNMENT);

        ModernButton addBtn = new ModernButton("+ Add Passenger", ModernButton.Style.PRIMARY);
        addBtn.setAlignmentX(CENTER_ALIGNMENT);
        addBtn.setPreferredSize(new Dimension(150, 36));
        addBtn.addActionListener(e -> showPassengerForm(null));

        emptyPanel.add(Box.createVerticalGlue());
        emptyPanel.add(title);
        emptyPanel.add(Box.createVerticalStrut(6));
        emptyPanel.add(desc);
        emptyPanel.add(Box.createVerticalStrut(18));
        emptyPanel.add(addBtn);
        emptyPanel.add(Box.createVerticalGlue());

        return emptyPanel;
    }

    public void loadPassengersAsync() {
        loadPassengersAsync(false);
    }

    public void loadPassengersAsync(boolean isManualRefresh) {
        refreshBtn.setEnabled(false);
        refreshBtn.setText("Loading...");

        final long gen = passengerSearchGen.incrementAndGet();
        String query = searchField.getText().trim();
        String selectedFilter = (String) resFilter.getSelectedItem();

        SwingWorker<List<PassengerRecord>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<PassengerRecord> doInBackground() throws Exception {
                return PassengerService.searchPassengers(query, selectedFilter);
            }

            @Override
            protected void done() {
                refreshBtn.setEnabled(true);
                refreshBtn.setText("↻ Refresh");
                if (gen != passengerSearchGen.get()) {
                    return; // Stale search result discarded
                }
                try {
                    passengerList.clear();
                    passengerList.addAll(get());
                    tableModel.fireTableDataChanged();

                    int count = passengerList.size();
                    countLabel.setText("Showing " + count + (count == 1 ? " passenger" : " passengers"));

                    if (count == 0) {
                        tableCardLayout.show(tableContainer, "empty");
                    } else {
                        tableCardLayout.show(tableContainer, "table");
                    }

                    viewBtn.setEnabled(false);
                    editBtn.setEnabled(false);
                    deleteBtn.setEnabled(false);

                    if (isManualRefresh) {
                        notificationBanner.showMessage("Passenger list refreshed.", NotificationBanner.Type.INFO);
                    }
                } catch (Exception e) {
                    notificationBanner.showMessage("Unable to load passengers. Please check the database connection and try again.", NotificationBanner.Type.ERROR);
                }
            }
        };

        worker.execute();
    }

    private void showPassengerDetails(PassengerRecord p) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "Passenger Details — " + p.name(), JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setSize(640, 620);
        dialog.setLocationRelativeTo(this);
        dialog.setResizable(false);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Color.WHITE);
        content.setBorder(new EmptyBorder(24, 28, 24, 28));

        // Header Title
        JLabel title = new JLabel(p.name());
        title.setFont(AeroFonts.TITLE_PAGE);
        title.setForeground(AeroColors.TEXT_MAIN);

        String subText = "PNR: " + (p.displayPnr() != null && !p.displayPnr().isBlank() ? p.displayPnr() : "—") +
                         " • Phone: " + p.phone() +
                         (p.email() != null && !p.email().isBlank() ? " • " + p.email() : "");
        JLabel sub = new JLabel(subText);
        sub.setFont(AeroFonts.BODY);
        sub.setForeground(AeroColors.TEXT_SECONDARY);

        content.add(title);
        content.add(Box.createVerticalStrut(4));
        content.add(sub);
        content.add(Box.createVerticalStrut(18));

        // Section 1: Passenger Information
        JLabel infoHeader = new JLabel("PASSENGER INFORMATION");
        infoHeader.setFont(AeroFonts.CAPTION_BOLD);
        infoHeader.setForeground(AeroColors.TEXT_SECONDARY);
        content.add(infoHeader);
        content.add(Box.createVerticalStrut(8));

        JPanel infoCard = new JPanel(new GridLayout(5, 2, 16, 8));
        infoCard.setBackground(new Color(0xF8, 0xFA, 0xFC));
        infoCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(12, 14, 12, 14)
        ));

        addDetailRow(infoCard, "Full Name", p.name());
        addDetailRow(infoCard, "PNR / Legacy PNR", p.displayPnr() != null && !p.displayPnr().isBlank() ? p.displayPnr() : "—");
        addDetailRow(infoCard, "Phone Number", p.phone());
        addDetailRow(infoCard, "Email Address", p.email() != null && !p.email().isBlank() ? p.email() : "—");
        addDetailRow(infoCard, "Age", p.age() != null ? p.age() + " years" : "—");
        addDetailRow(infoCard, "Gender", p.gender() != null && !p.gender().isBlank() ? p.gender() : "—");
        addDetailRow(infoCard, "Address", p.address() != null && !p.address().isBlank() ? p.address() : "—");
        addDetailRow(infoCard, "System ID", "#" + p.id());
        addDetailRow(infoCard, "Registered Date", p.createdAt() != null ? p.createdAt().toLocalDateTime().format(DATE_FORMAT) : "—");
        addDetailRow(infoCard, "Total Bookings", p.reservationCount() + (p.reservationCount() == 1 ? " reservation" : " reservations"));

        content.add(infoCard);
        content.add(Box.createVerticalStrut(18));

        // Section 2: Reservation History
        JLabel resHeader = new JLabel("RESERVATION HISTORY");
        resHeader.setFont(AeroFonts.CAPTION_BOLD);
        resHeader.setForeground(AeroColors.TEXT_SECONDARY);
        content.add(resHeader);
        content.add(Box.createVerticalStrut(8));

        JPanel resPanel = new JPanel(new BorderLayout());
        resPanel.setBackground(Color.WHITE);

        String[] resCols = {"Flight", "PNR", "Route", "Seat", "Fare", "Status", "Date"};
        DefaultTableModel resModel = new DefaultTableModel(resCols, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        StyledTable resTable = new StyledTable(resModel);

        JLabel loadingOrEmpty = new JLabel("Loading reservation history...", SwingConstants.CENTER);
        loadingOrEmpty.setFont(AeroFonts.CAPTION);
        loadingOrEmpty.setForeground(AeroColors.TEXT_MUTED);
        loadingOrEmpty.setBorder(new EmptyBorder(24, 0, 24, 0));

        CardLayout resCardLayout = new CardLayout();
        JPanel resContainer = new JPanel(resCardLayout);
        resContainer.setOpaque(false);
        resContainer.setBorder(BorderFactory.createLineBorder(AeroColors.BORDER, 1));

        JScrollPane resScroll = new JScrollPane(resTable);
        resScroll.setPreferredSize(new Dimension(580, 130));
        resScroll.setBorder(BorderFactory.createEmptyBorder());

        resContainer.add(loadingOrEmpty, "message");
        resContainer.add(resScroll, "table");
        resCardLayout.show(resContainer, "message");
        resPanel.add(resContainer, BorderLayout.CENTER);
        content.add(resPanel);
        content.add(Box.createVerticalStrut(20));

        // Close Button
        ModernButton closeBtn = new ModernButton("Close", ModernButton.Style.SECONDARY);
        closeBtn.setAlignmentX(CENTER_ALIGNMENT);
        closeBtn.setPreferredSize(new Dimension(100, 36));
        closeBtn.addActionListener(e -> dialog.dispose());
        content.add(closeBtn);

        // Fetch Reservation History Async
        SwingWorker<List<Object[]>, Void> resWorker = new SwingWorker<>() {
            @Override
            protected List<Object[]> doInBackground() throws Exception {
                List<Object[]> rows = new ArrayList<>();
                List<PassengerReservationRecord> history = PassengerService.getReservationHistory(p.id());
                for (PassengerReservationRecord r : history) {
                    String dateStr = r.bookingDate() != null ? r.bookingDate().toLocalDateTime().format(DATE_FORMAT) : "—";
                    rows.add(new Object[]{
                        r.flightNumber(),
                        r.pnr(),
                        r.route(),
                        r.seatNumber() == null || r.seatNumber().isBlank() ? "—" : r.seatNumber(),
                        "₹" + String.format("%,.2f", r.fare()),
                        r.status(),
                        dateStr
                    });
                }
                return rows;
            }

            @Override
            protected void done() {
                try {
                    List<Object[]> data = get();
                    if (data.isEmpty()) {
                        loadingOrEmpty.setText("No reservation history found for this passenger.");
                        resCardLayout.show(resContainer, "message");
                    } else {
                        for (Object[] r : data) {
                            resModel.addRow(r);
                        }
                        resCardLayout.show(resContainer, "table");
                    }
                } catch (Exception ex) {
                    loadingOrEmpty.setText("Unable to load reservation history.");
                    resCardLayout.show(resContainer, "message");
                }
            }
        };
        resWorker.execute();

        dialog.setContentPane(content);
        dialog.setVisible(true);
    }

    private void addDetailRow(JPanel grid, String label, String val) {
        JPanel cell = new JPanel();
        cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
        cell.setOpaque(false);

        JLabel l = new JLabel(label);
        l.setFont(AeroFonts.CAPTION);
        l.setForeground(AeroColors.TEXT_SECONDARY);

        JLabel v = new JLabel(val != null && !val.isBlank() ? val : "—");
        v.setFont(AeroFonts.BODY_BOLD);
        v.setForeground(AeroColors.TEXT_MAIN);

        cell.add(l);
        cell.add(Box.createVerticalStrut(2));
        cell.add(v);

        grid.add(cell);
    }

    private void showPassengerForm(PassengerRecord existing) {
        boolean isEdit = existing != null;
        JDialog dialog = new JDialog(
            SwingUtilities.getWindowAncestor(this),
            isEdit ? "Edit Passenger — " + existing.name() : "Add Passenger",
            JDialog.ModalityType.APPLICATION_MODAL
        );
        dialog.setSize(580, isEdit ? 620 : 540);
        dialog.setLocationRelativeTo(this);
        dialog.setResizable(false);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Color.WHITE);
        content.setBorder(new EmptyBorder(24, 28, 24, 28));

        JLabel title = new JLabel(isEdit ? "Edit Passenger Details" : "Add New Passenger");
        title.setFont(AeroFonts.TITLE_SECTION);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel sub = new JLabel(isEdit ? "Update passenger contact information and personal details." : "Register a new passenger profile for flight bookings.");
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
        ModernTextField idField = null;
        ModernTextField pnrField = null;
        if (isEdit) {
            idField = new ModernTextField("#" + existing.id(), 14);
            idField.setEnabled(false);
            idField.setEditable(false);

            String pnrVal = existing.displayPnr() != null && !existing.displayPnr().isBlank() ? existing.displayPnr() : "—";
            pnrField = new ModernTextField(pnrVal, 14);
            pnrField.setEnabled(false);
            pnrField.setEditable(false);
        }

        ModernTextField nameField = new ModernTextField(isEdit ? existing.name() : "", 14);
        nameField.setPlaceholder("e.g. Jane Doe");

        ModernTextField phoneField = new ModernTextField(isEdit ? existing.phone() : "", 14);
        phoneField.setPlaceholder("e.g. 09272777334 or +1 555-123-4567");

        ModernTextField emailField = new ModernTextField(isEdit && existing.email() != null ? existing.email() : "", 14);
        emailField.setPlaceholder("e.g. passenger@example.com (optional)");

        ModernTextField ageField = new ModernTextField(isEdit && existing.age() != null ? String.valueOf(existing.age()) : "", 14);
        ageField.setPlaceholder("e.g. 32 (optional)");

        ModernComboBox<String> genderCombo = new ModernComboBox<>(new String[]{"Select Gender", "Male", "Female", "Other"});
        if (isEdit && existing.gender() != null) {
            for (int i = 0; i < genderCombo.getItemCount(); i++) {
                if (genderCombo.getItemAt(i).equalsIgnoreCase(existing.gender())) {
                    genderCombo.setSelectedIndex(i);
                    break;
                }
            }
        }

        ModernTextField addressField = new ModernTextField(isEdit && existing.address() != null ? existing.address() : "", 14);
        addressField.setPlaceholder("e.g. City, Country (optional)");

        JPanel formGrid = new JPanel(new GridLayout(isEdit ? 4 : 3, 2, 16, 12));
        formGrid.setOpaque(false);

        if (isEdit) {
            addFormField(formGrid, "System ID (Immutable)", idField);
            addFormField(formGrid, "PNR / Identifier (Immutable)", pnrField);
        }

        addFormField(formGrid, "Full Name *", nameField);
        addFormField(formGrid, "Phone Number *", phoneField);
        addFormField(formGrid, "Email Address", emailField);
        addFormField(formGrid, "Age (0 - 120)", ageField);
        addFormField(formGrid, "Gender", genderCombo);
        addFormField(formGrid, "Address", addressField);

        content.add(formGrid);
        content.add(Box.createVerticalStrut(24));

        // Action Buttons
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        btnRow.setOpaque(false);

        ModernButton cancelBtn = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        cancelBtn.setPreferredSize(new Dimension(90, 36));
        cancelBtn.addActionListener(e -> dialog.dispose());

        ModernButton submitBtn = new ModernButton(isEdit ? "Save Changes" : "Add Passenger", ModernButton.Style.PRIMARY);
        submitBtn.setPreferredSize(new Dimension(130, 36));

        submitBtn.addActionListener(e -> {
            try {
                String n = nameField.getText().trim();
                String ph = phoneField.getText().trim();
                String em = emailField.getText().trim();
                String agStr = ageField.getText().trim();
                String gen = (String) genderCombo.getSelectedItem();
                String addr = addressField.getText().trim();

                if (n.isEmpty()) throw new IllegalArgumentException("Passenger name is required.");
                if (n.length() > 100) throw new IllegalArgumentException("Passenger name cannot exceed 100 characters.");

                if (ph.isEmpty()) throw new IllegalArgumentException("Please enter a valid phone number.");
                if (!ph.matches("[+0-9() -]{7,30}")) throw new IllegalArgumentException("Please enter a valid phone number.");
                if (ph.length() > 30) throw new IllegalArgumentException("Phone number cannot exceed 30 characters.");

                if (!em.isEmpty()) {
                    if (!em.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Please enter a valid email address.");
                    if (em.length() > 254) throw new IllegalArgumentException("Email address cannot exceed 254 characters.");
                }

                Integer ageVal = null;
                if (!agStr.isEmpty()) {
                    try {
                        ageVal = Integer.parseInt(agStr);
                        if (ageVal < 0 || ageVal > 120) throw new IllegalArgumentException("Age must be between 0 and 120.");
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("Age must be a valid number.");
                    }
                }

                String genderVal = (gen != null && !gen.equalsIgnoreCase("Select Gender")) ? gen : null;
                String addressVal = !addr.isEmpty() ? addr : null;
                String emailVal = !em.isEmpty() ? em : null;

                submitBtn.setEnabled(false);
                submitBtn.setText("Saving...");
                cancelBtn.setEnabled(false);
                nameField.setEnabled(false);
                phoneField.setEnabled(false);
                emailField.setEnabled(false);
                ageField.setEnabled(false);
                genderCombo.setEnabled(false);
                addressField.setEnabled(false);

                final Integer finalAge = ageVal;
                SwingWorker<Void, Void> saveWorker = new SwingWorker<>() {
                    private String saveError = null;

                    @Override
                    protected Void doInBackground() {
                        try {
                            if (isEdit) {
                                PassengerService.updatePassenger(existing.id(), n, ph, emailVal, finalAge, genderVal, addressVal);
                            } else {
                                PassengerService.createPassenger(n, ph, emailVal, finalAge, genderVal, addressVal);
                            }
                        } catch (SQLIntegrityConstraintViolationException ex) {
                            saveError = "A passenger with this email address already exists.";
                        } catch (SQLException ex) {
                            if (ex.getSQLState() != null && ex.getSQLState().startsWith("23")) {
                                saveError = "A passenger with this email address already exists.";
                            } else {
                                saveError = isEdit ? "Unable to update passenger. Please check details." : "Unable to add passenger. Please check details.";
                            }
                        } catch (IllegalArgumentException ex) {
                            saveError = ex.getMessage();
                        }
                        return null;
                    }

                    @Override
                    protected void done() {
                        submitBtn.setEnabled(true);
                        submitBtn.setText(isEdit ? "Save Changes" : "Add Passenger");
                        cancelBtn.setEnabled(true);
                        nameField.setEnabled(true);
                        phoneField.setEnabled(true);
                        emailField.setEnabled(true);
                        ageField.setEnabled(true);
                        genderCombo.setEnabled(true);
                        addressField.setEnabled(true);
                        if (saveError != null) {
                            dialogBanner.showMessage(saveError, NotificationBanner.Type.ERROR);
                        } else {
                            dialog.dispose();
                            notificationBanner.showMessage(isEdit ? "Passenger updated successfully." : "Passenger added successfully.", NotificationBanner.Type.SUCCESS);
                            loadPassengersAsync(false);
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

    private void deletePassenger(PassengerRecord p) {
        // Critical safeguard: Block deletion if reservations exist
        if (p.reservationCount() > 0) {
            notificationBanner.showMessage("This passenger cannot be deleted because reservation history exists.", NotificationBanner.Type.ERROR);
            return;
        }

        // Custom Delete Confirmation Modal Dialog
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "Delete Passenger", JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setSize(440, 200);
        dialog.setLocationRelativeTo(this);
        dialog.setResizable(false);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Color.WHITE);
        content.setBorder(new EmptyBorder(20, 24, 20, 24));

        JLabel title = new JLabel("Delete Passenger?");
        title.setFont(AeroFonts.TITLE_SECTION);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel desc = new JLabel("<html>Are you sure you want to permanently delete passenger <b>" + p.name() + "</b>?<br>This action cannot be undone.</html>");
        desc.setFont(AeroFonts.BODY);
        desc.setForeground(AeroColors.TEXT_SECONDARY);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnRow.setOpaque(false);

        ModernButton cancelBtn = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        cancelBtn.setPreferredSize(new Dimension(84, 34));
        cancelBtn.addActionListener(e -> dialog.dispose());

        ModernButton confirmDeleteBtn = new ModernButton("Delete Passenger", ModernButton.Style.DANGER);
        confirmDeleteBtn.setPreferredSize(new Dimension(140, 34));
        confirmDeleteBtn.addActionListener(e -> {
            dialog.dispose();
            performDeleteAsync(p);
        });

        btnRow.add(cancelBtn);
        btnRow.add(confirmDeleteBtn);

        content.add(title);
        content.add(Box.createVerticalStrut(10));
        content.add(desc);
        content.add(Box.createVerticalGlue());
        content.add(btnRow);

        dialog.setContentPane(content);
        dialog.setVisible(true);
    }

    private void performDeleteAsync(PassengerRecord p) {
        viewBtn.setEnabled(false);
        editBtn.setEnabled(false);
        deleteBtn.setEnabled(false);
        deleteBtn.setText("Deleting...");

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            private String errorMessage = null;

            @Override
            protected Void doInBackground() {
                try {
                    PassengerService.deletePassenger(p.id());
                } catch (IllegalStateException | IllegalArgumentException ex) {
                    errorMessage = ex.getMessage();
                } catch (SQLException ex) {
                    errorMessage = "Unable to delete passenger. Please check database connection.";
                }
                return null;
            }

            @Override
            protected void done() {
                deleteBtn.setText("Delete Passenger");
                if (errorMessage != null) {
                    notificationBanner.showMessage(errorMessage, NotificationBanner.Type.ERROR);
                    boolean hasSel = table.getSelectedRow() >= 0;
                    viewBtn.setEnabled(hasSel);
                    editBtn.setEnabled(hasSel);
                    deleteBtn.setEnabled(hasSel);
                } else {
                    notificationBanner.showMessage("Passenger deleted successfully.", NotificationBanner.Type.SUCCESS);
                    loadPassengersAsync(false);
                }
            }
        };

        worker.execute();
    }


    private class PassengerTableModel extends AbstractTableModel {
        private final String[] columns = {"Passenger / Name", "PNR", "Phone", "Email", "Age", "Reservations"};

        @Override public int getRowCount() { return passengerList.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            PassengerRecord r = passengerList.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> r.name();
                case 1 -> (r.displayPnr() != null && !r.displayPnr().isBlank()) ? r.displayPnr() : "—";
                case 2 -> r.phone();
                case 3 -> (r.email() != null && !r.email().isBlank()) ? r.email() : "—";
                case 4 -> r.age() != null ? String.valueOf(r.age()) : "—";
                case 5 -> r.reservationCount() == 0 ? "0" : (r.reservationCount() == 1 ? "1 booking" : r.reservationCount() + " bookings");
                default -> "—";
            };
        }

        @Override public boolean isCellEditable(int rowIndex, int columnIndex) { return false; }
    }
}
