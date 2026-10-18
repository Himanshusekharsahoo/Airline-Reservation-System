package airline.management.system.ui.panels;

import airline.management.system.DBConnection;
import airline.management.system.ReservationService;
import airline.management.system.ui.components.AeroScrollPane;
import airline.management.system.ui.components.ModernButton;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;

/**
 * AeroDesk Enterprise Flight Reservation & Booking Management Screen
 * Multi-step wizard workflow (Passenger -> Flight -> Seat -> Payment -> Confirmation -> Success)
 * delegating atomic transaction processing strictly to ReservationService.
 */
public class BookingPanel extends JPanel {
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NavigationController navigationController;
    private final NotificationBanner notificationBanner;

    // Wizard Card Layout & Container
    private final CardLayout wizardCardLayout = new CardLayout();
    private final JPanel wizardContainer = new JPanel(wizardCardLayout);

    // Wizard Step Indicator Panels
    private final JPanel stepIndicatorPanel;
    private final StepPill[] stepPills = new StepPill[5];
    private int currentStepIndex = 0; // 0=Passenger, 1=Flight, 2=Seat, 3=Payment, 4=Confirm

    // Active Selection State
    private BookablePassenger selectedPassenger = null;
    private BookableFlight selectedFlight = null;
    private String selectedSeat = null;
    private String selectedPaymentMethod = "UPI"; // Default: UPI

    // Step 1: Passenger Selection Components
    private final ModernTextField passengerSearchField;
    private final ModernButton passengerRefreshBtn;
    private final StyledTable passengerTable;
    private final PassengerTableModel passengerTableModel;
    private final List<BookablePassenger> passengerList = new ArrayList<>();
    private final CardLayout passengerTableCardLayout = new CardLayout();
    private final JPanel passengerTableContainer = new JPanel(passengerTableCardLayout);
    private final JLabel passengerCountLabel;
    private final JPanel selectedPassengerSummaryCard;
    private final JLabel passCardName = new JLabel("—");
    private final JLabel passCardPnr = new JLabel("—");
    private final JLabel passCardPhone = new JLabel("—");
    private final JLabel passCardEmail = new JLabel("—");
    private final ModernButton step1ContinueBtn;

    // Step 2: Flight Selection Components
    private final ModernTextField flightSearchField;
    private final ModernButton flightRefreshBtn;
    private final StyledTable flightTable;
    private final FlightTableModel flightTableModel;
    private final List<BookableFlight> flightList = new ArrayList<>();
    private final CardLayout flightTableCardLayout = new CardLayout();
    private final JPanel flightTableContainer = new JPanel(flightTableCardLayout);
    private final JLabel flightCountLabel;
    private final JPanel selectedFlightSummaryCard;
    private final JLabel flightCardNum = new JLabel("—");
    private final JLabel flightCardRoute = new JLabel("—");
    private final JLabel flightCardTime = new JLabel("—");
    private final JLabel flightCardSeats = new JLabel("—");
    private final JLabel flightCardFare = new JLabel("—");
    private final ModernButton step2ContinueBtn;

    // Step 3: Seat Selection Components
    private final JPanel seatMapHostPanel = new JPanel(new BorderLayout());
    private final JLabel seatSelectionStatusLabel = new JLabel("Please click an available seat on the cabin map.");
    private final Set<String> occupiedSeatSet = new HashSet<>();
    private final ModernButton step3ContinueBtn;

    // Step 4: Payment Selection Components
    private final JPanel paymentSummaryContainer = new JPanel();
    private final PaymentOptionCard upiCard;
    private final PaymentOptionCard cardCard;
    private final PaymentOptionCard cashCard;
    private final JLabel paymentFareDisplay = new JLabel("₹0.00");
    private final ModernButton step4ContinueBtn;

    // Step 5: Final Review & Confirmation Components
    private final JPanel reviewSummaryContainer = new JPanel();
    private final JLabel reviewPassName = new JLabel("—");
    private final JLabel reviewPassPhone = new JLabel("—");
    private final JLabel reviewPassPnr = new JLabel("—");
    private final JLabel reviewFlightNum = new JLabel("—");
    private final JLabel reviewFlightRoute = new JLabel("—");
    private final JLabel reviewFlightSchedule = new JLabel("—");
    private final JLabel reviewSeat = new JLabel("—");
    private final JLabel reviewFare = new JLabel("—");
    private final JLabel reviewPaymentMethod = new JLabel("—");
    private final ModernButton confirmAndBookBtn;
    private ModernButton reviewBackBtn;

    // Search generation counters to discard stale out-of-order results
    private final java.util.concurrent.atomic.AtomicLong bookingPassengerSearchGen = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong bookingFlightSearchGen = new java.util.concurrent.atomic.AtomicLong();

    // Step 6: Booking Success Components
    private final JLabel successPnrLabel = new JLabel("—");
    private final JLabel successPassLabel = new JLabel("—");
    private final JLabel successFlightLabel = new JLabel("—");
    private final JLabel successRouteLabel = new JLabel("—");
    private final JLabel successSeatLabel = new JLabel("—");
    private final JLabel successFareLabel = new JLabel("—");
    private final JLabel successPaymentLabel = new JLabel("—");
    private final JLabel successRefLabel = new JLabel("—");
    private String lastBookedPnr = null;

    public BookingPanel(NavigationController controller) {
        this.navigationController = controller;
        setLayout(new BorderLayout());
        setBackground(AeroColors.BACKGROUND);

        JPanel mainContent = new JPanel();
        mainContent.setLayout(new BoxLayout(mainContent, BoxLayout.Y_AXIS));
        mainContent.setBackground(AeroColors.BACKGROUND);
        mainContent.setBorder(new EmptyBorder(24, 28, 28, 28));

        // 1. Header Area
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);

        JPanel titleBlock = new JPanel();
        titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
        titleBlock.setOpaque(false);

        JLabel title = new JLabel("Reservation Desk");
        title.setFont(AeroFonts.TITLE_PAGE);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel subtitle = new JLabel("Airline operations reservation desk: Passenger booking, seat allocation, and ticketing console.");
        subtitle.setFont(AeroFonts.BODY);
        subtitle.setForeground(AeroColors.TEXT_SECONDARY);

        titleBlock.add(title);
        titleBlock.add(Box.createVerticalStrut(2));
        titleBlock.add(subtitle);

        JLabel deskBadge = new JLabel("● Reservation Agent Desk");
        deskBadge.setFont(AeroFonts.CAPTION_BOLD);
        deskBadge.setForeground(AeroColors.PRIMARY);

        headerPanel.add(titleBlock, BorderLayout.WEST);
        headerPanel.add(deskBadge, BorderLayout.EAST);
        mainContent.add(headerPanel);
        mainContent.add(Box.createVerticalStrut(14));

        // 2. Notification Banner
        notificationBanner = new NotificationBanner();
        mainContent.add(notificationBanner);
        mainContent.add(Box.createVerticalStrut(14));

        // 3. Wizard Step Indicator Bar
        stepIndicatorPanel = createStepIndicatorBar();
        mainContent.add(stepIndicatorPanel);
        mainContent.add(Box.createVerticalStrut(16));

        // 4. Wizard Steps Container
        wizardContainer.setOpaque(false);

        // Step 1 Setup
        passengerSearchField = new ModernTextField(22);
        passengerSearchField.setPlaceholder("Search passengers by name, PNR, or phone...");
        passengerRefreshBtn = new ModernButton("↻ Refresh", ModernButton.Style.SECONDARY);
        passengerCountLabel = new JLabel("Loading passengers...");
        passengerTableModel = new PassengerTableModel();
        passengerTable = new StyledTable(passengerTableModel);
        selectedPassengerSummaryCard = createSelectedPassengerSummaryCard();
        step1ContinueBtn = new ModernButton("Continue to Flight Selection →", ModernButton.Style.PRIMARY);
        step1ContinueBtn.setPreferredSize(new Dimension(240, 36));
        step1ContinueBtn.setEnabled(false);
        step1ContinueBtn.addActionListener(e -> goToStep(1));
        JPanel step1Panel = createStep1PassengerPanel();
        wizardContainer.add(step1Panel, "step1");

