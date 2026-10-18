package airline.management.system.ui.panels;

import airline.management.system.operations.boarding.BoardingPassenger;
import airline.management.system.operations.boarding.BoardingService;
import airline.management.system.operations.boarding.BoardingStatus;
import airline.management.system.operations.boarding.BoardingValidationResult;
import airline.management.system.operations.boarding.FlightBoardingSummary;
import airline.management.system.operations.gate.GateRecord;
import airline.management.system.operations.gate.GateService;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.ui.components.AeroScrollPane;
import airline.management.system.ui.components.DashboardCard;
import airline.management.system.ui.components.ModernButton;
import airline.management.system.ui.components.ModernComboBox;
import airline.management.system.ui.components.ModernTextField;
import airline.management.system.ui.components.NotificationBanner;
import airline.management.system.ui.components.StyledTable;
import airline.management.system.ui.layout.OperationalStation;
import airline.management.system.ui.layout.StationContext;
import airline.management.system.ui.navigation.NavigationController;
import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;

/**
 * AeroDesk Gate & Boarding Operations Console.
 * Enables airport gate staff to select flights, inspect passenger manifests, validate boarding passes,
 * and track real-time boarding completion.
 */
public class BoardingPanel extends JPanel {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NavigationController controller;
    private final NotificationBanner notificationBanner;

    // Flight Selector
    private final ModernComboBox<FlightBoardingSummary> flightCombo = new ModernComboBox<>();
    private final ModernButton refreshFlightsBtn;

    // KPI Cards
    private final DashboardCard bookedPassengersCard;
    private final DashboardCard boardedPassengersCard;
    private final DashboardCard remainingPassengersCard;
    private final DashboardCard gateStatusCard;

    // Boarding Pass Scanner / Input
    private final ModernTextField scanField;
    private final ModernButton boardBtn;

    // Manifest Table
    private final StyledTable manifestTable = new StyledTable();
    private final ManifestTableModel manifestTableModel = new ManifestTableModel();
    private final List<BoardingPassenger> manifestList = new ArrayList<>();
    private final CardLayout manifestCardLayout = new CardLayout();
    private final JPanel manifestContainer = new JPanel(manifestCardLayout);

    // Gate Action Controls
    private final ModernButton openBoardingBtn;
    private final ModernButton finalCallBtn;
    private final ModernButton closeGateBtn;
    private final JLabel gateBadge = new JLabel("Gate: Not assigned");
    private final ModernButton assignGateBtn;
    private final JLabel stationBadge = new JLabel("Gate Agent Station");

    // Active Selection State
    private FlightBoardingSummary activeFlight = null;

    // Station Context Listener
    private final Consumer<OperationalStation> stationListener;

