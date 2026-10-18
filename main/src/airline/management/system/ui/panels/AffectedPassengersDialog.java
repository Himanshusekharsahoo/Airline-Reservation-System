package airline.management.system.ui.panels;

import airline.management.system.UserSession;
import airline.management.system.operations.flight.FlightOperationsService;
import airline.management.system.operations.flight.FlightOperationsService.AffectedPassenger;
import airline.management.system.operations.flight.RebookingFoundationService;
import airline.management.system.operations.flight.RebookingFoundationService.AlternativeFlight;
import airline.management.system.operations.flight.RebookingFoundationService.RebookingResult;
import airline.management.system.security.Permission;
import airline.management.system.ui.components.ModernButton;
import airline.management.system.ui.components.ModernComboBox;
import airline.management.system.ui.components.NotificationBanner;
import airline.management.system.ui.components.StyledTable;
import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
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
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * Operational Disruption Dialog displaying affected passenger manifests
 * for cancelled or delayed flights with transactional rebooking foundation.
 */
public class AffectedPassengersDialog extends JDialog {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final long flightId;
    private final String flightNumber;
    private final String flightRoute;
    private final String flightStatus;

    private final NotificationBanner banner;
    private final StyledTable table;
    private final DefaultTableModel tableModel;
    private final List<AffectedPassenger> passengerList = new ArrayList<>();

    private final ModernButton rebookBtn;
    private final ModernButton refreshBtn;
    private final CardLayout tableCardLayout = new CardLayout();
    private final JPanel tableContainer = new JPanel(tableCardLayout);

    public AffectedPassengersDialog(Frame owner, long flightId, String flightNumber, String flightRoute, String flightStatus) {
        super(owner, "Affected Passenger Manifest — " + flightNumber, true);
        this.flightId = flightId;
        this.flightNumber = flightNumber;
        this.flightRoute = flightRoute;
        this.flightStatus = flightStatus;

        setSize(920, 580);
        setMinimumSize(new Dimension(840, 500));
        setLocationRelativeTo(owner);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(AeroColors.BACKGROUND);
        root.setBorder(new EmptyBorder(20, 24, 20, 24));

        // Header Block
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);

        JPanel titleBlock = new JPanel();
        titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
        titleBlock.setOpaque(false);

        JLabel title = new JLabel("Disrupted Flight Manifest: " + flightNumber);
        title.setFont(AeroFonts.TITLE_PAGE);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel subtitle = new JLabel("Route: " + flightRoute + " • Current Status: " + flightStatus);
        subtitle.setFont(AeroFonts.BODY);
        subtitle.setForeground(AeroColors.TEXT_SECONDARY);

        titleBlock.add(title);
        titleBlock.add(Box.createVerticalStrut(3));
        titleBlock.add(subtitle);
        headerPanel.add(titleBlock, BorderLayout.WEST);

        root.add(headerPanel);
        root.add(Box.createVerticalStrut(12));

        banner = new NotificationBanner();
        root.add(banner);
        root.add(Box.createVerticalStrut(12));