        // Step 2 Setup
        flightSearchField = new ModernTextField(22);
        flightSearchField.setPlaceholder("Search flights by flight number, airline, origin, or destination...");
        flightRefreshBtn = new ModernButton("↻ Refresh", ModernButton.Style.SECONDARY);
        flightCountLabel = new JLabel("Loading flights...");
        flightTableModel = new FlightTableModel();
        flightTable = new StyledTable(flightTableModel);
        selectedFlightSummaryCard = createSelectedFlightSummaryCard();
        step2ContinueBtn = new ModernButton("Continue to Seat Selection →", ModernButton.Style.PRIMARY);
        step2ContinueBtn.setPreferredSize(new Dimension(230, 36));
        step2ContinueBtn.setEnabled(false);
        step2ContinueBtn.addActionListener(e -> goToStep(2));
        JPanel step2Panel = createStep2FlightPanel();
        wizardContainer.add(step2Panel, "step2");

        // Step 3 Setup
        step3ContinueBtn = new ModernButton("Continue to Payment →", ModernButton.Style.PRIMARY);
        step3ContinueBtn.setPreferredSize(new Dimension(200, 36));
        step3ContinueBtn.setEnabled(false);
        step3ContinueBtn.addActionListener(e -> goToStep(3));
        JPanel step3Panel = createStep3SeatPanel();
        wizardContainer.add(step3Panel, "step3");

        // Step 4 Setup
        upiCard = new PaymentOptionCard("UPI Payment", "Simulated UPI instant transaction", "UPI");
        cardCard = new PaymentOptionCard("Debit / Credit Card", "Simulated card gateway authorization", "Card");
        cashCard = new PaymentOptionCard("Cash at Counter", "In-person desk cash settlement", "Cash");
        step4ContinueBtn = new ModernButton("Continue to Review & Confirm →", ModernButton.Style.PRIMARY);
        step4ContinueBtn.setPreferredSize(new Dimension(250, 36));
        step4ContinueBtn.addActionListener(e -> goToStep(4));
        JPanel step4Panel = createStep4PaymentPanel();
        wizardContainer.add(step4Panel, "step4");

        // Step 5 Setup
        confirmAndBookBtn = new ModernButton("Confirm & Book Ticket", ModernButton.Style.PRIMARY);
        confirmAndBookBtn.setPreferredSize(new Dimension(220, 40));
        confirmAndBookBtn.addActionListener(e -> executeBookingWorkflow());
        JPanel step5Panel = createStep5ConfirmPanel();
        wizardContainer.add(step5Panel, "step5");

        // Step 6 (Success) Setup
        JPanel stepSuccessPanel = createStepSuccessPanel();
        wizardContainer.add(stepSuccessPanel, "step_success");

        mainContent.add(wizardContainer);

        add(new AeroScrollPane(mainContent), BorderLayout.CENTER);

        // Auto-refresh when navigated to BOOKING
        if (controller != null) {
            controller.addListener((route, param) -> {
                if (route == NavRoute.BOOKING) {
                    refreshDataAsync();
                }
            });
        }

