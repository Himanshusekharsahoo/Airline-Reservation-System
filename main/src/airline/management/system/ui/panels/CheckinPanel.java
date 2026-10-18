package airline.management.system.ui.panels;

import airline.management.system.operations.checkin.BoardingPassRecord;
import airline.management.system.operations.checkin.CheckinPassengerDetails;
import airline.management.system.operations.checkin.CheckinResult;
import airline.management.system.operations.checkin.CheckinService;
import airline.management.system.operations.checkin.CheckinStatus;
import airline.management.system.operations.baggage.BaggageRecord;
import airline.management.system.operations.baggage.BaggageService;
import airline.management.system.operations.baggage.BaggageStatus;
import airline.management.system.operations.baggage.BaggageType;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.ui.components.AeroIcon;
import airline.management.system.ui.components.AeroScrollPane;
import airline.management.system.ui.components.ModernButton;
import airline.management.system.ui.components.ModernTextField;
import airline.management.system.ui.components.NotificationBanner;
import airline.management.system.ui.components.StatusBadge;
import airline.management.system.ui.navigation.NavigationController;
import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;
import java.math.BigDecimal;
import java.util.List;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;

/**
 * AeroDesk Airline Staff Operations — Passenger Check-in Screen.
 * Empowers airport check-in desk agents to verify passenger reservations, validate payment clearance,
 * inspect seat assignments, and process operational check-in without fabricated data.
 */
public class CheckinPanel extends JPanel {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NavigationController controller;
    private final NotificationBanner notificationBanner;

    // Search Controls
    private final ModernTextField pnrSearchField = new ModernTextField(18);
    private final ModernButton verifyBtn = new ModernButton("Verify PNR", ModernButton.Style.PRIMARY);
    private final ModernButton clearBtn = new ModernButton("Clear", ModernButton.Style.SECONDARY);

    // Details Container (CardLayout: empty vs details)
    private final CardLayout detailsCardLayout = new CardLayout();
    private final JPanel detailsContainer = new JPanel(detailsCardLayout);

    // Active Verification State
    private CheckinPassengerDetails currentDetails = null;

    // UI Field Labels
    private final JLabel pnrVal = new JLabel("—");
    private final JLabel passengerNameVal = new JLabel("—");
    private final JLabel phoneVal = new JLabel("—");
    private final JLabel emailVal = new JLabel("—");

    private final JLabel flightNoVal = new JLabel("—");
    private final JLabel airlineVal = new JLabel("—");
    private final JLabel routeVal = new JLabel("—");
    private final JLabel departureVal = new JLabel("—");
    private final JLabel arrivalVal = new JLabel("—");

    private final JLabel seatVal = new JLabel("—");
    private final StatusBadge resStatusBadge = new StatusBadge("—");
    private final StatusBadge pmtStatusBadge = new StatusBadge("—");
    private final JLabel pmtMethodVal = new JLabel("—");
    private final JLabel pmtRefVal = new JLabel("—");
    private final StatusBadge checkinBadge = new StatusBadge("—");
    private final JLabel bpNumberVal = new JLabel("—");
    private final JLabel assignedGateVal = new JLabel("—");

    private final ModernButton confirmCheckinBtn;
    private final ModernButton viewBoardingPassBtn;
    private final JLabel baggageSummaryVal = new JLabel("No checked baggage registered");
    private final ModernButton registerBaggageBtn = new ModernButton("+ Register Baggage", ModernButton.Style.PRIMARY);
    private final JLabel eligibilityBanner = new JLabel(" ", SwingConstants.CENTER);