    public BoardingPanel(NavigationController controller) {
        this.controller = controller;
        setLayout(new BorderLayout());
        setBackground(AeroColors.BACKGROUND);

        // Initialize action buttons and controls before assembling layout panels
        refreshFlightsBtn = new ModernButton("Refresh", ModernButton.Style.SECONDARY);
        refreshFlightsBtn.setPreferredSize(new Dimension(90, AeroConstants.INPUT_HEIGHT));
        refreshFlightsBtn.addActionListener(e -> loadFlights());

        assignGateBtn = new ModernButton("Assign Gate", ModernButton.Style.SECONDARY);
        assignGateBtn.setPreferredSize(new Dimension(125, AeroConstants.INPUT_HEIGHT));
        assignGateBtn.addActionListener(e -> openAssignGateDialog());

        scanField = new ModernTextField(18);
        scanField.setPlaceholder("Scan or enter passenger PNR / BP#");
        scanField.setPreferredSize(new Dimension(280, AeroConstants.INPUT_HEIGHT));

        boardBtn = new ModernButton("Mark as Boarded", ModernButton.Style.PRIMARY);
        boardBtn.setPreferredSize(new Dimension(150, AeroConstants.INPUT_HEIGHT));
        boardBtn.addActionListener(e -> executeBoarding());
        scanField.addActionListener(e -> executeBoarding());

        openBoardingBtn = new ModernButton("Open Gate Boarding", ModernButton.Style.SECONDARY);
        finalCallBtn = new ModernButton("Issue Final Call", ModernButton.Style.SECONDARY);
        closeGateBtn = new ModernButton("Close Boarding Gate", ModernButton.Style.DANGER);

        openBoardingBtn.addActionListener(e -> setGateStatus(BoardingStatus.BOARDING_OPEN));
        finalCallBtn.addActionListener(e -> setGateStatus(BoardingStatus.FINAL_CALL));
        closeGateBtn.addActionListener(e -> setGateStatus(BoardingStatus.BOARDING_CLOSED));

        flightCombo.addActionListener(e -> {
            activeFlight = (FlightBoardingSummary) flightCombo.getSelectedItem();
            if (activeFlight != null) {
                gateBadge.setText("Gate: " + activeFlight.getFormattedGate());
                loadManifest(activeFlight.flightId());
            } else {
                gateBadge.setText("Gate: Not assigned");
            }
        });

        // Assemble UI Layout
        JPanel mainContainer = new JPanel();
        mainContainer.setLayout(new BoxLayout(mainContainer, BoxLayout.Y_AXIS));
        mainContainer.setBackground(AeroColors.BACKGROUND);
        mainContainer.setBorder(new EmptyBorder(24, 28, 28, 28));

        // 1. Header Area
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        JPanel titleBlock = new JPanel();
        titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
        titleBlock.setOpaque(false);

        JLabel title = new JLabel("Gate & Boarding Console");
        title.setFont(AeroFonts.TITLE_PAGE);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel subtitle = new JLabel("Airport departure gate operations, boarding pass validation & passenger manifest clearance.");
        subtitle.setFont(AeroFonts.BODY);
        subtitle.setForeground(AeroColors.TEXT_SECONDARY);

        titleBlock.add(title);
        titleBlock.add(Box.createVerticalStrut(2));
        titleBlock.add(subtitle);
        header.add(titleBlock, BorderLayout.WEST);

        OperationalStation initSt = StationContext.getCurrentStation();
        stationBadge.setText("Gate Agent Station: " + (initSt != null ? initSt.getCode() : "DEL"));
        stationBadge.setFont(AeroFonts.CAPTION_BOLD);
        stationBadge.setForeground(AeroColors.PRIMARY);
        header.add(stationBadge, BorderLayout.EAST);

        mainContainer.add(header);
        mainContainer.add(Box.createVerticalStrut(16));

        // Notification Banner
        notificationBanner = new NotificationBanner();
        mainContainer.add(notificationBanner);
        mainContainer.add(Box.createVerticalStrut(14));

        // 2. Flight Selection & Console Bar
        JPanel selectBar = createFlightSelectionBar();
        mainContainer.add(selectBar);
        mainContainer.add(Box.createVerticalStrut(18));

        // 3. Operational KPI Cards Row
        JPanel kpiRow = new JPanel(new GridLayout(1, 4, 16, 0));
        kpiRow.setOpaque(false);

        bookedPassengersCard = new DashboardCard("BOOKED PASSENGERS", "—", "Confirmed flight reservations", "passenger", AeroColors.INFO_BG);
        boardedPassengersCard = new DashboardCard("BOARDED", "—", "Cleared through gate", "checkin", AeroColors.SUCCESS_BG);
        remainingPassengersCard = new DashboardCard("REMAINING", "—", "Awaiting gate boarding", "alert", AeroColors.WARNING_BG);
        gateStatusCard = new DashboardCard("GATE STATUS", "—", "Current gate operations phase", "boarding", new Color(0xEE, 0xF2, 0xF6));

        kpiRow.add(bookedPassengersCard);
        kpiRow.add(boardedPassengersCard);
        kpiRow.add(remainingPassengersCard);
        kpiRow.add(gateStatusCard);

        mainContainer.add(kpiRow);
        mainContainer.add(Box.createVerticalStrut(20));

        // 4. Fast Boarding Validation Bar
        JPanel scanBar = createScanBar();
        mainContainer.add(scanBar);
        mainContainer.add(Box.createVerticalStrut(18));

        // 5. Passenger Manifest Table Section
        JPanel manifestSection = createManifestSection();
        mainContainer.add(manifestSection);

        add(new AeroScrollPane(mainContainer), BorderLayout.CENTER);

        // Station change listener
        stationListener = st -> {
            stationBadge.setText("Gate Agent Station: " + (st != null ? st.getCode() : "DEL"));
            loadFlights();
        };
        StationContext.addListener(stationListener);

        // Initial flight list load
        loadFlights();
    }