        refreshDataAsync();
        goToStep(0);
    }

    private JPanel createStepIndicatorBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.setOpaque(false);

        String[] stepNames = {"1 Passenger", "2 Flight", "3 Seat", "4 Payment", "5 Confirmation"};
        for (int i = 0; i < 5; i++) {
            final int stepIndex = i;
            stepPills[i] = new StepPill(stepNames[i], i);
            stepPills[i].addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (canNavigateToStep(stepIndex)) {
                        goToStep(stepIndex);
                    }
                }
            });
            bar.add(stepPills[i]);
            if (i < 4) {
                JLabel arrow = new JLabel("→");
                arrow.setFont(AeroFonts.CAPTION_BOLD);
                arrow.setForeground(AeroColors.TEXT_MUTED);
                bar.add(arrow);
            }
        }
        return bar;
    }

    private boolean canNavigateToStep(int targetStep) {
        if (targetStep == 0) return true;
        if (targetStep == 1) return selectedPassenger != null;
        if (targetStep == 2) return selectedPassenger != null && selectedFlight != null;
        if (targetStep == 3) return selectedPassenger != null && selectedFlight != null && selectedSeat != null;
        if (targetStep == 4) return selectedPassenger != null && selectedFlight != null && selectedSeat != null;
        return false;
    }

    private void goToStep(int stepIndex) {
        this.currentStepIndex = stepIndex;
        wizardCardLayout.show(wizardContainer, "step" + (stepIndex + 1));

        for (int i = 0; i < 5; i++) {
            if (i < stepIndex) {
                stepPills[i].setState(StepPill.State.COMPLETED);
            } else if (i == stepIndex) {
                stepPills[i].setState(StepPill.State.ACTIVE);
            } else {
                stepPills[i].setState(StepPill.State.INACTIVE);
            }
        }

        if (stepIndex == 2 && selectedFlight != null) {
            loadSeatMapAsync(selectedFlight);
        } else if (stepIndex == 3) {
            updatePaymentSummary();
        } else if (stepIndex == 4) {
            updateReviewSummary();
        }

        stepIndicatorPanel.revalidate();
        stepIndicatorPanel.repaint();
    }

    // ==========================================
    // STEP 1: PASSENGER SELECTION
    // ==========================================
    private JPanel createStep1PassengerPanel() {
        JPanel card = new JPanel(new BorderLayout(0, 14));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(18, 20, 18, 20)
        ));

        // Top Toolbar
        JPanel topBar = new JPanel(new BorderLayout(12, 0));
        topBar.setOpaque(false);

        passengerSearchField.addActionListener(e -> loadPassengersAsync());

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightControls.setOpaque(false);

        ModernButton searchBtn = new ModernButton("Search", ModernButton.Style.PRIMARY);
        searchBtn.setPreferredSize(new Dimension(84, AeroConstants.BUTTON_HEIGHT));
        searchBtn.addActionListener(e -> loadPassengersAsync());

        ModernButton resetBtn = new ModernButton("Reset", ModernButton.Style.SECONDARY);
        resetBtn.setPreferredSize(new Dimension(80, AeroConstants.BUTTON_HEIGHT));
        resetBtn.addActionListener(e -> {
            passengerSearchField.setText("");
            loadPassengersAsync();
        });

        passengerRefreshBtn.setPreferredSize(new Dimension(96, AeroConstants.BUTTON_HEIGHT));
        passengerRefreshBtn.addActionListener(e -> loadPassengersAsync());

        ModernButton addPassengerBtn = new ModernButton("+ Add Passenger", ModernButton.Style.SECONDARY);
        addPassengerBtn.setPreferredSize(new Dimension(140, AeroConstants.BUTTON_HEIGHT));
        addPassengerBtn.addActionListener(e -> {
            if (navigationController != null) {
                navigationController.navigateTo(NavRoute.PASSENGERS);
            }
        });

        rightControls.add(searchBtn);
        rightControls.add(resetBtn);
        rightControls.add(passengerRefreshBtn);
        rightControls.add(addPassengerBtn);

        topBar.add(passengerSearchField, BorderLayout.CENTER);
        topBar.add(rightControls, BorderLayout.EAST);
        card.add(topBar, BorderLayout.NORTH);

        // Center Table Area
        JPanel centerPanel = new JPanel(new BorderLayout(0, 8));
        centerPanel.setOpaque(false);

        JPanel countBar = new JPanel(new BorderLayout());
        countBar.setOpaque(false);
        JLabel heading = new JLabel("Select Passenger (Click a row to select)");
        heading.setFont(AeroFonts.CAPTION_BOLD);
        heading.setForeground(AeroColors.TEXT_MAIN);
        passengerCountLabel.setFont(AeroFonts.CAPTION);
        passengerCountLabel.setForeground(AeroColors.TEXT_MUTED);
        countBar.add(heading, BorderLayout.WEST);
        countBar.add(passengerCountLabel, BorderLayout.EAST);
        centerPanel.add(countBar, BorderLayout.NORTH);

        passengerTable.getColumnModel().getColumn(0).setPreferredWidth(220); // Passenger
        passengerTable.getColumnModel().getColumn(1).setPreferredWidth(100); // PNR
        passengerTable.getColumnModel().getColumn(2).setPreferredWidth(140); // Phone
        passengerTable.getColumnModel().getColumn(3).setPreferredWidth(220); // Email
        passengerTable.getColumnModel().getColumn(4).setPreferredWidth(80);  // Age

        passengerTable.getSelectionModel().addListSelectionListener(e -> {
            int row = passengerTable.getSelectedRow();
            if (row >= 0 && row < passengerList.size()) {
                int modelIdx = passengerTable.convertRowIndexToModel(row);
                selectPassenger(passengerList.get(modelIdx));
            }
        });

        passengerTableContainer.setOpaque(false);
        passengerTableContainer.add(new JScrollPane(passengerTable), "table");
        passengerTableContainer.add(createEmptyStatePanel("No passengers found.", "Try changing your search keywords or register a passenger in Passenger Management."), "empty");
        passengerTableCardLayout.show(passengerTableContainer, "table");

        centerPanel.add(passengerTableContainer, BorderLayout.CENTER);
        card.add(centerPanel, BorderLayout.CENTER);

        // Bottom Selected Summary & Continue Action
        JPanel bottomArea = new JPanel(new BorderLayout(14, 0));
        bottomArea.setOpaque(false);
        bottomArea.setBorder(new EmptyBorder(12, 0, 0, 0));

        bottomArea.add(selectedPassengerSummaryCard, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        btnPanel.setOpaque(false);
        btnPanel.add(step1ContinueBtn);
        bottomArea.add(btnPanel, BorderLayout.EAST);

        card.add(bottomArea, BorderLayout.SOUTH);
        return card;
    }

    private JPanel createSelectedPassengerSummaryCard() {
        JPanel card = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 8));
        card.setBackground(new Color(0xF8, 0xFA, 0xFC));
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(4, 8, 4, 8)
        ));

        JLabel tag = new JLabel("SELECTED PASSENGER:");
        tag.setFont(AeroFonts.CAPTION_BOLD);
        tag.setForeground(AeroColors.TEXT_SECONDARY);
        card.add(tag);

        card.add(createSummaryField("Name", passCardName));
        card.add(createSummaryField("PNR", passCardPnr));
        card.add(createSummaryField("Phone", passCardPhone));
        card.add(createSummaryField("Email", passCardEmail));

        return card;
    }

    private void selectPassenger(BookablePassenger p) {
        this.selectedPassenger = p;
        passCardName.setText(p.name());
        passCardPnr.setText(p.displayPnr() != null && !p.displayPnr().isBlank() ? p.displayPnr() : "—");
        passCardPhone.setText(p.phone());
        passCardEmail.setText(p.email() != null && !p.email().isBlank() ? p.email() : "—");

        step1ContinueBtn.setEnabled(true);
    }

    // ==========================================
    // STEP 2: FLIGHT SELECTION
    // ==========================================
    private JPanel createStep2FlightPanel() {
        JPanel card = new JPanel(new BorderLayout(0, 14));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(18, 20, 18, 20)
        ));

        // Top Toolbar
        JPanel topBar = new JPanel(new BorderLayout(12, 0));
        topBar.setOpaque(false);

        flightSearchField.addActionListener(e -> loadFlightsAsync());

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightControls.setOpaque(false);

        ModernButton searchBtn = new ModernButton("Search", ModernButton.Style.PRIMARY);
        searchBtn.setPreferredSize(new Dimension(84, AeroConstants.BUTTON_HEIGHT));
        searchBtn.addActionListener(e -> loadFlightsAsync());

        ModernButton resetBtn = new ModernButton("Reset", ModernButton.Style.SECONDARY);
        resetBtn.setPreferredSize(new Dimension(80, AeroConstants.BUTTON_HEIGHT));
        resetBtn.addActionListener(e -> {
            flightSearchField.setText("");
            loadFlightsAsync();
        });

        flightRefreshBtn.setPreferredSize(new Dimension(96, AeroConstants.BUTTON_HEIGHT));
        flightRefreshBtn.addActionListener(e -> loadFlightsAsync());

        rightControls.add(searchBtn);
        rightControls.add(resetBtn);
        rightControls.add(flightRefreshBtn);

        topBar.add(flightSearchField, BorderLayout.CENTER);
        topBar.add(rightControls, BorderLayout.EAST);
        card.add(topBar, BorderLayout.NORTH);

        // Center Table Area
        JPanel centerPanel = new JPanel(new BorderLayout(0, 8));
        centerPanel.setOpaque(false);

        JPanel countBar = new JPanel(new BorderLayout());
        countBar.setOpaque(false);
        JLabel heading = new JLabel("Select Flight (Click a row to select)");
        heading.setFont(AeroFonts.CAPTION_BOLD);
        heading.setForeground(AeroColors.TEXT_MAIN);
        flightCountLabel.setFont(AeroFonts.CAPTION);
        flightCountLabel.setForeground(AeroColors.TEXT_MUTED);
        countBar.add(heading, BorderLayout.WEST);
        countBar.add(flightCountLabel, BorderLayout.EAST);
        centerPanel.add(countBar, BorderLayout.NORTH);

        flightTable.getColumnModel().getColumn(0).setPreferredWidth(90);  // Flight
        flightTable.getColumnModel().getColumn(1).setPreferredWidth(130); // Airline
        flightTable.getColumnModel().getColumn(2).setPreferredWidth(190); // Route
        flightTable.getColumnModel().getColumn(3).setPreferredWidth(130); // Departure
        flightTable.getColumnModel().getColumn(4).setPreferredWidth(130); // Arrival
        flightTable.getColumnModel().getColumn(5).setPreferredWidth(120); // Available / Total
        flightTable.getColumnModel().getColumn(6).setPreferredWidth(90);  // Fare
        flightTable.getColumnModel().getColumn(7).setPreferredWidth(90);  // Status

        flightTable.getSelectionModel().addListSelectionListener(e -> {
            int row = flightTable.getSelectedRow();
            if (row >= 0 && row < flightList.size()) {
                int modelIdx = flightTable.convertRowIndexToModel(row);
                selectFlight(flightList.get(modelIdx));
            }
        });

        flightTableContainer.setOpaque(false);
        flightTableContainer.add(new JScrollPane(flightTable), "table");
        flightTableContainer.add(createEmptyStatePanel("No bookable flights found.", "Ensure scheduled flights have available seats and status SCHEDULED/ACTIVE."), "empty");
        flightTableCardLayout.show(flightTableContainer, "table");

        centerPanel.add(flightTableContainer, BorderLayout.CENTER);
        card.add(centerPanel, BorderLayout.CENTER);

        // Bottom Selected Summary & Actions
        JPanel bottomArea = new JPanel(new BorderLayout(14, 0));
        bottomArea.setOpaque(false);
        bottomArea.setBorder(new EmptyBorder(12, 0, 0, 0));

        bottomArea.add(selectedFlightSummaryCard, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnPanel.setOpaque(false);

        ModernButton backBtn = new ModernButton("← Back to Passenger", ModernButton.Style.SECONDARY);
        backBtn.setPreferredSize(new Dimension(170, 36));
        backBtn.addActionListener(e -> goToStep(0));

        btnPanel.add(backBtn);
        btnPanel.add(step2ContinueBtn);
        bottomArea.add(btnPanel, BorderLayout.EAST);

        card.add(bottomArea, BorderLayout.SOUTH);
        return card;
    }

    private JPanel createSelectedFlightSummaryCard() {
        JPanel card = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 8));
        card.setBackground(new Color(0xF8, 0xFA, 0xFC));
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(4, 8, 4, 8)
        ));

        JLabel tag = new JLabel("SELECTED FLIGHT:");
        tag.setFont(AeroFonts.CAPTION_BOLD);
        tag.setForeground(AeroColors.TEXT_SECONDARY);
        card.add(tag);

        card.add(createSummaryField("Flight", flightCardNum));
        card.add(createSummaryField("Route", flightCardRoute));
        card.add(createSummaryField("Departure", flightCardTime));
        card.add(createSummaryField("Capacity", flightCardSeats));
        card.add(createSummaryField("Fare", flightCardFare));

        return card;
    }

    private void selectFlight(BookableFlight f) {
        this.selectedFlight = f;
        this.selectedSeat = null; // reset seat when flight changes
        step3ContinueBtn.setEnabled(false);

        flightCardNum.setText(f.flightNumber() + " (" + f.airline() + ")");
        flightCardRoute.setText(f.source() + " → " + f.destination());
        flightCardTime.setText(f.departureTime().format(DISPLAY_FORMAT));
        flightCardSeats.setText(f.availableSeats() + " / " + f.totalSeats() + " seats");
        flightCardFare.setText("₹" + String.format("%,.2f", f.fare()));

        step2ContinueBtn.setEnabled(true);
    }

    // ==========================================
    // STEP 3: VISUAL SEAT SELECTION
    // ==========================================
    private JPanel createStep3SeatPanel() {
        JPanel card = new JPanel(new BorderLayout(0, 14));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(18, 20, 18, 20)
        ));

        // Top Header and Legend
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        JPanel titleBox = new JPanel();
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        titleBox.setOpaque(false);

        JLabel hTitle = new JLabel("AIRCRAFT CABIN SEAT MAP");
        hTitle.setFont(AeroFonts.TITLE_SECTION);
        hTitle.setForeground(AeroColors.TEXT_MAIN);

        JLabel hSub = new JLabel("Click any available seat to allocate. Occupied seats cannot be selected.");
        hSub.setFont(AeroFonts.CAPTION);
        hSub.setForeground(AeroColors.TEXT_SECONDARY);

        titleBox.add(hTitle);
        titleBox.add(Box.createVerticalStrut(2));
        titleBox.add(hSub);
        header.add(titleBox, BorderLayout.WEST);

        // Legend
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        legend.setOpaque(false);
        legend.add(createLegendItem("Available", Color.WHITE, new Color(0x93, 0xC5, 0xFD), AeroColors.TEXT_MAIN));
        legend.add(createLegendItem("Selected", AeroColors.PRIMARY, AeroColors.PRIMARY, Color.WHITE));
        legend.add(createLegendItem("Occupied", new Color(0xE5, 0xE7, 0xEB), new Color(0xD1, 0xD5, 0xDB), AeroColors.TEXT_MUTED));
        header.add(legend, BorderLayout.EAST);

        card.add(header, BorderLayout.NORTH);

        // Center Seat Map Host
        seatMapHostPanel.setOpaque(false);
        card.add(seatMapHostPanel, BorderLayout.CENTER);

        // Bottom Selected Seat Bar & Actions
        JPanel bottomArea = new JPanel(new BorderLayout(14, 0));
        bottomArea.setOpaque(false);
        bottomArea.setBorder(new EmptyBorder(12, 0, 0, 0));

        JPanel statusCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        statusCard.setBackground(new Color(0xF8, 0xFA, 0xFC));
        statusCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(4, 10, 4, 10)
        ));

        seatSelectionStatusLabel.setFont(AeroFonts.BODY_BOLD);
        seatSelectionStatusLabel.setForeground(AeroColors.TEXT_MAIN);
        statusCard.add(seatSelectionStatusLabel);
        bottomArea.add(statusCard, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnPanel.setOpaque(false);

        ModernButton backBtn = new ModernButton("← Back to Flights", ModernButton.Style.SECONDARY);
        backBtn.setPreferredSize(new Dimension(150, 36));
        backBtn.addActionListener(e -> goToStep(1));

        btnPanel.add(backBtn);
        btnPanel.add(step3ContinueBtn);
        bottomArea.add(btnPanel, BorderLayout.EAST);

        card.add(bottomArea, BorderLayout.SOUTH);
        return card;
    }

    private JPanel createLegendItem(String text, Color bg, Color border, Color fg) {
        JPanel item = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        item.setOpaque(false);

        JLabel sample = new JLabel(text.substring(0, 1), SwingConstants.CENTER);
        sample.setPreferredSize(new Dimension(24, 20));
        sample.setFont(AeroFonts.BADGE);
        sample.setOpaque(true);
        sample.setBackground(bg);
        sample.setForeground(fg);
        sample.setBorder(BorderFactory.createLineBorder(border, 1));

        JLabel label = new JLabel(text);
        label.setFont(AeroFonts.CAPTION);
        label.setForeground(AeroColors.TEXT_MAIN);

        item.add(sample);
        item.add(label);
        return item;
    }

    private void loadSeatMapAsync(BookableFlight flight) {
        seatMapHostPanel.removeAll();
        JLabel loadingLbl = new JLabel("Querying seat map and reservation records...", SwingConstants.CENTER);
        loadingLbl.setFont(AeroFonts.BODY);
        loadingLbl.setForeground(AeroColors.TEXT_MUTED);
        seatMapHostPanel.add(loadingLbl, BorderLayout.CENTER);
        seatMapHostPanel.revalidate();
        seatMapHostPanel.repaint();

        SwingWorker<Set<String>, Void> worker = new SwingWorker<>() {
            @Override
            protected Set<String> doInBackground() throws Exception {
                Set<String> occupied = new HashSet<>();
                String sql = "SELECT seat_number FROM reservations WHERE flight_id=? AND status IN ('PENDING_PAYMENT','CONFIRMED') AND seat_number IS NOT NULL";
                try (Connection conn = DBConnection.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, flight.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            occupied.add(rs.getString("seat_number"));
                        }
                    }
                }
                return occupied;
            }

            @Override
            protected void done() {
                try {
                    occupiedSeatSet.clear();
                    occupiedSeatSet.addAll(get());
                    renderSeatMapGrid(flight);
                } catch (Exception ex) {
                    seatMapHostPanel.removeAll();
                    JLabel errLbl = new JLabel("Unable to load seat map. Please check database connection.", SwingConstants.CENTER);
                    errLbl.setFont(AeroFonts.BODY);
                    errLbl.setForeground(AeroColors.DANGER);
                    seatMapHostPanel.add(errLbl, BorderLayout.CENTER);
                    seatMapHostPanel.revalidate();
                    seatMapHostPanel.repaint();
                }
            }
        };
        worker.execute();
    }

    private void renderSeatMapGrid(BookableFlight flight) {
        seatMapHostPanel.removeAll();

        int totalSeats = Math.max(1, flight.totalSeats());
        int rows = (int) Math.ceil(totalSeats / 6.0);
        char[] cols = new char[]{'A', 'B', 'C', 'D', 'E', 'F'};

        JPanel grid = new JPanel(new GridLayout(rows, 7, 8, 8));
        grid.setBackground(Color.WHITE);
        grid.setBorder(new EmptyBorder(14, 16, 14, 16));

        int seatCount = 0;
        final List<SeatButton> buttonList = new ArrayList<>();

        for (int r = 1; r <= rows; r++) {
            // Seats A, B, C
            for (int ci = 0; ci < 3; ci++) {
                seatCount++;
                if (seatCount <= totalSeats) {
                    String code = r + String.valueOf(cols[ci]);
                    SeatButton sb = new SeatButton(code, occupiedSeatSet.contains(code));
                    sb.addActionListener(e -> {
                        this.selectedSeat = sb.seatCode;
                        seatSelectionStatusLabel.setText("SELECTED SEAT: " + sb.seatCode + "  (Allocated for " + (selectedPassenger != null ? selectedPassenger.name() : "passenger") + ")");
                        step3ContinueBtn.setEnabled(true);
                        for (SeatButton b : buttonList) b.updateSelection(this.selectedSeat);
                    });
                    buttonList.add(sb);
                    grid.add(sb);
                } else {
                    grid.add(Box.createGlue());
                }
            }

            // Aisle indicator
            JLabel aisle = new JLabel("Row " + r, SwingConstants.CENTER);
            aisle.setFont(AeroFonts.MUTED);
            aisle.setForeground(AeroColors.TEXT_MUTED);
            grid.add(aisle);

            // Seats D, E, F
            for (int ci = 3; ci < 6; ci++) {
                seatCount++;
                if (seatCount <= totalSeats) {
                    String code = r + String.valueOf(cols[ci]);
                    SeatButton sb = new SeatButton(code, occupiedSeatSet.contains(code));
                    sb.addActionListener(e -> {
                        this.selectedSeat = sb.seatCode;
                        seatSelectionStatusLabel.setText("SELECTED SEAT: " + sb.seatCode + "  (Allocated for " + (selectedPassenger != null ? selectedPassenger.name() : "passenger") + ")");
                        step3ContinueBtn.setEnabled(true);
                        for (SeatButton b : buttonList) b.updateSelection(this.selectedSeat);
                    });
                    buttonList.add(sb);
                    grid.add(sb);
                } else {
                    grid.add(Box.createGlue());
                }
            }
        }

        if (selectedSeat != null) {
            for (SeatButton b : buttonList) b.updateSelection(this.selectedSeat);
            seatSelectionStatusLabel.setText("SELECTED SEAT: " + selectedSeat);
            step3ContinueBtn.setEnabled(true);
        } else {
            seatSelectionStatusLabel.setText("Please click an available seat on the cabin map. (" + (totalSeats - occupiedSeatSet.size()) + " seats available)");
            step3ContinueBtn.setEnabled(false);
        }

        JScrollPane scroll = new JScrollPane(grid);
        scroll.setBorder(BorderFactory.createLineBorder(AeroColors.BORDER, 1));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(580, 320));

        seatMapHostPanel.add(scroll, BorderLayout.CENTER);
        seatMapHostPanel.revalidate();
        seatMapHostPanel.repaint();
    }

    private class SeatButton extends JButton {
        final String seatCode;
        final boolean isOccupied;

        SeatButton(String code, boolean occupied) {
            super(code);
            this.seatCode = code;
            this.isOccupied = occupied;
            setFont(AeroFonts.CAPTION_BOLD);
            setFocusPainted(false);
            setPreferredSize(new Dimension(48, 34));

            if (occupied) {
                setEnabled(false);
                setBackground(new Color(0xE5, 0xE7, 0xEB));
                setForeground(AeroColors.TEXT_MUTED);
                setBorder(BorderFactory.createLineBorder(new Color(0xD1, 0xD5, 0xDB), 1));
                setToolTipText("Seat " + code + " is already occupied");
            } else {
                setBackground(Color.WHITE);
                setForeground(AeroColors.TEXT_MAIN);
                setBorder(BorderFactory.createLineBorder(new Color(0x93, 0xC5, 0xFD), 1));
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setToolTipText("Seat " + code + " (Available - click to select)");
            }
        }

        void updateSelection(String active) {
            if (isOccupied) return;
            if (seatCode.equals(active)) {
                setBackground(AeroColors.PRIMARY);
                setForeground(Color.WHITE);
                setBorder(BorderFactory.createLineBorder(AeroColors.PRIMARY_HOVER, 2));
            } else {
                setBackground(Color.WHITE);
                setForeground(AeroColors.TEXT_MAIN);
                setBorder(BorderFactory.createLineBorder(new Color(0x93, 0xC5, 0xFD), 1));
            }
        }
    }

    // ==========================================
    // STEP 4: PAYMENT METHOD SELECTION
    // ==========================================
    private JPanel createStep4PaymentPanel() {
        JPanel card = new JPanel(new BorderLayout(0, 16));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(20, 24, 20, 24)
        ));

        // Header
        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.setOpaque(false);

        JLabel hTitle = new JLabel("PAYMENT METHOD");
        hTitle.setFont(AeroFonts.TITLE_SECTION);
        hTitle.setForeground(AeroColors.TEXT_MAIN);

        JLabel hSub = new JLabel("Select simulated payment settlement channel to issue the ticket.");
        hSub.setFont(AeroFonts.CAPTION);
        hSub.setForeground(AeroColors.TEXT_SECONDARY);

        head.add(hTitle);
        head.add(Box.createVerticalStrut(2));
        head.add(hSub);
        card.add(head, BorderLayout.NORTH);

        // Center Area: Booking Summary Card + Payment Options
        JPanel center = new JPanel(new GridLayout(1, 2, 20, 0));
        center.setOpaque(false);

        // Left summary
        paymentSummaryContainer.setLayout(new BoxLayout(paymentSummaryContainer, BoxLayout.Y_AXIS));
        paymentSummaryContainer.setOpaque(false);
        center.add(paymentSummaryContainer);

        // Right payment options
        JPanel optionsBox = new JPanel();
        optionsBox.setLayout(new BoxLayout(optionsBox, BoxLayout.Y_AXIS));
        optionsBox.setOpaque(false);

        JLabel optLbl = new JLabel("CHOOSE PAYMENT CHANNEL");
        optLbl.setFont(AeroFonts.CAPTION_BOLD);
        optLbl.setForeground(AeroColors.TEXT_SECONDARY);
        optionsBox.add(optLbl);
        optionsBox.add(Box.createVerticalStrut(10));

        upiCard.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { setPaymentMethod("UPI"); }
        });
        cardCard.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { setPaymentMethod("Card"); }
        });
        cashCard.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { setPaymentMethod("Cash"); }
        });

        optionsBox.add(upiCard);
        optionsBox.add(Box.createVerticalStrut(8));
        optionsBox.add(cardCard);
        optionsBox.add(Box.createVerticalStrut(8));
        optionsBox.add(cashCard);
        optionsBox.add(Box.createVerticalStrut(16));

        JPanel fareBox = new JPanel(new BorderLayout());
        fareBox.setBackground(new Color(0xF8, 0xFA, 0xFC));
        fareBox.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(10, 14, 10, 14)
        ));
        JLabel totalTag = new JLabel("TOTAL SETTLEMENT:");
        totalTag.setFont(AeroFonts.BODY_BOLD);
        totalTag.setForeground(AeroColors.TEXT_MAIN);
        paymentFareDisplay.setFont(AeroFonts.KPI_VALUE);
        paymentFareDisplay.setForeground(AeroColors.PRIMARY);
        fareBox.add(totalTag, BorderLayout.WEST);
        fareBox.add(paymentFareDisplay, BorderLayout.EAST);
        optionsBox.add(fareBox);

        center.add(optionsBox);
        card.add(center, BorderLayout.CENTER);

        // Bottom Actions
        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setOpaque(false);
        bottomBar.setBorder(new EmptyBorder(14, 0, 0, 0));

        ModernButton backBtn = new ModernButton("← Back to Seat Selection", ModernButton.Style.SECONDARY);
        backBtn.setPreferredSize(new Dimension(190, 36));
        backBtn.addActionListener(e -> goToStep(2));

        bottomBar.add(backBtn, BorderLayout.WEST);
        bottomBar.add(step4ContinueBtn, BorderLayout.EAST);
        card.add(bottomBar, BorderLayout.SOUTH);

        setPaymentMethod("UPI");
        return card;
    }

    private void setPaymentMethod(String method) {
        this.selectedPaymentMethod = method;
        upiCard.setSelected("UPI".equals(method));
        cardCard.setSelected("Card".equals(method));
        cashCard.setSelected("Cash".equals(method));
    }

    private void updatePaymentSummary() {
        paymentSummaryContainer.removeAll();

        JLabel sumTitle = new JLabel("BOOKING SUMMARY");
        sumTitle.setFont(AeroFonts.CAPTION_BOLD);
        sumTitle.setForeground(AeroColors.TEXT_SECONDARY);
        paymentSummaryContainer.add(sumTitle);
        paymentSummaryContainer.add(Box.createVerticalStrut(10));

        JPanel card = new JPanel(new GridLayout(6, 2, 10, 8));
        card.setBackground(new Color(0xF8, 0xFA, 0xFC));
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(12, 14, 12, 14)
        ));

        addSummaryRow(card, "Passenger Name", selectedPassenger != null ? selectedPassenger.name() : "—");
        addSummaryRow(card, "Passenger PNR", (selectedPassenger != null && selectedPassenger.displayPnr() != null) ? selectedPassenger.displayPnr() : "—");
        addSummaryRow(card, "Flight Number", selectedFlight != null ? selectedFlight.flightNumber() : "—");
        addSummaryRow(card, "Route", selectedFlight != null ? (selectedFlight.source() + " → " + selectedFlight.destination()) : "—");
        addSummaryRow(card, "Departure", selectedFlight != null ? selectedFlight.departureTime().format(DISPLAY_FORMAT) : "—");
        addSummaryRow(card, "Allocated Seat", selectedSeat != null ? selectedSeat : "—");

        paymentSummaryContainer.add(card);
        paymentFareDisplay.setText("₹" + String.format("%,.2f", selectedFlight != null ? selectedFlight.fare() : 0.0));
        paymentSummaryContainer.revalidate();
        paymentSummaryContainer.repaint();
    }

    private class PaymentOptionCard extends JPanel {
        private final JLabel titleLabel;
        private final JLabel subLabel;
        private final String code;

        PaymentOptionCard(String title, String subtitle, String code) {
            this.code = code;
            setLayout(new BorderLayout());
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(AeroColors.BORDER, 1),
                new EmptyBorder(10, 14, 10, 14)
            ));
            setBackground(Color.WHITE);

            titleLabel = new JLabel(title);
            titleLabel.setFont(AeroFonts.BODY_BOLD);
            titleLabel.setForeground(AeroColors.TEXT_MAIN);

            subLabel = new JLabel(subtitle);
            subLabel.setFont(AeroFonts.CAPTION);
            subLabel.setForeground(AeroColors.TEXT_SECONDARY);

            add(titleLabel, BorderLayout.NORTH);
            add(subLabel, BorderLayout.SOUTH);
        }

        void setSelected(boolean selected) {
            if (selected) {
                setBackground(new Color(0xEE, 0xF6, 0xFF));
                setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(AeroColors.PRIMARY, 2),
                    new EmptyBorder(9, 13, 9, 13)
                ));
            } else {
                setBackground(Color.WHITE);
                setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(AeroColors.BORDER, 1),
                    new EmptyBorder(10, 14, 10, 14)
                ));
            }
        }
    }

    // ==========================================
    // STEP 5: REVIEW & FINAL CONFIRMATION
    // ==========================================
    private JPanel createStep5ConfirmPanel() {
        JPanel card = new JPanel(new BorderLayout(0, 16));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(22, 26, 22, 26)
        ));

        // Header
        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.setOpaque(false);

        JLabel hTitle = new JLabel("CONFIRM BOOKING");
        hTitle.setFont(AeroFonts.TITLE_SECTION);
        hTitle.setForeground(AeroColors.TEXT_MAIN);

        JLabel hSub = new JLabel("Verify all passenger, flight, seat, and payment details before issuing the ticket.");
        hSub.setFont(AeroFonts.CAPTION);
        hSub.setForeground(AeroColors.TEXT_SECONDARY);

        head.add(hTitle);
        head.add(Box.createVerticalStrut(2));
        head.add(hSub);
        card.add(head, BorderLayout.NORTH);

        // Center Review Details
        reviewSummaryContainer.setLayout(new BoxLayout(reviewSummaryContainer, BoxLayout.Y_AXIS));
        reviewSummaryContainer.setOpaque(false);

        JPanel detailsGrid = new JPanel(new GridLayout(8, 2, 12, 8));
        detailsGrid.setBackground(new Color(0xF8, 0xFA, 0xFC));
        detailsGrid.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(14, 18, 14, 18)
        ));

        addSummaryRow(detailsGrid, "Passenger Name", reviewPassName);
        addSummaryRow(detailsGrid, "Passenger Phone", reviewPassPhone);
        addSummaryRow(detailsGrid, "Booking PNR", reviewPassPnr);
        addSummaryRow(detailsGrid, "Operating Flight", reviewFlightNum);
        addSummaryRow(detailsGrid, "Flight Route", reviewFlightRoute);
        addSummaryRow(detailsGrid, "Departure Schedule", reviewFlightSchedule);
        addSummaryRow(detailsGrid, "Allocated Seat", reviewSeat);
        addSummaryRow(detailsGrid, "Payment Channel", reviewPaymentMethod);

        reviewSummaryContainer.add(detailsGrid);
        reviewSummaryContainer.add(Box.createVerticalStrut(14));

        JPanel totalFareBar = new JPanel(new BorderLayout());
        totalFareBar.setBackground(new Color(0xEE, 0xF6, 0xFF));
        totalFareBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.PRIMARY, 1),
            new EmptyBorder(12, 16, 12, 16)
        ));
        JLabel totalTxt = new JLabel("TOTAL AMOUNT PAYABLE:");
        totalTxt.setFont(AeroFonts.BODY_BOLD);
        totalTxt.setForeground(AeroColors.TEXT_MAIN);
        reviewFare.setFont(AeroFonts.KPI_VALUE);
        reviewFare.setForeground(AeroColors.PRIMARY);
        totalFareBar.add(totalTxt, BorderLayout.WEST);
        totalFareBar.add(reviewFare, BorderLayout.EAST);

        reviewSummaryContainer.add(totalFareBar);
        card.add(reviewSummaryContainer, BorderLayout.CENTER);

        // Bottom Actions
        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setOpaque(false);
        bottomBar.setBorder(new EmptyBorder(16, 0, 0, 0));

        reviewBackBtn = new ModernButton("← Back to Payment", ModernButton.Style.SECONDARY);
        reviewBackBtn.setPreferredSize(new Dimension(160, 36));
        reviewBackBtn.addActionListener(e -> goToStep(3));

        bottomBar.add(reviewBackBtn, BorderLayout.WEST);
        bottomBar.add(confirmAndBookBtn, BorderLayout.EAST);
        card.add(bottomBar, BorderLayout.SOUTH);

        return card;
    }

    private void updateReviewSummary() {
        reviewPassName.setText(selectedPassenger != null ? selectedPassenger.name() : "—");
        reviewPassPhone.setText(selectedPassenger != null ? selectedPassenger.phone() : "—");
        reviewPassPnr.setText("Generated after successful booking");
        reviewFlightNum.setText(selectedFlight != null ? (selectedFlight.flightNumber() + " (" + selectedFlight.airline() + ")") : "—");
        reviewFlightRoute.setText(selectedFlight != null ? (selectedFlight.source() + " → " + selectedFlight.destination()) : "—");
        reviewFlightSchedule.setText(selectedFlight != null ? selectedFlight.departureTime().format(DISPLAY_FORMAT) : "—");
        reviewSeat.setText(selectedSeat != null ? selectedSeat : "—");
        reviewPaymentMethod.setText(selectedPaymentMethod + " (Simulated)");
        reviewFare.setText("₹" + String.format("%,.2f", selectedFlight != null ? selectedFlight.fare() : 0.0));
    }

    private void executeBookingWorkflow() {
        if (selectedFlight == null || selectedPassenger == null || selectedSeat == null || selectedPaymentMethod == null) {
            notificationBanner.showMessage("Incomplete booking details. Please review all steps.", NotificationBanner.Type.WARNING);
            return;
        }

        confirmAndBookBtn.setEnabled(false);
        confirmAndBookBtn.setText("Processing Booking...");
        if (reviewBackBtn != null) reviewBackBtn.setEnabled(false);

        final BookableFlight currentFlight = selectedFlight;
        final BookablePassenger currentPassenger = selectedPassenger;
        final String currentSeat = selectedSeat;
        final String currentMethod = selectedPaymentMethod;

        SwingWorker<BookingOutcome, Void> worker = new SwingWorker<>() {
            private String errorMessage = null;

            @Override
            protected BookingOutcome doInBackground() {
                try {
                    // Critical rule: Call the authoritative existing ReservationService
                    String pnr = ReservationService.book(currentFlight.id(), currentPassenger.id(), currentSeat, currentMethod);
                    String paymentRef = "SIM-" + pnr;
                    String paymentStatus = "SUCCESS";

                    // Fetch authoritative payment metadata off the EDT
                    try (Connection conn = DBConnection.getConnection();
                         PreparedStatement ps = conn.prepareStatement(
                             "SELECT pmt.payment_status, pmt.transaction_reference FROM payments pmt " +
                             "JOIN reservations r ON r.id=pmt.reservation_id WHERE r.pnr=?")) {
                        ps.setString(1, pnr);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                String psVal = rs.getString("payment_status");
                                if (psVal != null && !psVal.trim().isEmpty()) {
                                    paymentStatus = psVal;
                                }
                                String prVal = rs.getString("transaction_reference");
                                if (prVal != null && !prVal.trim().isEmpty()) {
                                    paymentRef = prVal;
                                }
                            }
                        }
                    } catch (SQLException ex) {
                        System.err.println("Warning: Unable to query payment metadata for confirmed booking " + pnr + ": " + ex.getMessage());
                    }

                    return new BookingOutcome(pnr, paymentRef, paymentStatus);
                } catch (SQLException | IllegalArgumentException ex) {
                    errorMessage = ex.getMessage();
                    return null;
                } catch (Exception ex) {
                    errorMessage = "An unexpected error occurred during ticket issuance.";
                    return null;
                }
            }

            @Override
            protected void done() {
                confirmAndBookBtn.setEnabled(true);
                confirmAndBookBtn.setText("Confirm & Book Ticket");
                if (reviewBackBtn != null) reviewBackBtn.setEnabled(true);
                try {
                    BookingOutcome outcome = get();
                    if (outcome != null) {
                        lastBookedPnr = outcome.pnr();
                        notificationBanner.showMessage("Booking confirmed! PNR: " + outcome.pnr(), NotificationBanner.Type.SUCCESS);
                        displayBookingSuccessScreen(outcome.pnr(), outcome.paymentRef(), outcome.paymentStatus(), currentFlight, currentPassenger, currentSeat, currentMethod);
                    } else {
                        // Error handling
                        if (errorMessage != null && errorMessage.toLowerCase().contains("seat")) {
                            notificationBanner.showMessage("That seat is no longer available. Please select another seat.", NotificationBanner.Type.ERROR);
                            goToStep(2); // Jump back to seat step to choose another
                        } else {
                            notificationBanner.showMessage(errorMessage != null ? errorMessage : "Booking failed. Please try again.", NotificationBanner.Type.ERROR);
                        }
                    }
                } catch (Exception ex) {
                    notificationBanner.showMessage("Booking failure: " + ex.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    // ==========================================
    // STEP 6: BOOKING CONFIRMED SUCCESS SCREEN
    // ==========================================
    private JPanel createStepSuccessPanel() {
        JPanel card = new JPanel(new BorderLayout(0, 16));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(26, 28, 26, 28)
        ));

        // Header
        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.setOpaque(false);

        JLabel hTitle = new JLabel("BOOKING CONFIRMED", SwingConstants.CENTER);
        hTitle.setFont(AeroFonts.TITLE_PAGE);
        hTitle.setForeground(AeroColors.SUCCESS);
        hTitle.setAlignmentX(CENTER_ALIGNMENT);

        JLabel hSub = new JLabel("Your reservation has been successfully created.", SwingConstants.CENTER);
        hSub.setFont(AeroFonts.BODY);
        hSub.setForeground(AeroColors.TEXT_SECONDARY);
        hSub.setAlignmentX(CENTER_ALIGNMENT);

        head.add(hTitle);
        head.add(Box.createVerticalStrut(4));
        head.add(hSub);
        card.add(head, BorderLayout.NORTH);

        // Center Confirmation Details
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setOpaque(false);

        JPanel pnrBanner = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 8));
        pnrBanner.setBackground(new Color(0xDC, 0xFC, 0xE7));
        pnrBanner.setBorder(BorderFactory.createLineBorder(new Color(0x86, 0xEF, 0xAC), 1));
        JLabel pnrTag = new JLabel("RESERVATION PNR:");
        pnrTag.setFont(AeroFonts.BODY_BOLD);
        pnrTag.setForeground(AeroColors.SUCCESS);
        successPnrLabel.setFont(AeroFonts.TITLE_PAGE);
        successPnrLabel.setForeground(AeroColors.SUCCESS);
        pnrBanner.add(pnrTag);
        pnrBanner.add(successPnrLabel);
        center.add(pnrBanner);
        center.add(Box.createVerticalStrut(16));

        JPanel details = new JPanel(new GridLayout(7, 2, 12, 8));
        details.setBackground(new Color(0xF8, 0xFA, 0xFC));
        details.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(14, 18, 14, 18)
        ));

        addSummaryRow(details, "Passenger", successPassLabel);
        addSummaryRow(details, "Flight", successFlightLabel);
        addSummaryRow(details, "Route", successRouteLabel);
        addSummaryRow(details, "Seat", successSeatLabel);
        addSummaryRow(details, "Fare", successFareLabel);
        addSummaryRow(details, "Payment Status", successPaymentLabel);
        addSummaryRow(details, "Transaction Reference", successRefLabel);

        center.add(details);
        card.add(center, BorderLayout.CENTER);

        // Bottom Navigation Buttons
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 0));
        btnRow.setOpaque(false);
        btnRow.setBorder(new EmptyBorder(16, 0, 0, 0));

        ModernButton viewTripBtn = new ModernButton("View in Trip History", ModernButton.Style.PRIMARY);
        viewTripBtn.setPreferredSize(new Dimension(170, 38));
        viewTripBtn.addActionListener(e -> {
            if (navigationController != null) {
                navigationController.navigateTo(NavRoute.TRIPS, lastBookedPnr);
            }
        });

        ModernButton anotherTicketBtn = new ModernButton("Book Another Ticket", ModernButton.Style.SECONDARY);
        anotherTicketBtn.setPreferredSize(new Dimension(170, 38));
        anotherTicketBtn.addActionListener(e -> resetWorkflow());

        ModernButton dashboardBtn = new ModernButton("Back to Dashboard", ModernButton.Style.SECONDARY);
        dashboardBtn.setPreferredSize(new Dimension(160, 38));
        dashboardBtn.addActionListener(e -> {
            if (navigationController != null) {
                navigationController.navigateTo(NavRoute.DASHBOARD);
            }
        });

        btnRow.add(viewTripBtn);
        btnRow.add(anotherTicketBtn);
        btnRow.add(dashboardBtn);
        card.add(btnRow, BorderLayout.SOUTH);

        return card;
    }

    private void displayBookingSuccessScreen(String pnr, String paymentRef, String paymentStatus, BookableFlight flight, BookablePassenger passenger, String seat, String method) {
        successPnrLabel.setText(pnr);
        successPassLabel.setText(passenger.name());
        successFlightLabel.setText(flight.flightNumber() + " (" + flight.airline() + ")");
        successRouteLabel.setText(flight.source() + " → " + flight.destination());
        successSeatLabel.setText(seat);
        successFareLabel.setText("₹" + String.format("%,.2f", flight.fare()));
        successPaymentLabel.setText((paymentStatus != null ? paymentStatus : "COMPLETED") + " (" + method + ")");
        successRefLabel.setText(paymentRef != null ? paymentRef : ("SIM-" + pnr));

        wizardCardLayout.show(wizardContainer, "step_success");
        refreshDataAsync();
    }

    private void resetWorkflow() {
        selectedPassenger = null;
        selectedFlight = null;
        selectedSeat = null;
        selectedPaymentMethod = "UPI";

        passCardName.setText("—");
        passCardPnr.setText("—");
        passCardPhone.setText("—");
        passCardEmail.setText("—");

        flightCardNum.setText("—");
        flightCardRoute.setText("—");
        flightCardTime.setText("—");
        flightCardSeats.setText("—");
        flightCardFare.setText("—");

        passengerTable.clearSelection();
        flightTable.clearSelection();

        step1ContinueBtn.setEnabled(false);
        step2ContinueBtn.setEnabled(false);
        step3ContinueBtn.setEnabled(false);

        goToStep(0);
        refreshDataAsync();
    }

    // ==========================================
    // ASYNC DATA LOADING
    // ==========================================
    public void refreshDataAsync() {
        loadPassengersAsync();
        loadFlightsAsync();
    }

    private void loadPassengersAsync() {
        passengerRefreshBtn.setEnabled(false);
        passengerRefreshBtn.setText("Loading...");

        final long gen = bookingPassengerSearchGen.incrementAndGet();
        String query = passengerSearchField.getText().trim();

        SwingWorker<List<BookablePassenger>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<BookablePassenger> doInBackground() throws Exception {
                List<BookablePassenger> list = new ArrayList<>();
                StringBuilder sql = new StringBuilder(
                    "SELECT p.id, p.legacy_pnr, " +
                    "COALESCE(p.legacy_pnr, (SELECT r.pnr FROM reservations r WHERE r.passenger_id = p.id ORDER BY r.booking_date DESC LIMIT 1)) AS display_pnr, " +
                    "p.name, p.age, p.gender, p.phone, p.email, p.address, " +
                    "(SELECT COUNT(*) FROM reservations r WHERE r.passenger_id = p.id) AS reservation_count " +
                    "FROM passengers p WHERE 1=1"
                );
                List<Object> params = new ArrayList<>();

                if (!query.isEmpty()) {
                    sql.append(" AND (p.name LIKE ? OR p.phone LIKE ? OR p.email LIKE ? OR p.legacy_pnr LIKE ? OR CAST(p.id AS CHAR) LIKE ?)");
                    String like = "%" + query + "%";
                    for (int i = 0; i < 5; i++) params.add(like);
                }
                sql.append(" ORDER BY p.name ASC");

                try (Connection conn = DBConnection.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            int ageVal = rs.getInt("age");
                            Integer age = rs.wasNull() ? null : ageVal;
                            list.add(new BookablePassenger(
                                rs.getLong("id"),
                                rs.getString("legacy_pnr"),
                                rs.getString("display_pnr"),
                                rs.getString("name"),
                                age,
                                rs.getString("gender"),
                                rs.getString("phone"),
                                rs.getString("email"),
                                rs.getString("address"),
                                rs.getInt("reservation_count")
                            ));
                        }
                    }
                }
                return list;
            }

            @Override
            protected void done() {
                passengerRefreshBtn.setEnabled(true);
                passengerRefreshBtn.setText("↻ Refresh");
                if (gen != bookingPassengerSearchGen.get()) {
                    return; // Stale search result discarded
                }
                try {
                    passengerList.clear();
                    passengerList.addAll(get());
                    passengerTableModel.fireTableDataChanged();

                    int count = passengerList.size();
                    passengerCountLabel.setText("Showing " + count + (count == 1 ? " passenger" : " passengers"));
                    passengerTableCardLayout.show(passengerTableContainer, count == 0 ? "empty" : "table");
                } catch (Exception ex) {
                    notificationBanner.showMessage("Unable to load passengers. Please check database connection.", NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    private void loadFlightsAsync() {
        flightRefreshBtn.setEnabled(false);
        flightRefreshBtn.setText("Loading...");

        final long gen = bookingFlightSearchGen.incrementAndGet();
        String query = flightSearchField.getText().trim();

        SwingWorker<List<BookableFlight>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<BookableFlight> doInBackground() throws Exception {
                List<BookableFlight> list = new ArrayList<>();
                StringBuilder sql = new StringBuilder(
                    "SELECT id, flight_number, airline, source, destination, departure_time, arrival_time, " +
                    "available_seats, total_seats, fare, status FROM flights " +
                    "WHERE status IN ('SCHEDULED', 'ACTIVE') AND available_seats > 0"
                );
                List<Object> params = new ArrayList<>();

                if (!query.isEmpty()) {
                    sql.append(" AND (flight_number LIKE ? OR airline LIKE ? OR source LIKE ? OR destination LIKE ?)");
                    String like = "%" + query + "%";
                    for (int i = 0; i < 4; i++) params.add(like);
                }
                sql.append(" ORDER BY departure_time ASC");

                try (Connection conn = DBConnection.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            list.add(new BookableFlight(
                                rs.getLong("id"),
                                rs.getString("flight_number"),
                                rs.getString("airline"),
                                rs.getString("source"),
                                rs.getString("destination"),
                                rs.getTimestamp("departure_time").toLocalDateTime(),
                                rs.getTimestamp("arrival_time").toLocalDateTime(),
                                rs.getInt("available_seats"),
                                rs.getInt("total_seats"),
                                rs.getDouble("fare"),
                                rs.getString("status")
                            ));
                        }
                    }
                }
                return list;
            }

            @Override
            protected void done() {
                flightRefreshBtn.setEnabled(true);
                flightRefreshBtn.setText("↻ Refresh");
                if (gen != bookingFlightSearchGen.get()) {
                    return; // Stale search result discarded
                }
                try {
                    flightList.clear();
                    flightList.addAll(get());
                    flightTableModel.fireTableDataChanged();

                    int count = flightList.size();
                    flightCountLabel.setText("Showing " + count + (count == 1 ? " flight" : " flights"));
                    flightTableCardLayout.show(flightTableContainer, count == 0 ? "empty" : "table");
                } catch (Exception ex) {
                    notificationBanner.showMessage("Unable to load bookable flights. Please check database connection.", NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    // ==========================================
    // UI HELPERS & MODELS
    // ==========================================
    private JPanel createEmptyStatePanel(String title, String caption) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Color.WHITE);
        p.setBorder(new EmptyBorder(40, 20, 40, 20));

        JLabel t = new JLabel(title);
        t.setFont(AeroFonts.BODY_BOLD);
        t.setForeground(AeroColors.TEXT_SECONDARY);
        t.setAlignmentX(CENTER_ALIGNMENT);

        JLabel c = new JLabel(caption);
        c.setFont(AeroFonts.CAPTION);
        c.setForeground(AeroColors.TEXT_MUTED);
        c.setAlignmentX(CENTER_ALIGNMENT);

        p.add(Box.createVerticalGlue());
        p.add(t);
        p.add(Box.createVerticalStrut(4));
        p.add(c);
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JPanel createSummaryField(String labelText, JLabel valLabel) {
        JPanel f = new JPanel();
        f.setLayout(new BoxLayout(f, BoxLayout.Y_AXIS));
        f.setOpaque(false);

        JLabel l = new JLabel(labelText);
        l.setFont(AeroFonts.MUTED);
        l.setForeground(AeroColors.TEXT_MUTED);

        valLabel.setFont(AeroFonts.BODY_BOLD);
        valLabel.setForeground(AeroColors.TEXT_MAIN);

        f.add(l);
        f.add(valLabel);
        return f;
    }

    private void addSummaryRow(JPanel container, String labelText, Component valComp) {
        JLabel l = new JLabel(labelText);
        l.setFont(AeroFonts.CAPTION);
        l.setForeground(AeroColors.TEXT_SECONDARY);

        container.add(l);
        container.add(valComp);
    }

    private void addSummaryRow(JPanel container, String labelText, String valText) {
        JLabel l = new JLabel(labelText);
        l.setFont(AeroFonts.CAPTION);
        l.setForeground(AeroColors.TEXT_SECONDARY);

        JLabel v = new JLabel(valText);
        v.setFont(AeroFonts.BODY_BOLD);
        v.setForeground(AeroColors.TEXT_MAIN);

        container.add(l);
        container.add(v);
    }

    private static class StepPill extends JPanel {
        enum State { INACTIVE, ACTIVE, COMPLETED }

        private final JLabel label;
        private final int index;

        StepPill(String title, int index) {
            this.index = index;
            setLayout(new FlowLayout(FlowLayout.CENTER, 10, 4));
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            label = new JLabel(title);
            label.setFont(AeroFonts.BUTTON);
            add(label);

            setState(State.INACTIVE);
        }

        void setState(State state) {
            switch (state) {
                case ACTIVE -> {
                    label.setForeground(AeroColors.PRIMARY);
                    setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 2, 0, AeroColors.PRIMARY),
                        new EmptyBorder(2, 6, 2, 6)
                    ));
                }
                case COMPLETED -> {
                    label.setForeground(AeroColors.SUCCESS);
                    setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 2, 0, AeroColors.SUCCESS),
                        new EmptyBorder(2, 6, 2, 6)
                    ));
                }
                case INACTIVE -> {
                    label.setForeground(AeroColors.TEXT_MUTED);
                    setBorder(new EmptyBorder(4, 6, 4, 6));
                }
            }
        }
    }

    // Models & Records
    public record BookableFlight(
        long id,
        String flightNumber,
        String airline,
        String source,
        String destination,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        int availableSeats,
        int totalSeats,
        double fare,
        String status
    ) {}

    public record BookablePassenger(
        long id,
        String legacyPnr,
        String displayPnr,
        String name,
        Integer age,
        String gender,
        String phone,
        String email,
        String address,
        int reservationCount
    ) {}

    public record BookingOutcome(
        String pnr,
        String paymentRef,
        String paymentStatus
    ) {}

    private class PassengerTableModel extends AbstractTableModel {
        private final String[] columns = {"Passenger", "PNR", "Phone", "Email", "Age"};

        @Override public int getRowCount() { return passengerList.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            BookablePassenger p = passengerList.get(row);
            return switch (col) {
                case 0 -> p.name();
                case 1 -> (p.displayPnr() != null && !p.displayPnr().isBlank()) ? p.displayPnr() : "—";
                case 2 -> p.phone();
                case 3 -> (p.email() != null && !p.email().isBlank()) ? p.email() : "—";
                case 4 -> p.age() != null ? String.valueOf(p.age()) : "—";
                default -> "—";
            };
        }

        @Override public boolean isCellEditable(int r, int c) { return false; }
    }

    private class FlightTableModel extends AbstractTableModel {
        private final String[] columns = {"Flight", "Airline", "Route", "Departure", "Arrival", "Seats Available", "Fare", "Status"};

        @Override public int getRowCount() { return flightList.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            BookableFlight f = flightList.get(row);
            return switch (col) {
                case 0 -> f.flightNumber();
                case 1 -> f.airline();
                case 2 -> f.source() + " → " + f.destination();
                case 3 -> f.departureTime().format(DISPLAY_FORMAT);
                case 4 -> f.arrivalTime().format(DISPLAY_FORMAT);
                case 5 -> f.availableSeats() + " / " + f.totalSeats();
                case 6 -> "₹" + String.format("%,.2f", f.fare());
                case 7 -> f.status();
                default -> "—";
            };
        }

        @Override public boolean isCellEditable(int r, int c) { return false; }
    }
}