    public CheckinPanel(NavigationController controller) {
        this.controller = controller;

        confirmCheckinBtn = new ModernButton("Execute Passenger Check-in", ModernButton.Style.PRIMARY);
        confirmCheckinBtn.setPreferredSize(new Dimension(240, 38));
        confirmCheckinBtn.addActionListener(e -> executeCheckin());

        viewBoardingPassBtn = new ModernButton("View Boarding Pass", ModernButton.Style.SECONDARY);
        viewBoardingPassBtn.setPreferredSize(new Dimension(190, 38));
        viewBoardingPassBtn.setVisible(false);
        viewBoardingPassBtn.addActionListener(e -> openBoardingPassDialog());

        registerBaggageBtn.setPreferredSize(new Dimension(170, 36));
        registerBaggageBtn.setEnabled(false);
        registerBaggageBtn.addActionListener(e -> showRegisterBaggageDialog());

        setLayout(new BorderLayout());
        setBackground(AeroColors.BACKGROUND);

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

        JLabel title = new JLabel("Passenger Check-in Desk");
        title.setFont(AeroFonts.TITLE_PAGE);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel subtitle = new JLabel("Airport counter passenger identity verification, seat confirmation & boarding pass clearance.");
        subtitle.setFont(AeroFonts.BODY);
        subtitle.setForeground(AeroColors.TEXT_SECONDARY);

        titleBlock.add(title);
        titleBlock.add(Box.createVerticalStrut(2));
        titleBlock.add(subtitle);
        header.add(titleBlock, BorderLayout.WEST);

        JLabel staffBadge = new JLabel("● Check-in Agent Station");
        staffBadge.setFont(AeroFonts.CAPTION_BOLD);
        staffBadge.setForeground(AeroColors.PRIMARY);
        header.add(staffBadge, BorderLayout.EAST);

        mainContainer.add(header);
        mainContainer.add(Box.createVerticalStrut(16));

        // Notification Banner
        notificationBanner = new NotificationBanner();
        mainContainer.add(notificationBanner);
        mainContainer.add(Box.createVerticalStrut(14));

        // 2. PNR Search Card
        JPanel searchCard = createSearchCard();
        mainContainer.add(searchCard);
        mainContainer.add(Box.createVerticalStrut(18));

        // 3. Details Container (Empty state vs Detailed checklist)
        detailsContainer.setOpaque(false);
        detailsContainer.add(createEmptyState(), "empty");
        detailsContainer.add(createDetailsView(), "details");
        detailsCardLayout.show(detailsContainer, "empty");

        mainContainer.add(detailsContainer);

        add(new AeroScrollPane(mainContainer), BorderLayout.CENTER);

        // Actions
        verifyBtn.addActionListener(e -> performLookup());
        pnrSearchField.addActionListener(e -> performLookup());
        clearBtn.addActionListener(e -> clearFields());
    }