    private JPanel createFlightSelectionBar() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(14, 18, 14, 18)
        ));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        left.setOpaque(false);

        JLabel label = new JLabel("Select Departure Flight:");
        label.setFont(AeroFonts.BODY_BOLD);
        label.setForeground(AeroColors.TEXT_MAIN);
        left.add(label);

        flightCombo.setPreferredSize(new Dimension(360, AeroConstants.INPUT_HEIGHT));
        left.add(flightCombo);
        left.add(refreshFlightsBtn);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        right.setOpaque(false);
        gateBadge.setFont(AeroFonts.BODY_BOLD);
        gateBadge.setForeground(AeroColors.PRIMARY);
        right.add(gateBadge);
        right.add(assignGateBtn);

        card.add(left, BorderLayout.WEST);
        card.add(right, BorderLayout.EAST);
        return card;
    }

    private JPanel createScanBar() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(14, 18, 14, 18)
        ));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        left.setOpaque(false);

        JLabel label = new JLabel("Boarding Pass Verification (PNR):");
        label.setFont(AeroFonts.BODY_BOLD);
        label.setForeground(AeroColors.TEXT_MAIN);
        left.add(label);

        left.add(scanField);
        left.add(boardBtn);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(openBoardingBtn);
        right.add(finalCallBtn);
        right.add(closeGateBtn);

        card.add(left, BorderLayout.WEST);
        card.add(right, BorderLayout.EAST);
        return card;
    }

    private JPanel createManifestSection() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(16, 18, 16, 18)
        ));
        card.setPreferredSize(new Dimension(800, 360));

        JPanel h = new JPanel(new BorderLayout());
        h.setOpaque(false);
        h.setBorder(new EmptyBorder(0, 0, 12, 0));

        JPanel titleBlock = new JPanel();
        titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
        titleBlock.setOpaque(false);

        JLabel t = new JLabel("Flight Boarding Manifest");
        t.setFont(AeroFonts.TITLE_CARD);
        t.setForeground(AeroColors.TEXT_MAIN);

        JLabel s = new JLabel("Real-time passenger manifest from confirmed reservations");
        s.setFont(AeroFonts.CAPTION);
        s.setForeground(AeroColors.TEXT_SECONDARY);

        titleBlock.add(t);
        titleBlock.add(Box.createVerticalStrut(2));
        titleBlock.add(s);
        h.add(titleBlock, BorderLayout.WEST);

        JLabel notice = new JLabel("Physical Gate Hardware & Turnstile Scanning: Scheduled for Phase 12");
        notice.setFont(AeroFonts.MUTED);
        notice.setForeground(AeroColors.TEXT_MUTED);
        h.add(notice, BorderLayout.EAST);

        card.add(h, BorderLayout.NORTH);

        manifestTable.setModel(manifestTableModel);
        manifestContainer.setOpaque(false);
        manifestContainer.add(new JScrollPane(manifestTable), "table");
        manifestContainer.add(createEmptyManifestNotice(), "empty");
        manifestCardLayout.show(manifestContainer, "table");

        card.add(manifestContainer, BorderLayout.CENTER);
        return card;
    }

    private JPanel createEmptyManifestNotice() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Color.WHITE);
        p.setBorder(new EmptyBorder(40, 20, 40, 20));

        JLabel title = new JLabel("No Confirmed Passengers On Flight", SwingConstants.CENTER);
        title.setFont(AeroFonts.BODY_BOLD);
        title.setForeground(AeroColors.TEXT_SECONDARY);
        title.setAlignmentX(CENTER_ALIGNMENT);

        JLabel desc = new JLabel("No reservations found for the selected flight route.", SwingConstants.CENTER);
        desc.setFont(AeroFonts.CAPTION);
        desc.setForeground(AeroColors.TEXT_MUTED);
        desc.setAlignmentX(CENTER_ALIGNMENT);

        p.add(Box.createVerticalGlue());
        p.add(title);
        p.add(Box.createVerticalStrut(4));
        p.add(desc);
        p.add(Box.createVerticalGlue());
        return p;
    }

    private void loadFlights() {
        SwingWorker<List<FlightBoardingSummary>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<FlightBoardingSummary> doInBackground() throws Exception {
                OperationalStation st = StationContext.getCurrentStation();
                String code = st != null ? st.getCode() : "DEL";
                return BoardingService.getInstance().getScheduledFlights(code);
            }

            @Override
            protected void done() {
                try {
                    List<FlightBoardingSummary> flights = get();
                    flightCombo.removeAllItems();
                    for (FlightBoardingSummary f : flights) {
                        flightCombo.addItem(f);
                    }
                    if (!flights.isEmpty()) {
                        flightCombo.setSelectedIndex(0);
                        activeFlight = flights.get(0);
                        gateBadge.setText("Gate: " + activeFlight.getFormattedGate());
                        loadManifest(activeFlight.flightId());
                    } else {
                        activeFlight = null;
                        gateBadge.setText("Gate: Not assigned");
                        manifestList.clear();
                        manifestTableModel.fireTableDataChanged();
                        manifestCardLayout.show(manifestContainer, "empty");
                        updateManifestKpis();
                    }
                } catch (Exception e) {
                    notificationBanner.showMessage("Failed to load scheduled flights: " + e.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    private void loadManifest(long flightId) {
        SwingWorker<List<BoardingPassenger>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<BoardingPassenger> doInBackground() throws Exception {
                return BoardingService.getInstance().getPassengerManifest(flightId);
            }

            @Override
            protected void done() {
                try {
                    manifestList.clear();
                    manifestList.addAll(get());
                    manifestTableModel.fireTableDataChanged();

                    if (manifestList.isEmpty()) {
                        manifestCardLayout.show(manifestContainer, "empty");
                    } else {
                        manifestCardLayout.show(manifestContainer, "table");
                    }

                    updateManifestKpis();

                } catch (Exception e) {
                    notificationBanner.showMessage("Failed to load passenger manifest: " + e.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    private void updateManifestKpis() {
        int total = manifestList.size();
        int boarded = (int) manifestList.stream().filter(BoardingPassenger::boarded).count();
        int remaining = Math.max(0, total - boarded);

        bookedPassengersCard.setValue(String.valueOf(total));
        boardedPassengersCard.setValue(String.valueOf(boarded));
        remainingPassengersCard.setValue(String.valueOf(remaining));

        BoardingStatus status = activeFlight != null
            ? BoardingService.getInstance().getFlightBoardingStatus(activeFlight.flightId())
            : BoardingStatus.SCHEDULED;

        gateStatusCard.setValue(status.getLabel());
    }

    private void executeBoarding() {
        if (activeFlight == null) {
            notificationBanner.showMessage("Please select an active departure flight.", NotificationBanner.Type.WARNING);
            return;
        }

        String pnr = scanField.getText().trim();
        if (pnr.isEmpty()) {
            notificationBanner.showMessage("Please enter or scan a passenger PNR or Boarding Pass #.", NotificationBanner.Type.WARNING);
            scanField.requestFocusInWindow();
            return;
        }

        boardBtn.setEnabled(false);

        SwingWorker<BoardingValidationResult, Void> worker = new SwingWorker<>() {
            @Override
            protected BoardingValidationResult doInBackground() throws Exception {
                return BoardingService.getInstance().validateAndBoard(pnr, activeFlight.flightId());
            }

            @Override
            protected void done() {
                boardBtn.setEnabled(true);
                try {
                    BoardingValidationResult res = get();
                    if (res.valid()) {
                        notificationBanner.showMessage(res.message() + " " + res.schemaNotice(), NotificationBanner.Type.SUCCESS);
                        scanField.setText("");
                        scanField.requestFocusInWindow();
                        loadManifest(activeFlight.flightId());
                    } else {
                        notificationBanner.showMessage(res.message(), NotificationBanner.Type.ERROR);
                        scanField.requestFocusInWindow();
                    }
                } catch (Exception e) {
                    notificationBanner.showMessage("Boarding validation failed: " + e.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    private void setGateStatus(BoardingStatus status) {
        if (activeFlight == null) return;
        BoardingService.getInstance().updateFlightBoardingStatus(activeFlight.flightId(), status);
        gateStatusCard.setValue(status.getLabel());
        notificationBanner.showMessage("Gate boarding status updated to [" + status.getLabel() + "].", NotificationBanner.Type.SUCCESS);
    }

    private void openAssignGateDialog() {
        if (activeFlight == null) {
            notificationBanner.showMessage("Please select a departure flight first.", NotificationBanner.Type.WARNING);
            return;
        }

        if (!AuthorizationService.hasPermission(Permission.MANAGE_GATES)) {
            notificationBanner.showMessage("Access denied: You do not have MANAGE_GATES permission.", NotificationBanner.Type.ERROR);
            return;
        }

        assignGateBtn.setEnabled(false);
        SwingWorker<List<GateRecord>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<GateRecord> doInBackground() throws Exception {
                OperationalStation st = StationContext.getCurrentStation();
                String code = st != null ? st.getCode() : "DEL";
                return GateService.getInstance().getGatesByAirport(code);
            }

            @Override
            protected void done() {
                assignGateBtn.setEnabled(true);
                try {
                    List<GateRecord> gates = get();
                    showAssignGateModal(gates);
                } catch (Exception e) {
                    notificationBanner.showMessage("Failed to load airport gates: " + e.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    private void showAssignGateModal(List<GateRecord> gates) {
        javax.swing.JDialog dialog = new javax.swing.JDialog(
            javax.swing.SwingUtilities.getWindowAncestor(this),
            "Assign Departure Gate — " + activeFlight.flightNumber(),
            java.awt.Dialog.ModalityType.APPLICATION_MODAL
        );
        dialog.setSize(500, 340);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout());

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Color.WHITE);
        p.setBorder(new EmptyBorder(24, 24, 24, 24));

        JLabel title = new JLabel("Allocate Departure Gate — " + activeFlight.flightNumber());
        title.setFont(AeroFonts.TITLE_CARD);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel sub = new JLabel("Route: " + activeFlight.getRoute() + " | Departure: " + activeFlight.departureTime().format(TIME_FORMAT));
        sub.setFont(AeroFonts.CAPTION);
        sub.setForeground(AeroColors.TEXT_SECONDARY);

        p.add(title);
        p.add(Box.createVerticalStrut(4));
        p.add(sub);
        p.add(Box.createVerticalStrut(20));

        JPanel form = new JPanel(new GridLayout(2, 2, 12, 12));
        form.setOpaque(false);

        JLabel gateLbl = new JLabel("Select Gate:");
        gateLbl.setFont(AeroFonts.BODY_BOLD);
        ModernComboBox<GateRecord> gateCb = new ModernComboBox<>();
        for (GateRecord g : gates) {
            gateCb.addItem(g);
        }

        form.add(gateLbl);
        form.add(gateCb);

        JLabel statusLbl = new JLabel("Current Allocation:");
        statusLbl.setFont(AeroFonts.BODY_BOLD);
        JLabel currVal = new JLabel(activeFlight.getFormattedGate());
        currVal.setFont(AeroFonts.BODY);

        form.add(statusLbl);
        form.add(currVal);

        p.add(form);
        p.add(Box.createVerticalStrut(24));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnRow.setOpaque(false);
        ModernButton cancelBtn = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        cancelBtn.setPreferredSize(new Dimension(90, 36));
        cancelBtn.addActionListener(e -> dialog.dispose());

        ModernButton confirmBtn = new ModernButton("Confirm Allocation", ModernButton.Style.PRIMARY);
        confirmBtn.setPreferredSize(new Dimension(160, 36));
        confirmBtn.addActionListener(e -> {
            GateRecord selGate = (GateRecord) gateCb.getSelectedItem();
            if (selGate == null) return;
            confirmBtn.setEnabled(false);

            java.time.LocalDateTime dep = activeFlight.departureTime();
            java.time.LocalDateTime start = dep.minusMinutes(45);
            java.time.LocalDateTime end = dep.plusMinutes(15);

            SwingWorker<Void, Void> assignWorker = new SwingWorker<>() {
                @Override
                protected Void doInBackground() throws Exception {
                    GateService.getInstance().assignFlightGate(activeFlight.flightId(), selGate.gateId(), start, end);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        dialog.dispose();
                        notificationBanner.showMessage("Gate " + selGate.gateNumber() + " successfully allocated to flight " + activeFlight.flightNumber(), NotificationBanner.Type.SUCCESS);
                        loadFlights();
                    } catch (Exception ex) {
                        confirmBtn.setEnabled(true);
                        notificationBanner.showMessage("Gate assignment failed: " + ex.getMessage(), NotificationBanner.Type.ERROR);
                    }
                }
            };
            assignWorker.execute();
        });

        btnRow.add(cancelBtn);
        btnRow.add(confirmBtn);
        p.add(btnRow);

        dialog.add(p, BorderLayout.CENTER);
        dialog.setVisible(true);
    }

    private class ManifestTableModel extends AbstractTableModel {
        private final String[] columns = {"PNR", "Passenger Name", "Allocated Seat", "Check-in Status", "Gate Clearance"};

        @Override public int getRowCount() { return manifestList.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            BoardingPassenger p = manifestList.get(row);
            return switch (col) {
                case 0 -> p.pnr();
                case 1 -> p.passengerName();
                case 2 -> p.seatNumber();
                case 3 -> p.checkedIn() ? "Checked In" + (p.boardingPassNumber() != null ? " (" + p.boardingPassNumber() + ")" : "") : "Not Checked In";
                case 4 -> p.boarded() ? "BOARDED" : (p.checkedIn() ? "Ready to Board" : "Awaiting Check-in");
                default -> "";
            };
        }
    }

    public void unregisterStationListener() {
        StationContext.removeListener(stationListener);
    }
}