        // Table Setup
        String[] columnNames = {"Passenger Name", "PNR", "Seat", "Reservation", "Payment", "Check-in", "Boarding Pass"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        table = new StyledTable(tableModel);
        table.getSelectionModel().addListSelectionListener(e -> updateActionButtons());

        tableContainer.setOpaque(false);
        tableContainer.add(new JScrollPane(table), "table");
        tableContainer.add(createEmptyState(), "empty");
        tableCardLayout.show(tableContainer, "empty");

        JPanel tableCard = new JPanel(new BorderLayout());
        tableCard.setBackground(Color.WHITE);
        tableCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(12, 14, 12, 14)
        ));
        tableCard.add(tableContainer, BorderLayout.CENTER);
        root.add(tableCard);
        root.add(Box.createVerticalStrut(14));

        // Bottom Controls
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        bottomBar.setOpaque(false);

        refreshBtn = new ModernButton("Refresh", ModernButton.Style.SECONDARY);
        refreshBtn.setPreferredSize(new Dimension(100, 34));
        refreshBtn.addActionListener(e -> loadManifestAsync());

        rebookBtn = new ModernButton("Rebook Passenger", ModernButton.Style.PRIMARY);
        rebookBtn.setPreferredSize(new Dimension(160, 34));
        rebookBtn.setEnabled(false);
        rebookBtn.addActionListener(e -> promptRebooking());

        ModernButton closeBtn = new ModernButton("Close", ModernButton.Style.SECONDARY);
        closeBtn.setPreferredSize(new Dimension(90, 34));
        closeBtn.addActionListener(e -> dispose());

        bottomBar.add(refreshBtn);
        bottomBar.add(rebookBtn);
        bottomBar.add(closeBtn);
        root.add(bottomBar);

        setContentPane(root);
        loadManifestAsync();
    }

    private JPanel createEmptyState() {
        JPanel emptyPanel = new JPanel();
        emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
        emptyPanel.setBackground(Color.WHITE);
        emptyPanel.setBorder(new EmptyBorder(60, 20, 60, 20));

        JLabel title = new JLabel("No passengers booked on this flight.");
        title.setFont(AeroFonts.BODY_BOLD);
        title.setForeground(AeroColors.TEXT_SECONDARY);
        title.setAlignmentX(CENTER_ALIGNMENT);

        emptyPanel.add(Box.createVerticalGlue());
        emptyPanel.add(title);
        emptyPanel.add(Box.createVerticalGlue());
        return emptyPanel;
    }

    private void updateActionButtons() {
        int row = table.getSelectedRow();
        boolean hasSel = row >= 0 && row < passengerList.size();
        boolean canRebook = hasSel && UserSession.hasPermission(Permission.MANAGE_RESERVATIONS);
        if (canRebook) {
            AffectedPassenger ap = passengerList.get(table.convertRowIndexToModel(row));
            canRebook = !"CANCELLED".equalsIgnoreCase(ap.reservationStatus());
        }
        rebookBtn.setEnabled(canRebook);
    }

    public void loadManifestAsync() {
        refreshBtn.setEnabled(false);
        refreshBtn.setText("Loading…");

        SwingWorker<List<AffectedPassenger>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<AffectedPassenger> doInBackground() throws Exception {
                return FlightOperationsService.getAffectedPassengers(flightId);
            }

            @Override
            protected void done() {
                refreshBtn.setEnabled(true);
                refreshBtn.setText("Refresh");
                try {
                    passengerList.clear();
                    passengerList.addAll(get());
                    tableModel.setRowCount(0);

                    for (AffectedPassenger ap : passengerList) {
                        tableModel.addRow(new Object[]{
                            ap.passengerName(),
                            ap.pnr(),
                            ap.seatNumber(),
                            ap.reservationStatus(),
                            ap.paymentStatus(),
                            ap.checkinStatus(),
                            ap.boardingPassNumber()
                        });
                    }

                    if (passengerList.isEmpty()) {
                        tableCardLayout.show(tableContainer, "empty");
                    } else {
                        tableCardLayout.show(tableContainer, "table");
                    }
                    updateActionButtons();
                } catch (Exception e) {
                    banner.showMessage("Failed to load disruption manifest: " + e.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    private void promptRebooking() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= passengerList.size()) return;
        AffectedPassenger passenger = passengerList.get(table.convertRowIndexToModel(row));

        rebookBtn.setEnabled(false);
        rebookBtn.setText("Searching flights…");

        SwingWorker<List<AlternativeFlight>, Void> searchWorker = new SwingWorker<>() {
            @Override
            protected List<AlternativeFlight> doInBackground() throws Exception {
                return RebookingFoundationService.findEligibleAlternativeFlights(flightId);
            }

            @Override
            protected void done() {
                rebookBtn.setText("Rebook Passenger");
                updateActionButtons();
                try {
                    List<AlternativeFlight> alternatives = get();
                    if (alternatives.isEmpty()) {
                        banner.showMessage("No eligible alternative flights with available seats were found for route [" + flightRoute + "].", NotificationBanner.Type.WARNING);
                        return;
                    }

                    String[] options = new String[alternatives.size()];
                    for (int i = 0; i < alternatives.size(); i++) {
                        AlternativeFlight af = alternatives.get(i);
                        options[i] = String.format("%s (Dep: %s, Seats: %d, Fare: $%.2f)",
                            af.flightNumber(), af.departureTime().format(TIME_FMT), af.availableSeats(), af.fare());
                    }

                    String selection = (String) JOptionPane.showInputDialog(
                        AffectedPassengersDialog.this,
                        "Select alternative flight for passenger [" + passenger.passengerName() + "] (PNR: " + passenger.pnr() + "):",
                        "Operational Rebooking",
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        options,
                        options[0]
                    );

                    if (selection != null) {
                        int selectedIdx = -1;
                        for (int i = 0; i < options.length; i++) {
                            if (options[i].equals(selection)) {
                                selectedIdx = i;
                                break;
                            }
                        }
                        if (selectedIdx >= 0) {
                            AlternativeFlight chosen = alternatives.get(selectedIdx);
                            executeRebooking(passenger.reservationId(), chosen.flightId(), chosen.flightNumber());
                        }
                    }
                } catch (Exception ex) {
                    banner.showMessage("Error finding alternative flights: " + ex.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };
        searchWorker.execute();
    }

    private void executeRebooking(long reservationId, long targetFlightId, String targetFlightNumber) {
        rebookBtn.setEnabled(false);
        rebookBtn.setText("Rebooking…");

        SwingWorker<RebookingResult, Void> rebookWorker = new SwingWorker<>() {
            @Override
            protected RebookingResult doInBackground() throws Exception {
                return RebookingFoundationService.rebookPassenger(reservationId, targetFlightId, null);
            }

            @Override
            protected void done() {
                rebookBtn.setText("Rebook Passenger");
                try {
                    RebookingResult res = get();
                    if (res.success()) {
                        banner.showMessage("Passenger rebooked successfully! New PNR: " + res.newPnr() + " on flight " + res.targetFlightNumber() + " (Seat " + res.assignedSeat() + ")", NotificationBanner.Type.SUCCESS);
                        loadManifestAsync();
                    } else {
                        banner.showMessage(res.message(), NotificationBanner.Type.ERROR);
                    }
                } catch (Exception e) {
                    banner.showMessage("Rebooking transaction failed: " + e.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };
        rebookWorker.execute();
    }
}