    private JPanel createSearchCard() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(16, 20, 16, 20)
        ));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        left.setOpaque(false);

        JLabel label = new JLabel("Search PNR:");
        label.setFont(AeroFonts.BODY_BOLD);
        label.setForeground(AeroColors.TEXT_MAIN);
        left.add(label);

        pnrSearchField.setPlaceholder("Enter booking PNR (e.g. 11, PNR-...)");
        pnrSearchField.setPreferredSize(new Dimension(280, AeroConstants.INPUT_HEIGHT));
        left.add(pnrSearchField);

        verifyBtn.setPreferredSize(new Dimension(120, AeroConstants.INPUT_HEIGHT));
        left.add(verifyBtn);

        clearBtn.setPreferredSize(new Dimension(80, AeroConstants.INPUT_HEIGHT));
        left.add(clearBtn);

        card.add(left, BorderLayout.WEST);
        return card;
    }

    private JPanel createEmptyState() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(60, 20, 60, 20)
        ));

        JPanel iconBox = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        iconBox.setOpaque(false);
        iconBox.add(new AeroIcon(AeroIcon.Type.RESERVATION, 42).color(AeroColors.PRIMARY));
        iconBox.setAlignmentX(CENTER_ALIGNMENT);

        JLabel title = new JLabel("Ready for Passenger Check-in Verification", SwingConstants.CENTER);
        title.setFont(AeroFonts.TITLE_SECTION);
        title.setForeground(AeroColors.TEXT_MAIN);
        title.setAlignmentX(CENTER_ALIGNMENT);

        JLabel desc = new JLabel("Enter a passenger reservation PNR above to inspect itinerary details, verify payment clearance, and confirm boarding eligibility.", SwingConstants.CENTER);
        desc.setFont(AeroFonts.BODY);
        desc.setForeground(AeroColors.TEXT_SECONDARY);
        desc.setAlignmentX(CENTER_ALIGNMENT);

        panel.add(iconBox);
        panel.add(Box.createVerticalStrut(10));
        panel.add(title);
        panel.add(Box.createVerticalStrut(6));
        panel.add(desc);

        return panel;
    }

    private JPanel createDetailsView() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);

        // Eligibility Banner
        eligibilityBanner.setFont(AeroFonts.BODY_BOLD);
        eligibilityBanner.setOpaque(true);
        eligibilityBanner.setBorder(new EmptyBorder(10, 16, 10, 16));
        panel.add(eligibilityBanner);
        panel.add(Box.createVerticalStrut(16));

        // Two Cards Row: Passenger/Flight (Left) and Verification Status (Right)
        JPanel grid = new JPanel(new GridLayout(1, 2, 18, 0));
        grid.setOpaque(false);

        // Card 1: Passenger & Route Details
        JPanel card1 = createSectionCard("Reservation & Flight Details", "Authoritative booking record");
        JPanel infoGrid = new JPanel(new GridLayout(6, 2, 8, 8));
        infoGrid.setOpaque(false);
        addInfoRow(infoGrid, "PNR Reference", pnrVal);
        addInfoRow(infoGrid, "Passenger Name", passengerNameVal);
        addInfoRow(infoGrid, "Contact Phone", phoneVal);
        addInfoRow(infoGrid, "Flight Number", flightNoVal);
        addInfoRow(infoGrid, "Airline Carrier", airlineVal);
        addInfoRow(infoGrid, "Flight Route", routeVal);
        card1.add(infoGrid, BorderLayout.CENTER);
        grid.add(card1);

        // Card 2: Operational Verification Checklist
        JPanel card2 = createSectionCard("Operational Verification", "Payment status, gate and boarding pass details");
        JPanel checkGrid = new JPanel(new GridLayout(8, 2, 8, 8));
        checkGrid.setOpaque(false);
        addBadgeRow(checkGrid, "Reservation Status", resStatusBadge);
        addBadgeRow(checkGrid, "Payment Clearance", pmtStatusBadge);
        addInfoRow(checkGrid, "Payment Method", pmtMethodVal);
        addInfoRow(checkGrid, "Transaction Ref", pmtRefVal);
        addInfoRow(checkGrid, "Allocated Seat", seatVal);
        addInfoRow(checkGrid, "Assigned Gate", assignedGateVal);
        addInfoRow(checkGrid, "Boarding Pass #", bpNumberVal);
        addBadgeRow(checkGrid, "Check-in Status", checkinBadge);
        card2.add(checkGrid, BorderLayout.CENTER);
        grid.add(card2);

        panel.add(grid);
        panel.add(Box.createVerticalStrut(18));

        // Card 3: Checked Baggage Handling
        JPanel bagCard = createSectionCard("Checked Baggage Operations", "Luggage registration, weight clearance, and tag tracking");
        JPanel bagRow = new JPanel(new BorderLayout(16, 0));
        bagRow.setOpaque(false);

        JPanel bagTextPanel = new JPanel();
        bagTextPanel.setLayout(new BoxLayout(bagTextPanel, BoxLayout.Y_AXIS));
        bagTextPanel.setOpaque(false);

        JLabel bagTitle = new JLabel("Registered Luggage Units:");
        bagTitle.setFont(AeroFonts.CAPTION_BOLD);
        bagTitle.setForeground(AeroColors.TEXT_SECONDARY);

        baggageSummaryVal.setFont(AeroFonts.BODY);
        baggageSummaryVal.setForeground(AeroColors.TEXT_MAIN);

        bagTextPanel.add(bagTitle);
        bagTextPanel.add(Box.createVerticalStrut(4));
        bagTextPanel.add(baggageSummaryVal);

        JPanel bagBtnGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bagBtnGroup.setOpaque(false);
        bagBtnGroup.add(registerBaggageBtn);

        bagRow.add(bagTextPanel, BorderLayout.CENTER);
        bagRow.add(bagBtnGroup, BorderLayout.EAST);
        bagCard.add(bagRow, BorderLayout.CENTER);

        panel.add(bagCard);
        panel.add(Box.createVerticalStrut(18));

        // Action Bar with Phase 12 Database Integration
        JPanel actionCard = new JPanel(new BorderLayout());
        actionCard.setBackground(Color.WHITE);
        actionCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(16, 20, 16, 20)
        ));

        JPanel noticePanel = new JPanel();
        noticePanel.setLayout(new BoxLayout(noticePanel, BoxLayout.Y_AXIS));
        noticePanel.setOpaque(false);

        JLabel noticeTitle = new JLabel("Operational Status — Database Check-in & Boarding Pass:");
        noticeTitle.setFont(AeroFonts.CAPTION_BOLD);
        noticeTitle.setForeground(AeroColors.TEXT_MAIN);

        JLabel noticeDesc = new JLabel("<html>Live transactional check-in backed by MySQL persistence. Real boarding pass generation<br>creates authoritative security hashes and binds directly to flight gate assignments.</html>");
        noticeDesc.setFont(AeroFonts.MUTED);
        noticeDesc.setForeground(AeroColors.TEXT_SECONDARY);

        noticePanel.add(noticeTitle);
        noticePanel.add(Box.createVerticalStrut(2));
        noticePanel.add(noticeDesc);

        JPanel actionBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actionBtns.setOpaque(false);
        actionBtns.add(viewBoardingPassBtn);
        actionBtns.add(confirmCheckinBtn);

        actionCard.add(noticePanel, BorderLayout.WEST);
        actionCard.add(actionBtns, BorderLayout.EAST);

        panel.add(actionCard);
        return panel;
    }

    private JPanel createSectionCard(String title, String subtitle) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(16, 18, 16, 18)
        ));

        JPanel h = new JPanel();
        h.setLayout(new BoxLayout(h, BoxLayout.Y_AXIS));
        h.setOpaque(false);
        h.setBorder(new EmptyBorder(0, 0, 12, 0));

        JLabel t = new JLabel(title);
        t.setFont(AeroFonts.TITLE_CARD);
        t.setForeground(AeroColors.TEXT_MAIN);

        JLabel s = new JLabel(subtitle);
        s.setFont(AeroFonts.CAPTION);
        s.setForeground(AeroColors.TEXT_SECONDARY);

        h.add(t);
        h.add(Box.createVerticalStrut(2));
        h.add(s);
        card.add(h, BorderLayout.NORTH);
        return card;
    }

    private void addInfoRow(JPanel container, String label, JLabel valLabel) {
        JLabel l = new JLabel(label);
        l.setFont(AeroFonts.CAPTION);
        l.setForeground(AeroColors.TEXT_SECONDARY);

        valLabel.setFont(AeroFonts.BODY_BOLD);
        valLabel.setForeground(AeroColors.TEXT_MAIN);

        container.add(l);
        container.add(valLabel);
    }

    private void addBadgeRow(JPanel container, String label, StatusBadge badge) {
        JLabel l = new JLabel(label);
        l.setFont(AeroFonts.CAPTION);
        l.setForeground(AeroColors.TEXT_SECONDARY);

        container.add(l);
        container.add(badge);
    }

    private void performLookup() {
        String pnr = pnrSearchField.getText().trim();
        if (pnr.isEmpty()) {
            notificationBanner.showMessage("Please enter a PNR to verify.", NotificationBanner.Type.WARNING);
            pnrSearchField.requestFocusInWindow();
            return;
        }

        verifyBtn.setEnabled(false);
        verifyBtn.setText("Verifying...");

        SwingWorker<CheckinPassengerDetails, Void> worker = new SwingWorker<>() {
            @Override
            protected CheckinPassengerDetails doInBackground() throws Exception {
                return CheckinService.getInstance().lookupReservation(pnr);
            }

            @Override
            protected void done() {
                verifyBtn.setEnabled(true);
                verifyBtn.setText("Verify PNR");
                try {
                    CheckinPassengerDetails details = get();
                    if (details == null) {
                        currentDetails = null;
                        detailsCardLayout.show(detailsContainer, "empty");
                        notificationBanner.showMessage("No reservation found for PNR: " + pnr, NotificationBanner.Type.ERROR);
                        return;
                    }

                    currentDetails = details;
                    populateDetails(details);
                    detailsCardLayout.show(detailsContainer, "details");
                    notificationBanner.setVisible(false);

                } catch (Exception e) {
                    notificationBanner.showMessage("Error verifying reservation: " + e.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };

        worker.execute();
    }

    private void populateDetails(CheckinPassengerDetails d) {
        pnrVal.setText(d.pnr());
        passengerNameVal.setText(d.passengerName());
        phoneVal.setText(d.phone() != null ? d.phone() : "—");
        emailVal.setText(d.email() != null ? d.email() : "—");

        flightNoVal.setText(d.flightNumber());
        airlineVal.setText(d.airline());
        routeVal.setText(d.source() + " → " + d.destination());
        departureVal.setText(d.departureTime().format(TIME_FORMAT));
        arrivalVal.setText(d.arrivalTime().format(TIME_FORMAT));

        seatVal.setText(d.seatNumber() != null ? d.seatNumber() : "UNASSIGNED");
        resStatusBadge.setText(d.reservationStatus());
        pmtStatusBadge.setText(d.paymentStatus());
        pmtMethodVal.setText(d.paymentMethod() != null ? d.paymentMethod() : "—");
        pmtRefVal.setText(d.transactionReference() != null ? d.transactionReference() : "—");

        checkinBadge.setText(d.checkinStatus().getLabel());
        bpNumberVal.setText(d.boardingPassNumber() != null ? d.boardingPassNumber() : "—");
        assignedGateVal.setText(d.assignedGate() != null && !d.assignedGate().isBlank() ? d.assignedGate() : "Not assigned");

        boolean eligible = d.isEligibleForCheckin();
        confirmCheckinBtn.setEnabled(eligible && d.checkinStatus() != CheckinStatus.CHECKED_IN);

        if (d.checkinStatus() == CheckinStatus.CHECKED_IN) {
            eligibilityBanner.setText("PASSENGER ALREADY CHECKED IN (BOARDING PASS ISSUED)");
            eligibilityBanner.setBackground(AeroColors.SUCCESS_BG);
            eligibilityBanner.setForeground(AeroColors.SUCCESS);
            confirmCheckinBtn.setText("Passenger Checked In");
            confirmCheckinBtn.setEnabled(false);
            viewBoardingPassBtn.setVisible(true);
            viewBoardingPassBtn.setEnabled(true);
        } else if (eligible) {
            eligibilityBanner.setText("PASSENGER ELIGIBLE FOR CHECK-IN & BOARDING CLEARANCE");
            eligibilityBanner.setBackground(AeroColors.SUCCESS_BG);
            eligibilityBanner.setForeground(AeroColors.SUCCESS);
            confirmCheckinBtn.setText("Execute Passenger Check-in");
            viewBoardingPassBtn.setVisible(false);
        } else if (!d.isConfirmed()) {
            eligibilityBanner.setText("INELIGIBLE: RESERVATION IS " + d.reservationStatus());
            eligibilityBanner.setBackground(AeroColors.DANGER_BG);
            eligibilityBanner.setForeground(AeroColors.DANGER);
            confirmCheckinBtn.setText("Check-in Unavailable");
            viewBoardingPassBtn.setVisible(false);
        } else {
            eligibilityBanner.setText("INELIGIBLE: PAYMENT IS PENDING / UNCONFIRMED");
            eligibilityBanner.setBackground(AeroColors.WARNING_BG);
            eligibilityBanner.setForeground(new Color(0xB4, 0x53, 0x09));
            confirmCheckinBtn.setText("Payment Required");
            viewBoardingPassBtn.setVisible(false);
        }

        loadBaggageAsync(d.pnr(), d.checkinStatus());
    }

    private void loadBaggageAsync(String pnr, CheckinStatus status) {
        SwingWorker<List<BaggageRecord>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<BaggageRecord> doInBackground() throws Exception {
                return BaggageService.getInstance().getBaggageByPnr(pnr);
            }

            @Override
            protected void done() {
                try {
                    List<BaggageRecord> bags = get();
                    if (bags.isEmpty()) {
                        baggageSummaryVal.setText("No checked baggage registered for this passenger journey.");
                    } else {
                        StringBuilder sb = new StringBuilder("<html><b>" + bags.size() + " Checked Unit" + (bags.size() == 1 ? "" : "s") + ":</b><br>");
                        for (BaggageRecord b : bags) {
                            sb.append("• Tag: <b>").append(b.tagNumber()).append("</b> (")
                              .append(b.weightKg()).append(" kg) — [")
                              .append(b.status().name()).append("]<br>");
                        }
                        sb.append("</html>");
                        baggageSummaryVal.setText(sb.toString());
                    }

                    boolean canManageBaggage = AuthorizationService.hasPermission(Permission.MANAGE_BAGGAGE);
                    registerBaggageBtn.setVisible(canManageBaggage);
                    registerBaggageBtn.setEnabled(canManageBaggage && status == CheckinStatus.CHECKED_IN);
                } catch (Exception ignored) {
                    baggageSummaryVal.setText("Unable to load baggage records.");
                }
            }
        };
        worker.execute();
    }

    private void showRegisterBaggageDialog() {
        if (currentDetails == null) return;
        Component parent = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parent instanceof java.awt.Frame ? (java.awt.Frame) parent : null, "Register Baggage — PNR " + currentDetails.pnr(), true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(440, 320);
        dialog.setLocationRelativeTo(parent);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(20, 24, 20, 24));
        content.setBackground(AeroColors.BACKGROUND);

        JLabel desc = new JLabel("<html>Register checked luggage for passenger <b>" + currentDetails.passengerName() + "</b></html>");
        desc.setFont(AeroFonts.BODY);
        content.add(desc);
        content.add(Box.createVerticalStrut(14));

        ModernTextField weightField = new ModernTextField(10);
        weightField.setPlaceholder("Weight in KG (e.g. 15.00)");
        ModernTextField remarksField = new ModernTextField(20);
        remarksField.setPlaceholder("Optional handling remarks");

        JPanel r1 = new JPanel(new BorderLayout(8, 0));
        r1.setOpaque(false);
        JLabel wLbl = new JLabel("Weight (KG):");
        wLbl.setFont(AeroFonts.BODY_BOLD);
        wLbl.setPreferredSize(new Dimension(110, 36));
        r1.add(wLbl, BorderLayout.WEST);
        r1.add(weightField, BorderLayout.CENTER);
        content.add(r1);
        content.add(Box.createVerticalStrut(10));

        JPanel r2 = new JPanel(new BorderLayout(8, 0));
        r2.setOpaque(false);
        JLabel remLbl = new JLabel("Remarks:");
        remLbl.setFont(AeroFonts.BODY_BOLD);
        remLbl.setPreferredSize(new Dimension(110, 36));
        r2.add(remLbl, BorderLayout.WEST);
        r2.add(remarksField, BorderLayout.CENTER);
        content.add(r2);
        content.add(Box.createVerticalStrut(18));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnRow.setOpaque(false);
        ModernButton cancelBtn = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        ModernButton submitBtn = new ModernButton("Register Baggage", ModernButton.Style.PRIMARY);

        cancelBtn.addActionListener(e -> dialog.dispose());
        submitBtn.addActionListener(e -> {
            String wStr = weightField.getText().trim();
            BigDecimal weight;
            try {
                weight = new BigDecimal(wStr);
                if (weight.compareTo(BigDecimal.ZERO) <= 0 || weight.compareTo(new BigDecimal("70.00")) > 0) {
                    JOptionPane.showMessageDialog(dialog, "Weight must be between 0.1 and 70.00 kg.", "Validation Error", JOptionPane.WARNING_MESSAGE);
                    return;
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Invalid weight value.", "Validation Error", JOptionPane.WARNING_MESSAGE);
                return;
            }

            submitBtn.setEnabled(false);
            SwingWorker<BaggageRecord, Void> regWorker = new SwingWorker<>() {
                @Override
                protected BaggageRecord doInBackground() throws Exception {
                    return BaggageService.getInstance().registerBaggage(
                        currentDetails.pnr(), weight, BaggageType.CHECKED, remarksField.getText().trim()
                    );
                }

                @Override
                protected void done() {
                    try {
                        BaggageRecord b = get();
                        dialog.dispose();
                        notificationBanner.showMessage("Baggage [" + b.tagNumber() + "] registered (" + b.weightKg() + " kg).", NotificationBanner.Type.SUCCESS);
                        loadBaggageAsync(currentDetails.pnr(), currentDetails.checkinStatus());
                    } catch (Exception ex) {
                        submitBtn.setEnabled(true);
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        JOptionPane.showMessageDialog(dialog, cause.getMessage(), "Error Registering Baggage", JOptionPane.ERROR_MESSAGE);
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

    private void executeCheckin() {
        if (currentDetails == null) return;

        confirmCheckinBtn.setEnabled(false);
        confirmCheckinBtn.setText("Processing...");

        SwingWorker<CheckinResult, Void> worker = new SwingWorker<>() {
            @Override
            protected CheckinResult doInBackground() throws Exception {
                return CheckinService.getInstance().processCheckin(currentDetails.pnr(), currentDetails.seatNumber());
            }

            @Override
            protected void done() {
                try {
                    CheckinResult res = get();
                    if (res.success()) {
                        currentDetails = res.details();
                        populateDetails(res.details());
                        notificationBanner.showMessage(res.message() + " " + res.schemaNotice(), NotificationBanner.Type.SUCCESS);
                    } else {
                        confirmCheckinBtn.setEnabled(true);
                        confirmCheckinBtn.setText("Execute Passenger Check-in");
                        notificationBanner.showMessage(res.message(), NotificationBanner.Type.ERROR);
                    }
                } catch (Exception e) {
                    confirmCheckinBtn.setEnabled(true);
                    confirmCheckinBtn.setText("Execute Passenger Check-in");
                    notificationBanner.showMessage("Failed to process check-in: " + e.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };

        worker.execute();
    }

    private void openBoardingPassDialog() {
        if (currentDetails == null) return;
        viewBoardingPassBtn.setEnabled(false);

        SwingWorker<BoardingPassRecord, Void> worker = new SwingWorker<>() {
            @Override
            protected BoardingPassRecord doInBackground() throws Exception {
                return CheckinService.getInstance().getBoardingPass(currentDetails.pnr());
            }

            @Override
            protected void done() {
                viewBoardingPassBtn.setEnabled(true);
                try {
                    BoardingPassRecord bp = get();
                    if (bp != null) {
                        showBoardingPassModal(bp);
                    } else {
                        notificationBanner.showMessage("No boarding pass record found for PNR: " + currentDetails.pnr(), NotificationBanner.Type.ERROR);
                    }
                } catch (Exception e) {
                    notificationBanner.showMessage("Error loading boarding pass: " + e.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    private void showBoardingPassModal(BoardingPassRecord bp) {
        javax.swing.JDialog dialog = new javax.swing.JDialog(
            javax.swing.SwingUtilities.getWindowAncestor(this),
            "AeroDesk — Verified Boarding Pass",
            java.awt.Dialog.ModalityType.APPLICATION_MODAL
        );
        dialog.setSize(580, 430);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout());

        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(new EmptyBorder(24, 28, 24, 28));

        // Header
        JPanel hPanel = new JPanel(new BorderLayout());
        hPanel.setOpaque(false);
        hPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 2, 0, AeroColors.PRIMARY),
            new EmptyBorder(0, 0, 12, 0)
        ));

        JLabel airLabel = new JLabel(bp.airline() + " • OFFICIAL BOARDING PASS");
        airLabel.setFont(AeroFonts.TITLE_CARD);
        airLabel.setForeground(AeroColors.PRIMARY);

        JLabel fltBadge = new JLabel(bp.flightNumber());
        fltBadge.setFont(AeroFonts.TITLE_PAGE);
        fltBadge.setForeground(AeroColors.TEXT_MAIN);

        hPanel.add(airLabel, BorderLayout.WEST);
        hPanel.add(fltBadge, BorderLayout.EAST);
        card.add(hPanel, BorderLayout.NORTH);

        // Grid Details
        JPanel body = new JPanel(new GridLayout(5, 2, 16, 12));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(18, 0, 18, 0));

        addModalRow(body, "PASSENGER NAME", bp.passengerName());
        addModalRow(body, "PNR REFERENCE", bp.pnr());
        addModalRow(body, "BOARDING PASS #", bp.boardingPassNumber());
        addModalRow(body, "BOARDING GROUP", bp.boardingGroup());
        addModalRow(body, "FLIGHT ROUTE", bp.getRoute());
        addModalRow(body, "DEPARTURE TIME", bp.departureTime().format(TIME_FORMAT));
        addModalRow(body, "ASSIGNED SEAT", bp.seatNumber());
        addModalRow(body, "DEPARTURE GATE", bp.getFormattedGate());
        addModalRow(body, "CHECK-IN STATUS", bp.checkinStatus());
        addModalRow(body, "ISSUED AT", bp.issuedAt().format(TIME_FORMAT));

        card.add(body, BorderLayout.CENTER);

        // Footer
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        footer.setOpaque(false);
        ModernButton closeBtn = new ModernButton("Close", ModernButton.Style.PRIMARY);
        closeBtn.setPreferredSize(new Dimension(100, 36));
        closeBtn.addActionListener(e -> dialog.dispose());
        footer.add(closeBtn);
        card.add(footer, BorderLayout.SOUTH);

        dialog.add(card, BorderLayout.CENTER);
        dialog.setVisible(true);
    }

    private void addModalRow(JPanel container, String label, String value) {
        JPanel row = new JPanel(new BorderLayout(4, 2));
        row.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setFont(AeroFonts.CAPTION);
        l.setForeground(AeroColors.TEXT_SECONDARY);
        JLabel v = new JLabel(value != null && !value.isBlank() ? value : "—");
        v.setFont(AeroFonts.BODY_BOLD);
        v.setForeground(AeroColors.TEXT_MAIN);
        row.add(l, BorderLayout.NORTH);
        row.add(v, BorderLayout.CENTER);
        container.add(row);
    }

    private void clearFields() {
        pnrSearchField.setText("");
        currentDetails = null;
        bpNumberVal.setText("—");
        assignedGateVal.setText("—");
        viewBoardingPassBtn.setVisible(false);
        detailsCardLayout.show(detailsContainer, "empty");
        notificationBanner.setVisible(false);
        pnrSearchField.requestFocusInWindow();
    }
}
