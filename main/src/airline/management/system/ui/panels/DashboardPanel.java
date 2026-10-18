package airline.management.system.ui.panels;

import airline.management.system.DBConnection;
import airline.management.system.TableUtils;
import airline.management.system.UserSession;
import airline.management.system.security.Role;
import airline.management.system.ui.components.AeroIcon;
import airline.management.system.ui.components.AeroScrollPane;
import airline.management.system.ui.components.ModernButton;
import airline.management.system.ui.components.NotificationBanner;
import airline.management.system.ui.components.StatusBadge;
import airline.management.system.ui.components.StyledTable;
import airline.management.system.ui.layout.StationContext;
import airline.management.system.ui.navigation.NavRoute;
import airline.management.system.ui.navigation.NavigationController;
import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * AeroDesk Operations Dashboard: Real-time airline operations overview,
 * flight operations board, operational priority work queue, recent reservations,
 * and fleet operations snapshot.
 */
public class DashboardPanel extends JPanel {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy • HH:mm");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final NavigationController navigationController;

    // Header Controls
    private final JLabel timeLabel;
    private final ModernButton refreshButton;
    private final NotificationBanner notificationBanner;

    // Refined KPI Strip Metric Items
    private final MetricItem flightsMetric;
    private final MetricItem passengersMetric;
    private final MetricItem checkinsMetric;
    private final MetricItem disruptionsMetric;

    // Needs Attention Priority Badges & Value Labels
    private final StatusBadge disruptionsBadge = new StatusBadge("CLEAR");
    private final StatusBadge baggageBadge = new StatusBadge("CLEAR");
    private final StatusBadge pirClaimsBadge = new StatusBadge("CLEAR");
    private final StatusBadge checkinBadge = new StatusBadge("INFO");
    private final StatusBadge gateBadge = new StatusBadge("READY");

    private final JLabel disruptionsVal = new JLabel("All flights operating normally");
    private final JLabel baggageVal = new JLabel("No baggage exceptions reported");
    private final JLabel pirClaimsAttnVal = new JLabel("No open claims or irregularity reports");
    private final JLabel checkinAttnVal = new JLabel("Check-in counters open");
    private final JLabel gateAttnVal = new JLabel("Gates operational and ready");
    private final JLabel checkinSnapshotVal = new JLabel("—");
    private final JLabel boardingSnapshotVal = new JLabel("—");

    // Flight Board Container & Table
    private final CardLayout flightsCardLayout = new CardLayout();
    private final JPanel flightsContainer = new JPanel(flightsCardLayout);
    private final StyledTable flightsTable = new StyledTable();

    // Recent Reservations Container & Table
    private final CardLayout reservationsCardLayout = new CardLayout();
    private final JPanel reservationsContainer = new JPanel(reservationsCardLayout);
    private final StyledTable reservationsTable = new StyledTable();

    // Snapshot Metric Labels
    private final JLabel totalSeatsVal = new JLabel("—");
    private final JLabel availSeatsVal = new JLabel("—");
    private final JLabel occupiedSeatsVal = new JLabel("—");
    private final StyledProgressBar occupancyBar = new StyledProgressBar();

    private final JLabel confirmedResVal = new JLabel("—");
    private final JLabel cancelledResVal = new JLabel("—");
    private final JLabel pendingResVal = new JLabel("—");

    private final JLabel compPaymentsVal = new JLabel("—");
    private final JLabel compAmountVal = new JLabel("—");
    private final JLabel refPaymentsVal = new JLabel("—");
    private final JLabel refAmountVal = new JLabel("—");
    private final JLabel netSettlementVal = new JLabel("—");
    private final JLabel baggageOpsVal = new JLabel("—");
    private final JLabel pirClaimsVal = new JLabel("—");
    private final JLabel operationalAlertsVal = new JLabel("Loading operational alerts…");

    public DashboardPanel(NavigationController controller) {
        this.navigationController = controller;
        setLayout(new BorderLayout());
        setBackground(AeroColors.BACKGROUND);

        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(AeroColors.BACKGROUND);
        contentPanel.setBorder(new EmptyBorder(20, 24, 28, 24));
        contentPanel.setAlignmentX(LEFT_ALIGNMENT);

        // 1. Compact Premium Operational Hero
        JPanel heroPanel = new SurfacePanel(new BorderLayout(20, 0));
        heroPanel.setBorder(new EmptyBorder(16, 20, 16, 20));
        heroPanel.setAlignmentX(LEFT_ALIGNMENT);
        heroPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 148));

        JPanel leftHero = new JPanel();
        leftHero.setLayout(new BoxLayout(leftHero, BoxLayout.Y_AXIS));
        leftHero.setOpaque(false);

        int hour = LocalDateTime.now().getHour();
        String greeting = hour < 12 ? "Good morning" : (hour < 17 ? "Good afternoon" : "Good evening");
        String user = UserSession.username() != null ? UserSession.username() : "Operator";
        JLabel welcomeTitle = new JLabel(greeting + ", " + user);
        welcomeTitle.setFont(AeroFonts.TITLE_PAGE);
        welcomeTitle.setForeground(AeroColors.TEXT_MAIN);

        JLabel welcomeSubtitle = new JLabel(StationContext.getCurrentStation().getAirportName() + " (" + StationContext.getCurrentStation().getCode() + ") — Active Dispatch Shift");
        welcomeSubtitle.setFont(AeroFonts.PAGE_SUBTITLE);
        welcomeSubtitle.setForeground(AeroColors.TEXT_SECONDARY);

        // Compact operational metadata strip
        JPanel metaStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        metaStrip.setOpaque(false);
        metaStrip.setBorder(new EmptyBorder(6, 0, 0, 0));

        JLabel hubTag = new JLabel("DISPATCH HUB");
        hubTag.setFont(AeroFonts.SECTION_CATEGORY);
        hubTag.setForeground(AeroColors.TEXT_MUTED);

        JLabel hubCode = new JLabel(StationContext.getCurrentStation().getCode());
        hubCode.setFont(AeroFonts.MONO_BOLD);
        hubCode.setForeground(AeroColors.TEXT_MAIN);

        StationContext.addListener(st -> {
            hubCode.setText(st.getCode());
            welcomeSubtitle.setText(st.getAirportName() + " (" + st.getCode() + ") — Active Dispatch Shift");
            refreshData();
        });

        JLabel sep1 = new JLabel("•");
        sep1.setForeground(AeroColors.BORDER);

        JLabel dateLabel = new JLabel(LocalDate.now().format(DATE_FORMAT).toUpperCase());
        dateLabel.setFont(AeroFonts.CAPTION_BOLD);
        dateLabel.setForeground(AeroColors.TEXT_SECONDARY);

        JLabel sep2 = new JLabel("•");
        sep2.setForeground(AeroColors.BORDER);

        JPanel livePill = createLivePill();

        JLabel sep3 = new JLabel("•");
        sep3.setForeground(AeroColors.BORDER);

        JLabel roleFocusLabel = new JLabel(roleFocus(UserSession.getRole()));
        roleFocusLabel.setFont(AeroFonts.MUTED);
        roleFocusLabel.setForeground(AeroColors.TEXT_MUTED);

        metaStrip.add(new AeroIcon(AeroIcon.Type.STATION, 14).color(AeroColors.TEXT_MUTED));
        metaStrip.add(hubTag);
        metaStrip.add(hubCode);
        metaStrip.add(sep1);
        metaStrip.add(dateLabel);
        metaStrip.add(sep2);
        metaStrip.add(livePill);
        metaStrip.add(sep3);
        metaStrip.add(roleFocusLabel);

        leftHero.add(welcomeTitle);
        leftHero.add(Box.createVerticalStrut(2));
        leftHero.add(welcomeSubtitle);
        leftHero.add(metaStrip);

        JPanel rightHero = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        rightHero.setOpaque(false);

        timeLabel = new JLabel(LocalDateTime.now().format(TIME_FORMAT));
        timeLabel.setFont(AeroFonts.MONO);
        timeLabel.setForeground(AeroColors.TEXT_SECONDARY);

        refreshButton = new ModernButton("Refresh", ModernButton.Style.SECONDARY);
        refreshButton.setPreferredSize(new Dimension(100, 32));
        refreshButton.addActionListener(e -> refreshData());

        rightHero.add(timeLabel);
        rightHero.add(refreshButton);

        heroPanel.add(leftHero, BorderLayout.CENTER);
        heroPanel.add(rightHero, BorderLayout.EAST);
        contentPanel.add(heroPanel);
        contentPanel.add(Box.createVerticalStrut(12));

        // Notification Banner
        notificationBanner = new NotificationBanner();
        notificationBanner.setAlignmentX(LEFT_ALIGNMENT);
        contentPanel.add(notificationBanner);
        contentPanel.add(Box.createVerticalStrut(12));

        // 2. Refined KPI Strip (Single cohesive container rather than 4 heavy cards)
        JPanel kpiStrip = new SurfacePanel(new GridLayout(1, 4, 0, 0));
        kpiStrip.setBorder(new EmptyBorder(0, 0, 0, 0));
        kpiStrip.setAlignmentX(LEFT_ALIGNMENT);
        kpiStrip.setMaximumSize(new Dimension(Integer.MAX_VALUE, 140));
        kpiStrip.setPreferredSize(new Dimension(1400, 136));

        flightsMetric = new MetricItem("TODAY'S FLIGHTS", "Scheduled and active routes", "No scheduled movements",
                AeroIcon.Type.FLIGHT, AeroColors.PRIMARY, AeroColors.INFO_BG, true);
        passengersMetric = new MetricItem("PASSENGERS", "Registered passenger profiles", "No registered passenger profiles",
                AeroIcon.Type.PASSENGER, AeroColors.TEXT_SECONDARY, new Color(0xEE, 0xF2, 0xF6), true);
        checkinsMetric = new MetricItem("CHECK-INS", "Checked-in passengers", "No checked-in passengers",
                AeroIcon.Type.CHECKIN, AeroColors.SUCCESS, AeroColors.SUCCESS_BG, true);
        disruptionsMetric = new MetricItem("DISRUPTIONS", "Active IROPS events", "No active disruptions",
                AeroIcon.Type.ALERT, AeroColors.SUCCESS, AeroColors.SUCCESS_BG, false);

        kpiStrip.add(flightsMetric);
        kpiStrip.add(passengersMetric);
        kpiStrip.add(checkinsMetric);
        kpiStrip.add(disruptionsMetric);

        contentPanel.add(kpiStrip);
        contentPanel.add(Box.createVerticalStrut(18));

        // 3. Main Operational Content: Primary Flight Board (Left) & Needs Attention (Right)
        JPanel tablesGrid = new JPanel(new GridBagLayout());
        tablesGrid.setOpaque(false);
        tablesGrid.setAlignmentX(LEFT_ALIGNMENT);
        tablesGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 420));
        tablesGrid.setPreferredSize(new Dimension(1400, 380));

        // Left: Primary Flight Board
        JPanel flightsCardPanel = createSectionCard("Primary Flight Board", "Real-time departure status, gate allocations & available capacity",
                "View Flights →", () -> navigationController.navigateTo(NavRoute.FLIGHTS));

        flightsContainer.setOpaque(false);
        flightsContainer.add(createTableWrapper(flightsTable), "table");
        flightsContainer.add(createEmptyState(AeroIcon.Type.FLIGHT, "No flight movements to display",
                "There are currently no scheduled or active movements requiring attention.", null, null), "empty");
        flightsContainer.add(createEmptyState(AeroIcon.Type.FLIGHT, "Loading flight movements",
                "Retrieving scheduled and active routes.", null, null), "loading");
        flightsCardLayout.show(flightsContainer, "loading");

        flightsCardPanel.add(flightsContainer, BorderLayout.CENTER);
        GridBagConstraints mainColumn = new GridBagConstraints();
        mainColumn.gridx = 0; mainColumn.gridy = 0; mainColumn.weightx = 0.65; mainColumn.weighty = 1;
        mainColumn.fill = GridBagConstraints.BOTH; mainColumn.insets = new java.awt.Insets(0, 0, 0, 16);
        tablesGrid.add(flightsCardPanel, mainColumn);

        // Right: Needs Attention Operational Priority Work Queue
        JPanel needsAttentionPanel = createNeedsAttentionCard();
        GridBagConstraints attentionColumn = new GridBagConstraints();
        attentionColumn.gridx = 1; attentionColumn.gridy = 0; attentionColumn.weightx = 0.35; attentionColumn.weighty = 1;
        attentionColumn.fill = GridBagConstraints.BOTH;
        tablesGrid.add(needsAttentionPanel, attentionColumn);

        contentPanel.add(tablesGrid);
        contentPanel.add(Box.createVerticalStrut(18));

        // 4. Secondary Content: Recent Reservations (Left) & Operations Snapshot (Right)
        JPanel secondaryGrid = new JPanel(new GridLayout(1, 2, 16, 0));
        secondaryGrid.setOpaque(false);
        secondaryGrid.setAlignmentX(LEFT_ALIGNMENT);
        secondaryGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 420));
        secondaryGrid.setPreferredSize(new Dimension(1400, 380));

        // Left Card: Recent Reservations
        JPanel reservationsCardPanel = createSectionCard("Recent Reservations", "Authoritative bookings, passenger records & payment settlement",
                "Reservation Desk →", () -> navigationController.navigateTo(NavRoute.BOOKING));

        reservationsContainer.setOpaque(false);
        reservationsContainer.add(createTableWrapper(reservationsTable), "table");
        reservationsContainer.add(createEmptyState(AeroIcon.Type.RESERVATION, "No recent reservations",
                "There are currently no bookings requiring operational review.", null, null), "empty");
        reservationsContainer.add(createEmptyState(AeroIcon.Type.RESERVATION, "Loading reservations",
                "Retrieving recent booking activity.", null, null), "loading");
        reservationsCardLayout.show(reservationsContainer, "loading");

        reservationsCardPanel.add(reservationsContainer, BorderLayout.CENTER);
        secondaryGrid.add(reservationsCardPanel);

        // Right Card: Operations Snapshot
        JPanel snapshotCard = createSnapshotSection();
        secondaryGrid.add(snapshotCard);

        contentPanel.add(secondaryGrid);
        contentPanel.add(Box.createVerticalStrut(16));

        // 5. Operational Alerts Strip
        contentPanel.add(createAlertsSection());

        // Single primary vertical scroll owner for the entire dashboard
        JScrollPane scrollPane = new AeroScrollPane(contentPanel);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scrollPane, BorderLayout.CENTER);

        // Initial real database data load
        refreshData();
    }

    private static JPanel createLivePill() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(AeroColors.SUCCESS_BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.setColor(new Color(0xBB, 0xF7, 0xD0));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(1, 6, 1, 6));

        JPanel dot = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(AeroColors.SUCCESS);
                g2.fillOval(0, 1, 6, 6);
                g2.dispose();
            }
        };
        dot.setOpaque(false);
        dot.setPreferredSize(new Dimension(6, 8));

        JLabel label = new JLabel("LIVE OPERATIONS");
        label.setFont(AeroFonts.BADGE);
        label.setForeground(AeroColors.SUCCESS);

        p.add(dot);
        p.add(label);
        return p;
    }

    private JPanel createTableWrapper(StyledTable table) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(table.getTableHeader(), BorderLayout.NORTH);

        // Embedding the table with vertical scrolling handled smoothly by the primary dashboard page
        JScrollPane internalScroll = new JScrollPane(table);
        internalScroll.setBorder(BorderFactory.createEmptyBorder());
        internalScroll.setOpaque(false);
        internalScroll.getViewport().setOpaque(false);
        internalScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        internalScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        wrapper.add(internalScroll, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel createSectionCard(String title, String subtitle, String actionLabel, Runnable onAction) {
        JPanel card = new SurfacePanel(new BorderLayout());
        card.setBorder(new EmptyBorder(18, 20, 12, 20));
        card.setPreferredSize(new Dimension(520, 360));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(0, 0, 8, 0));

        JPanel textBlock = new JPanel();
        textBlock.setLayout(new BoxLayout(textBlock, BoxLayout.Y_AXIS));
        textBlock.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(AeroFonts.TITLE_CARD);
        titleLabel.setForeground(AeroColors.TEXT_MAIN);

        JLabel subLabel = new JLabel(subtitle);
        subLabel.setFont(AeroFonts.CAPTION);
        subLabel.setForeground(AeroColors.TEXT_SECONDARY);

        textBlock.add(titleLabel);
        textBlock.add(Box.createVerticalStrut(2));
        textBlock.add(subLabel);
        header.add(textBlock, BorderLayout.WEST);

        if (actionLabel != null && onAction != null) {
            ModernButton btn = new ModernButton(actionLabel, ModernButton.Style.GHOST);
            btn.setPreferredSize(new Dimension(120, 28));
            btn.setFont(AeroFonts.CAPTION_BOLD);
            btn.addActionListener(e -> onAction.run());
            header.add(btn, BorderLayout.EAST);
        }

        card.add(header, BorderLayout.NORTH);
        return card;
    }

    private JPanel createNeedsAttentionCard() {
        JPanel card = new SurfacePanel(new BorderLayout());
        card.setBorder(new EmptyBorder(18, 20, 12, 14));
        card.setPreferredSize(new Dimension(520, 360));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(0, 0, 8, 0));

        JPanel textBlock = new JPanel();
        textBlock.setLayout(new BoxLayout(textBlock, BoxLayout.Y_AXIS));
        textBlock.setOpaque(false);

        JLabel titleLabel = new JLabel("Needs Attention");
        titleLabel.setFont(AeroFonts.TITLE_CARD);
        titleLabel.setForeground(AeroColors.TEXT_MAIN);

        JLabel subLabel = new JLabel("Operational priority feed requiring staff attention");
        subLabel.setFont(AeroFonts.CAPTION);
        subLabel.setForeground(AeroColors.TEXT_SECONDARY);

        textBlock.add(titleLabel);
        textBlock.add(Box.createVerticalStrut(2));
        textBlock.add(subLabel);
        header.add(textBlock, BorderLayout.WEST);

        card.add(header, BorderLayout.NORTH);

        JPanel listPanel = new JPanel(new GridLayout(5, 1, 0, 6));
        listPanel.setOpaque(false);

        listPanel.add(createAttentionItem(AeroIcon.Type.ALERT, disruptionsBadge, "Flight Disruption", disruptionsVal, "IROPS Desk", () -> navigationController.navigateTo(NavRoute.IROPS)));
        listPanel.add(createAttentionItem(AeroIcon.Type.BAGGAGE, baggageBadge, "Baggage Exception", baggageVal, "Baggage Ops", () -> navigationController.navigateTo(NavRoute.BAGGAGE)));
        listPanel.add(createAttentionItem(AeroIcon.Type.CLAIM, pirClaimsBadge, "PIR & Claims Cases", pirClaimsAttnVal, "Claims Desk", () -> navigationController.navigateTo(NavRoute.PIR_CLAIMS)));
        listPanel.add(createAttentionItem(AeroIcon.Type.CHECKIN, checkinBadge, "Passenger Check-in", checkinAttnVal, "Check-in", () -> navigationController.navigateTo(NavRoute.CHECKIN)));
        listPanel.add(createAttentionItem(AeroIcon.Type.GATE, gateBadge, "Gate & Boarding", gateAttnVal, "Boarding", () -> navigationController.navigateTo(NavRoute.BOARDING)));

        card.add(listPanel, BorderLayout.CENTER);
        return card;
    }

    private JPanel createAttentionItem(AeroIcon.Type iconType, StatusBadge badge, String title, JLabel valueLabel, String buttonText, Runnable onAction) {
        JPanel row = new JPanel(new BorderLayout(12, 0)) {
            private boolean hover;
            {
                addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override public void mouseEntered(java.awt.event.MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(java.awt.event.MouseEvent e) { hover = false; repaint(); }
                });
            }
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (hover) {
                    g2.setColor(AeroColors.SIDEBAR_HOVER);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(8, 8, 8, 6));

        JPanel iconWrap = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(AeroColors.CARD_SUBTLE);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        iconWrap.setOpaque(false);
        iconWrap.setPreferredSize(new Dimension(32, 32));
        iconWrap.setBorder(new EmptyBorder(7, 7, 7, 7));
        iconWrap.add(new AeroIcon(iconType, 18).color(AeroColors.TEXT_SECONDARY), BorderLayout.CENTER);
        row.add(iconWrap, BorderLayout.WEST);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setOpaque(false);

        JPanel titleRow = new JPanel(new BorderLayout(8, 0));
        titleRow.setOpaque(false);
        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(AeroFonts.BODY_BOLD);
        titleLbl.setForeground(AeroColors.TEXT_MAIN);
        titleRow.add(titleLbl, BorderLayout.WEST);
        titleRow.add(badge, BorderLayout.EAST);

        valueLabel.setFont(AeroFonts.CAPTION);
        valueLabel.setForeground(AeroColors.TEXT_SECONDARY);

        center.add(titleRow);
        center.add(Box.createVerticalStrut(3));
        center.add(valueLabel);
        row.add(center, BorderLayout.CENTER);

        ModernButton btn = new ModernButton(buttonText, ModernButton.Style.GHOST);
        btn.setFont(AeroFonts.CAPTION_BOLD);
        btn.setPreferredSize(new Dimension(104, 26));
        btn.addActionListener(e -> onAction.run());
        row.add(btn, BorderLayout.EAST);

        return row;
    }

    private static String roleFocus(Role role) {
        if (role == null) return "Focus: Live flight operations & fleet capacity.";
        return switch (role) {
            case CHECKINAGENT -> "Focus: Passenger verification, confirmed bookings & check-in readiness.";
            case GATEAGENT -> "Focus: Boarding operations, flight status & gate management.";
            case OPERATIONSMANAGER -> "Focus: Flight lifecycle, disruptions & gate dispatch.";
            case FINANCEOFFICER -> "Focus: Completed payments, refunds & financial settlement.";
            case RESERVATIONAGENT -> "Focus: Booking management & passenger profiles.";
            case AUDITOR -> "Focus: Operational log review & audit compliance.";
            case AIRLINEADMIN, SUPERADMIN -> "Focus: Full network operations, staff access & compliance.";
        };
    }

    private JPanel createSnapshotSection() {
        JPanel section = new SurfacePanel(new BorderLayout());
        section.setBorder(new EmptyBorder(18, 20, 16, 20));
        section.setPreferredSize(new Dimension(520, 360));

        // Section Header
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(0, 0, 12, 0));

        JLabel title = new JLabel("Operations Snapshot");
        title.setFont(AeroFonts.TITLE_CARD);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel subtitle = new JLabel("Fleet capacity utilization, booking lifecycle & financial settlements");
        subtitle.setFont(AeroFonts.CAPTION);
        subtitle.setForeground(AeroColors.TEXT_SECONDARY);

        header.add(title);
        header.add(Box.createVerticalStrut(2));
        header.add(subtitle);
        section.add(header, BorderLayout.NORTH);

        // 4-Tile Grid (2x2)
        JPanel tiles = new JPanel(new GridLayout(2, 2, 12, 12));
        tiles.setOpaque(false);

        // Tile 1: Fleet Seat Allocation
        JPanel tile1 = createTileContainer("Fleet Seat Allocation");
        JPanel seatStats = new JPanel(new GridLayout(3, 2, 8, 4));
        seatStats.setOpaque(false);
        addStatRow(seatStats, "Total Fleet Seats", totalSeatsVal);
        addStatRow(seatStats, "Available Seats", availSeatsVal);
        addStatRow(seatStats, "Occupied Seats", occupiedSeatsVal);
        tile1.add(seatStats, BorderLayout.CENTER);

        JPanel progressContainer = new JPanel(new BorderLayout(0, 4));
        progressContainer.setOpaque(false);
        progressContainer.setBorder(new EmptyBorder(6, 0, 0, 0));
        progressContainer.add(occupancyBar, BorderLayout.CENTER);
        tile1.add(progressContainer, BorderLayout.SOUTH);

        // Tile 2: Reservation Lifecycle Distribution
        JPanel tile2 = createTileContainer("Reservation Distribution");
        JPanel resStats = new JPanel(new GridLayout(3, 2, 8, 4));
        resStats.setOpaque(false);
        addStatRow(resStats, "Confirmed Bookings", confirmedResVal);
        addStatRow(resStats, "Cancelled Bookings", cancelledResVal);
        addStatRow(resStats, "Pending Bookings", pendingResVal);
        tile2.add(resStats, BorderLayout.CENTER);

        // Tile 3: Financial Settlement Summary
        JPanel tile3 = createTileContainer("Financial Settlements");
        JPanel payStats = new JPanel(new GridLayout(3, 2, 8, 4));
        payStats.setOpaque(false);
        addStatRow(payStats, "Completed Payments", compAmountVal);
        addStatRow(payStats, "Refunded Settlements", refAmountVal);
        addStatRow(payStats, "Net Realized", netSettlementVal);
        tile3.add(payStats, BorderLayout.CENTER);

        // Tile 4: Airport Operations Status (Real operational module readiness)
        JPanel tile4 = createTileContainer("Station Operations Status");
        JPanel opsStats = new JPanel(new GridLayout(4, 2, 8, 4));
        opsStats.setOpaque(false);
        addStatRow(opsStats, "Checked-in Pax", checkinSnapshotVal);
        addStatRow(opsStats, "Flights Boarding", boardingSnapshotVal);
        addStatRow(opsStats, "Baggage Ops", baggageOpsVal);
        addStatRow(opsStats, "Claims Incidents", pirClaimsVal);
        tile4.add(opsStats, BorderLayout.CENTER);

        tiles.add(tile1);
        tiles.add(tile2);
        tiles.add(tile3);
        tiles.add(tile4);
        section.add(tiles, BorderLayout.CENTER);

        return section;
    }

    private JPanel createAlertsSection() {
        JPanel section = new SurfacePanel(new BorderLayout(12, 0), AeroColors.CARD_SUBTLE, false);
        section.setBorder(new EmptyBorder(14, 16, 14, 16));
        section.setAlignmentX(LEFT_ALIGNMENT);
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72));

        JPanel iconPart = new JPanel(new BorderLayout());
        iconPart.setOpaque(false);
        iconPart.add(new AeroIcon(AeroIcon.Type.ALERT, 18).color(AeroColors.WARNING), BorderLayout.NORTH);

        JPanel textPart = new JPanel();
        textPart.setLayout(new BoxLayout(textPart, BoxLayout.Y_AXIS));
        textPart.setOpaque(false);

        JLabel alertHeader = new JLabel("OPERATIONAL ALERTS");
        alertHeader.setFont(AeroFonts.SECTION_CATEGORY);
        alertHeader.setForeground(AeroColors.TEXT_MUTED);

        operationalAlertsVal.setFont(AeroFonts.BODY);
        operationalAlertsVal.setForeground(AeroColors.TEXT_MAIN);

        textPart.add(alertHeader);
        textPart.add(Box.createVerticalStrut(2));
        textPart.add(operationalAlertsVal);

        section.add(iconPart, BorderLayout.WEST);
        section.add(textPart, BorderLayout.CENTER);
        return section;
    }

    private JPanel createTileContainer(String title) {
        JPanel tile = new SurfacePanel(new BorderLayout(), AeroColors.CARD_SUBTLE, false);
        tile.setBorder(new EmptyBorder(12, 14, 12, 14));

        JLabel t = new JLabel(title);
        t.setFont(AeroFonts.CAPTION_BOLD);
        t.setForeground(AeroColors.TEXT_MAIN);
        t.setBorder(new EmptyBorder(0, 0, 6, 0));
        tile.add(t, BorderLayout.NORTH);

        return tile;
    }

    private void addStatRow(JPanel container, String label, JLabel valueLabel) {
        JLabel l = new JLabel(label);
        l.setFont(AeroFonts.CAPTION);
        l.setForeground(AeroColors.TEXT_SECONDARY);

        valueLabel.setFont(AeroFonts.BODY_BOLD);
        valueLabel.setForeground(AeroColors.TEXT_MAIN);
        valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        container.add(l);
        container.add(valueLabel);
    }

    private JPanel createEmptyState(AeroIcon.Type iconType, String title, String description, String actionLabel, Runnable onAction) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(24, 28, 24, 28));
        p.setPreferredSize(new Dimension(480, 260));

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setOpaque(false);

        JPanel iconBadge = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(AeroColors.CARD_SUBTLE);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        iconBadge.setOpaque(false);
        iconBadge.setAlignmentX(CENTER_ALIGNMENT);
        iconBadge.setPreferredSize(new Dimension(56, 56));
        iconBadge.setMaximumSize(new Dimension(56, 56));
        iconBadge.setBorder(new EmptyBorder(16, 16, 16, 16));
        iconBadge.add(new AeroIcon(iconType, 24).color(AeroColors.TEXT_MUTED), BorderLayout.CENTER);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(AeroFonts.BODY_BOLD);
        titleLabel.setForeground(AeroColors.TEXT_MAIN);
        titleLabel.setAlignmentX(CENTER_ALIGNMENT);

        JLabel descLabel = new JLabel("<html><div style='text-align:center;width:320px'>" + description + "</div></html>");
        descLabel.setFont(AeroFonts.CAPTION);
        descLabel.setForeground(AeroColors.TEXT_SECONDARY);
        descLabel.setAlignmentX(CENTER_ALIGNMENT);

        inner.add(iconBadge);
        inner.add(Box.createVerticalStrut(14));
        inner.add(titleLabel);
        inner.add(Box.createVerticalStrut(6));
        inner.add(descLabel);

        if (actionLabel != null && onAction != null) {
            inner.add(Box.createVerticalStrut(14));
            ModernButton btn = new ModernButton(actionLabel, ModernButton.Style.SECONDARY);
            btn.setFont(AeroFonts.CAPTION_BOLD);
            btn.setAlignmentX(CENTER_ALIGNMENT);
            btn.setPreferredSize(new Dimension(140, 30));
            btn.addActionListener(e -> onAction.run());
            inner.add(btn);
        }

        p.add(inner);
        return p;
    }

    public void refreshData() {
        timeLabel.setText(LocalDateTime.now().format(TIME_FORMAT));
        refreshButton.setEnabled(false);
        refreshButton.setText("Loading...");
        beginLoadingPresentation();

        SwingWorker<DashboardData, Void> worker = new SwingWorker<>() {
            @Override
            protected DashboardData doInBackground() throws Exception {
                long tenantOrgId = airline.management.system.tenant.TenantContext.getOrganizationId() != null ?
                        airline.management.system.tenant.TenantContext.getOrganizationId() : 1L;

                try (Connection c = DBConnection.getConnection()) {
                    // 1. KPI Queries
                    int activeFlights = 0;
                    try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM flights WHERE organization_id = ? AND status IN ('SCHEDULED','ACTIVE')")) {
                        p.setLong(1, tenantOrgId);
                        try (ResultSet r = p.executeQuery()) { if (r.next()) activeFlights = r.getInt(1); }
                    }

                    int confirmedRes = 0;
                    try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM reservations WHERE organization_id = ? AND status='CONFIRMED'")) {
                        p.setLong(1, tenantOrgId);
                        try (ResultSet r = p.executeQuery()) { if (r.next()) confirmedRes = r.getInt(1); }
                    }

                    int totalPassengers = 0;
                    try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM passengers WHERE organization_id = ?")) {
                        p.setLong(1, tenantOrgId);
                        try (ResultSet r = p.executeQuery()) { if (r.next()) totalPassengers = r.getInt(1); }
                    }

                    double totalRevenue = 0.0;
                    try (PreparedStatement p = c.prepareStatement("SELECT COALESCE(SUM(amount), 0) FROM payments WHERE organization_id = ? AND payment_status IN ('SUCCESS','COMPLETED')")) {
                        p.setLong(1, tenantOrgId);
                        try (ResultSet r = p.executeQuery()) { if (r.next()) totalRevenue = r.getDouble(1); }
                    }

                    // 2. Flight Operations Board Query (includes gate allocation if gates schema is available)
                    DefaultTableModel flightsModel;
                    boolean hasGates = tableExists(c, "gates") && tableExists(c, "flight_gates");
                    String flightQuery = hasGates ?
                            "SELECT f.flight_number AS 'Flight', CONCAT(f.source, ' → ', f.destination) AS 'Route', " +
                            "f.departure_time AS 'Departure', COALESCE(g.gate_number, '—') AS 'Gate', " +
                            "CONCAT(f.available_seats, ' / ', f.total_seats) AS 'Capacity', f.status AS 'Status' " +
                            "FROM flights f " +
                            "LEFT JOIN flight_gates fg ON fg.flight_id = f.id AND fg.organization_id = f.organization_id " +
                            "LEFT JOIN gates g ON g.id = fg.gate_id AND g.organization_id = f.organization_id " +
                            "WHERE f.organization_id = ? ORDER BY f.departure_time ASC LIMIT 8" :
                            "SELECT flight_number AS 'Flight', CONCAT(source, ' → ', destination) AS 'Route', " +
                            "departure_time AS 'Departure', '—' AS 'Gate', " +
                            "CONCAT(available_seats, ' / ', total_seats) AS 'Capacity', status AS 'Status' " +
                            "FROM flights WHERE organization_id = ? ORDER BY departure_time ASC LIMIT 8";

                    try (PreparedStatement p = c.prepareStatement(flightQuery)) {
                        p.setLong(1, tenantOrgId);
                        try (ResultSet r = p.executeQuery()) {
                            flightsModel = TableUtils.toTableModel(r);
                        }
                    }

                    // 3. Recent Reservations Table Query
                    DefaultTableModel resModel;
                    try (PreparedStatement p = c.prepareStatement(
                            "SELECT r.pnr AS 'PNR', p.name AS 'Passenger', f.flight_number AS 'Flight', " +
                            "COALESCE(r.seat_number, '—') AS 'Seat', r.status AS 'Status', COALESCE(pmt.payment_status,'PENDING') AS 'Payment' " +
                            "FROM reservations r JOIN passengers p ON p.id=r.passenger_id JOIN flights f ON f.id=r.flight_id " +
                            "LEFT JOIN payments pmt ON pmt.reservation_id=r.id WHERE r.organization_id = ? ORDER BY r.booking_date DESC LIMIT 8")) {
                        p.setLong(1, tenantOrgId);
                        try (ResultSet r = p.executeQuery()) {
                            resModel = TableUtils.toTableModel(r);
                        }
                    }

                    // 4. Operations Snapshot Queries
                    int totalSeats = 0;
                    int availSeats = 0;
                    try (PreparedStatement p = c.prepareStatement("SELECT COALESCE(SUM(total_seats),0), COALESCE(SUM(available_seats),0) FROM flights WHERE organization_id = ? AND status IN ('SCHEDULED','ACTIVE')")) {
                        p.setLong(1, tenantOrgId);
                        try (ResultSet r = p.executeQuery()) {
                            if (r.next()) {
                                totalSeats = r.getInt(1);
                                availSeats = r.getInt(2);
                            }
                        }
                    }

                    int resConfirmed = 0, resCancelled = 0, resPending = 0;
                    try (PreparedStatement p = c.prepareStatement(
                            "SELECT COUNT(CASE WHEN status='CONFIRMED' THEN 1 END), " +
                            "COUNT(CASE WHEN status='CANCELLED' THEN 1 END), " +
                            "COUNT(CASE WHEN status NOT IN ('CONFIRMED','CANCELLED') THEN 1 END) FROM reservations WHERE organization_id = ?")) {
                        p.setLong(1, tenantOrgId);
                        try (ResultSet r = p.executeQuery()) {
                            if (r.next()) {
                                resConfirmed = r.getInt(1);
                                resCancelled = r.getInt(2);
                                resPending = r.getInt(3);
                            }
                        }
                    }

                    int compCount = 0, refCount = 0;
                    double compAmt = 0.0, refAmt = 0.0;
                    try (PreparedStatement p = c.prepareStatement(
                            "SELECT COUNT(CASE WHEN payment_status IN ('SUCCESS','COMPLETED') THEN 1 END), " +
                            "COALESCE(SUM(CASE WHEN payment_status IN ('SUCCESS','COMPLETED') THEN amount ELSE 0 END), 0), " +
                            "COUNT(CASE WHEN payment_status='REFUNDED' THEN 1 END), " +
                            "COALESCE(SUM(CASE WHEN payment_status='REFUNDED' THEN amount ELSE 0 END), 0) FROM payments WHERE organization_id = ?")) {
                        p.setLong(1, tenantOrgId);
                        try (ResultSet r = p.executeQuery()) {
                            if (r.next()) {
                                compCount = r.getInt(1);
                                compAmt = r.getDouble(2);
                                refCount = r.getInt(3);
                                refAmt = r.getDouble(4);
                            }
                        }
                    }

                    int delayed = scalar(c, "SELECT COUNT(*) FROM flights WHERE organization_id = ? AND status='DELAYED'", tenantOrgId);
                    int cancelled = scalar(c, "SELECT COUNT(*) FROM flights WHERE organization_id = ? AND status='CANCELLED'", tenantOrgId);
                    int boarding = scalar(c, "SELECT COUNT(*) FROM flights WHERE organization_id = ? AND status='BOARDING'", tenantOrgId);
                    int checkins = optionalScalar(c, "checkins", "SELECT COUNT(*) FROM checkins WHERE organization_id = ?", tenantOrgId);
                    int boarded = optionalScalar(c, "checkins", "SELECT COUNT(*) FROM checkins WHERE organization_id = ? AND status='BOARDED'", tenantOrgId);
                    int totalGates = optionalScalar(c, "gates", "SELECT COUNT(*) FROM gates WHERE organization_id = ?", tenantOrgId);
                    int occupiedGates = optionalScalar(c, "gates", "SELECT COUNT(*) FROM gates WHERE organization_id = ? AND status='OCCUPIED'", tenantOrgId);
                    String gateUtil = totalGates > 0 ? ((occupiedGates * 100) / totalGates) + "%" : "0%";

                    int totalBaggage = optionalScalar(c, "baggage", "SELECT COUNT(*) FROM baggage WHERE organization_id = ?", tenantOrgId);
                    int delayedBaggage = optionalScalar(c, "baggage", "SELECT COUNT(*) FROM baggage WHERE organization_id = ? AND status='DELAYED'", tenantOrgId);
                    int missingBaggage = optionalScalar(c, "baggage", "SELECT COUNT(*) FROM baggage WHERE organization_id = ? AND status='MISSING'", tenantOrgId);
                    int baggageExceptions = delayedBaggage + missingBaggage;

                    int openPirs = optionalScalar(c, "pir_reports", "SELECT COUNT(*) FROM pir_reports WHERE organization_id = ? AND status IN ('OPEN','UNDER_INVESTIGATION')", tenantOrgId);
                    int claimsReview = optionalScalar(c, "claim_cases", "SELECT COUNT(*) FROM claim_cases WHERE organization_id = ? AND status IN ('SUBMITTED','UNDER_REVIEW')", tenantOrgId);
                    int claimsSettlement = optionalScalar(c, "claim_cases", "SELECT COUNT(*) FROM claim_cases WHERE organization_id = ? AND status='APPROVED'", tenantOrgId);
                    int activeDisruptions = optionalScalar(c, "flight_disruptions", "SELECT COUNT(*) FROM flight_disruptions WHERE organization_id = ? AND status NOT IN ('RESOLVED','CANCELLED')", tenantOrgId);
                    int impactedPax = optionalScalar(c, "flight_disruptions", "SELECT COALESCE(SUM(passenger_impact_count), 0) FROM flight_disruptions WHERE organization_id = ? AND status NOT IN ('RESOLVED','CANCELLED')", tenantOrgId);

                    List<String> alertParts = new ArrayList<>();
                    if (activeDisruptions > 0) alertParts.add("Active IROPS: " + activeDisruptions + " (" + impactedPax + " pax affected)");
                    if (delayed > 0) alertParts.add("Delayed: " + delayed);
                    if (cancelled > 0) alertParts.add("Cancelled: " + cancelled);
                    if (boarding > 0) alertParts.add("Boarding: " + boarding);
                    if (missingBaggage > 0) alertParts.add("Missing Luggage: " + missingBaggage);
                    if (delayedBaggage > 0) alertParts.add("Delayed Luggage: " + delayedBaggage);
                    if (openPirs > 0) alertParts.add("Open PIRs: " + openPirs);
                    if (claimsReview > 0) alertParts.add("Claims In Review: " + claimsReview);
                    if (claimsSettlement > 0) alertParts.add("Claims Approved: " + claimsSettlement);

                    String alerts = alertParts.isEmpty() ?
                            "All systems nominal across " + StationContext.getCurrentStation().getCode() + " hub • Gate Utilization: " + gateUtil + " • Active Check-ins: " + checkins + " • Boarded: " + boarded + " • Total Baggage: " + totalBaggage :
                            String.join("   •   ", alertParts) + "   •   Gate Utilization: " + gateUtil;

                    return new DashboardData(
                            activeFlights, confirmedRes, totalPassengers, totalRevenue,
                            flightsModel, resModel,
                            totalSeats, availSeats,
                            resConfirmed, resCancelled, resPending,
                            compCount, compAmt, refCount, refAmt, alerts,
                            totalBaggage, baggageExceptions,
                            openPirs, missingBaggage, delayedBaggage, claimsReview, claimsSettlement,
                            checkins, activeDisruptions, impactedPax, boarding
                    );
                }
            }

            @Override
            protected void done() {
                refreshButton.setEnabled(true);
                refreshButton.setText("Refresh");
                try {
                    DashboardData data = get();

                    // Update KPI Strip from live query results, including authentic zeros.
                    flightsMetric.applyCount(data.activeFlights);
                    passengersMetric.applyCount(data.totalPassengers);
                    checkinsMetric.applyCount(data.checkins);
                    disruptionsMetric.applyCount(data.activeDisruptions);
                    disruptionsMetric.setValueColor(data.activeDisruptions > 0 ? AeroColors.DANGER : AeroColors.TEXT_MAIN);

                    // Update Needs Attention Work Queue
                    if (data.activeDisruptions > 0) {
                        disruptionsBadge.setStatus("CRITICAL");
                        disruptionsVal.setText(data.activeDisruptions + " active (" + data.impactedPax + " pax affected)");
                        disruptionsVal.setForeground(AeroColors.DANGER);
                    } else {
                        disruptionsBadge.setStatus("CLEAR");
                        disruptionsVal.setText("No active disruptions");
                        disruptionsVal.setForeground(AeroColors.TEXT_SECONDARY);
                    }

                    if (data.baggageExceptions > 0) {
                        baggageBadge.setStatus("ATTENTION");
                        baggageVal.setText(data.baggageExceptions + " exceptions (" + data.delayedBaggage + " delayed, " + data.missingBaggage + " missing)");
                        baggageVal.setForeground(AeroColors.DANGER);
                    } else {
                        baggageBadge.setStatus("CLEAR");
                        baggageVal.setText("No active cases");
                        baggageVal.setForeground(AeroColors.TEXT_SECONDARY);
                    }

                    if (data.openPirs > 0 || data.claimsReview > 0 || data.claimsSettlement > 0) {
                        pirClaimsBadge.setStatus("REVIEW");
                        pirClaimsAttnVal.setText(data.openPirs + " open PIRs • " + data.claimsReview + " in review");
                        pirClaimsAttnVal.setForeground(AeroColors.WARNING);
                    } else {
                        pirClaimsBadge.setStatus("CLEAR");
                        pirClaimsAttnVal.setText("No active cases");
                        pirClaimsAttnVal.setForeground(AeroColors.TEXT_SECONDARY);
                    }

                    if (data.checkins > 0 || data.confirmedReservations > 0) {
                        checkinBadge.setStatus("INFO");
                        checkinAttnVal.setText(data.checkins + " checked in of " + data.confirmedReservations + " confirmed bookings");
                        checkinAttnVal.setForeground(AeroColors.TEXT_SECONDARY);
                    } else {
                        checkinBadge.setStatus("CLEAR");
                        checkinAttnVal.setText("No check-in activity");
                        checkinAttnVal.setForeground(AeroColors.TEXT_SECONDARY);
                    }
                    checkinSnapshotVal.setText(String.format("%,d", data.checkins));
                    boardingSnapshotVal.setText(String.format("%,d", data.boarding));

                    if (data.boarding > 0) {
                        gateBadge.setStatus("BOARDING");
                        gateAttnVal.setText(data.boarding + " flights currently boarding");
                        gateAttnVal.setForeground(AeroColors.WARNING);
                    } else {
                        gateBadge.setStatus("CLEAR");
                        gateAttnVal.setText("No boarding in progress");
                        gateAttnVal.setForeground(AeroColors.TEXT_SECONDARY);
                    }

                    // Update Flights Board Table
                    flightsTable.setModel(data.flightsModel);
                    if (data.flightsModel.getRowCount() == 0) {
                        flightsCardLayout.show(flightsContainer, "empty");
                    } else {
                        flightsCardLayout.show(flightsContainer, "table");
                    }

                    // Update Reservations Table
                    reservationsTable.setModel(data.reservationsModel);
                    if (data.reservationsModel.getRowCount() == 0) {
                        reservationsCardLayout.show(reservationsContainer, "empty");
                    } else {
                        reservationsCardLayout.show(reservationsContainer, "table");
                    }

                    // Update Snapshot: Seats
                    totalSeatsVal.setText(String.format("%,d", data.totalSeats));
                    availSeatsVal.setText(String.format("%,d", data.availSeats));
                    int occupied = Math.max(0, data.totalSeats - data.availSeats);
                    occupiedSeatsVal.setText(String.format("%,d", occupied));
                    int occupancyPct = data.totalSeats > 0 ? (int) Math.round(((double) occupied / data.totalSeats) * 100) : 0;
                    occupancyBar.setValue(occupancyPct);
                    occupancyBar.setString(occupancyPct + "% Occupancy");

                    // Update Snapshot: Reservations
                    confirmedResVal.setText(String.format("%,d", data.resConfirmed));
                    cancelledResVal.setText(String.format("%,d", data.resCancelled));
                    pendingResVal.setText(String.format("%,d", data.resPending));

                    // Update Snapshot: Settlements
                    compPaymentsVal.setText(String.valueOf(data.compPaymentsCount));
                    compAmountVal.setText("₹" + String.format("%,.2f", data.compPaymentsAmount));
                    refPaymentsVal.setText(String.valueOf(data.refPaymentsCount));
                    refAmountVal.setText("₹" + String.format("%,.2f", data.refPaymentsAmount));
                    double net = Math.max(0.0, data.compPaymentsAmount - data.refPaymentsAmount);
                    netSettlementVal.setText("₹" + String.format("%,.2f", net));

                    // Update Snapshot: Baggage Operations
                    if (data.totalBaggage == 0) {
                        baggageOpsVal.setText("0 bags (Idle)");
                        baggageOpsVal.setForeground(AeroColors.TEXT_MUTED);
                    } else if (data.baggageExceptions > 0) {
                        baggageOpsVal.setText(data.totalBaggage + " (" + data.baggageExceptions + " alerts)");
                        baggageOpsVal.setForeground(AeroColors.DANGER);
                    } else {
                        baggageOpsVal.setText(data.totalBaggage + " bags (Normal)");
                        baggageOpsVal.setForeground(AeroColors.SUCCESS);
                    }

                    // Update Snapshot: PIR & Claims
                    if (data.openPirs == 0 && data.claimsReview == 0 && data.claimsSettlement == 0) {
                        pirClaimsVal.setText("Normal (0 active)");
                        pirClaimsVal.setForeground(AeroColors.SUCCESS);
                    } else {
                        pirClaimsVal.setText(data.openPirs + " open (" + data.claimsReview + " review, " + data.claimsSettlement + " approved)");
                        pirClaimsVal.setForeground(AeroColors.WARNING);
                    }

                    operationalAlertsVal.setText(data.operationalAlerts);

                } catch (Exception e) {
                    notificationBanner.showMessage("Unable to load dashboard data. Please verify the database connection.", NotificationBanner.Type.ERROR);
                    applyUnavailablePresentation();
                }
            }
        };

        worker.execute();
    }

    private void beginLoadingPresentation() {
        flightsMetric.setLoading();
        passengersMetric.setLoading();
        checkinsMetric.setLoading();
        disruptionsMetric.setLoading();

        disruptionsVal.setText("Checking...");
        baggageVal.setText("Checking...");
        pirClaimsAttnVal.setText("Checking...");
        checkinAttnVal.setText("Checking...");
        gateAttnVal.setText("Checking...");
        disruptionsVal.setForeground(AeroColors.TEXT_MUTED);
        baggageVal.setForeground(AeroColors.TEXT_MUTED);
        pirClaimsAttnVal.setForeground(AeroColors.TEXT_MUTED);
        checkinAttnVal.setForeground(AeroColors.TEXT_MUTED);
        gateAttnVal.setForeground(AeroColors.TEXT_MUTED);

        flightsCardLayout.show(flightsContainer, "loading");
        reservationsCardLayout.show(reservationsContainer, "loading");
        operationalAlertsVal.setText("Loading operational alerts...");
    }

    private void applyUnavailablePresentation() {
        flightsMetric.setUnavailable();
        passengersMetric.setUnavailable();
        checkinsMetric.setUnavailable();
        disruptionsMetric.setUnavailable();

        disruptionsBadge.setStatus("INFO");
        baggageBadge.setStatus("INFO");
        pirClaimsBadge.setStatus("INFO");
        checkinBadge.setStatus("INFO");
        gateBadge.setStatus("INFO");
        disruptionsVal.setText("Status unavailable");
        baggageVal.setText("Status unavailable");
        pirClaimsAttnVal.setText("Status unavailable");
        checkinAttnVal.setText("Status unavailable");
        gateAttnVal.setText("Status unavailable");
        disruptionsVal.setForeground(AeroColors.TEXT_MUTED);
        baggageVal.setForeground(AeroColors.TEXT_MUTED);
        pirClaimsAttnVal.setForeground(AeroColors.TEXT_MUTED);
        checkinAttnVal.setForeground(AeroColors.TEXT_MUTED);
        gateAttnVal.setForeground(AeroColors.TEXT_MUTED);

        flightsCardLayout.show(flightsContainer, "empty");
        reservationsCardLayout.show(reservationsContainer, "empty");
        operationalAlertsVal.setText("Operational alerts unavailable. Refresh to retry.");
    }

    private static int optionalScalar(Connection connection, String tableName, String sql, long orgId) {
        try {
            if (!tableExists(connection, tableName)
                    && !tableExists(connection, tableName.toUpperCase())
                    && !tableExists(connection, tableName.toLowerCase())) {
                return 0;
            }
            return scalar(connection, sql, orgId);
        } catch (SQLException e) {
            return 0;
        }
    }

    private record DashboardData(
            int activeFlights,
            int confirmedReservations,
            int totalPassengers,
            double totalRevenue,
            DefaultTableModel flightsModel,
            DefaultTableModel reservationsModel,
            int totalSeats,
            int availSeats,
            int resConfirmed,
            int resCancelled,
            int resPending,
            int compPaymentsCount,
            double compPaymentsAmount,
            int refPaymentsCount,
            double refPaymentsAmount,
            String operationalAlerts,
            int totalBaggage,
            int baggageExceptions,
            int openPirs,
            int missingBaggage,
            int delayedBaggage,
            int claimsReview,
            int claimsSettlement,
            int checkins,
            int activeDisruptions,
            int impactedPax,
            int boarding
    ) {}

    private static int scalar(Connection connection, String sql, long orgId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, orgId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private static boolean tableExists(Connection conn, String tableName) {
        try (ResultSet rs = conn.getMetaData().getTables(conn.getCatalog(), null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    private static final class SurfacePanel extends JPanel {
        private final Color surface;
        private final boolean outlined;
        SurfacePanel(java.awt.LayoutManager layout) { this(layout, Color.WHITE, true); }
        SurfacePanel(java.awt.LayoutManager layout, Color surface) { this(layout, surface, true); }
        SurfacePanel(java.awt.LayoutManager layout, Color surface, boolean outlined) {
            super(layout);
            this.surface = surface;
            this.outlined = outlined;
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(15, 23, 42, 6));
            g.fillRoundRect(1, 2, getWidth() - 2, Math.max(0, getHeight() - 2), AeroConstants.CORNER_RADIUS, AeroConstants.CORNER_RADIUS);
            g.setColor(surface);
            g.fillRoundRect(0, 0, getWidth() - 2, Math.max(0, getHeight() - 2), AeroConstants.CORNER_RADIUS, AeroConstants.CORNER_RADIUS);
            if (outlined) {
                g.setColor(AeroColors.BORDER);
                g.drawRoundRect(0, 0, Math.max(0, getWidth() - 2), Math.max(0, getHeight() - 2), AeroConstants.CORNER_RADIUS, AeroConstants.CORNER_RADIUS);
            }
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class MetricItem extends JPanel {
        private final JLabel valueLabel;
        private final JLabel subLabel;
        private final String populatedText;
        private final String emptyText;

        MetricItem(String labelText, String subText, String emptySubText, AeroIcon.Type iconType, Color iconColor, Color iconBg, boolean showRightBorder) {
            setLayout(new BorderLayout());
            setOpaque(false);
            this.populatedText = subText;
            this.emptyText = emptySubText;
            setBorder(BorderFactory.createCompoundBorder(
                    showRightBorder ? BorderFactory.createMatteBorder(0, 0, 0, 1, AeroColors.BORDER_SUBTLE) : BorderFactory.createEmptyBorder(),
                    new EmptyBorder(12, 18, 12, 18)
            ));

            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.setAlignmentX(LEFT_ALIGNMENT);

            JLabel label = new JLabel(labelText);
            label.setFont(AeroFonts.SECTION_CATEGORY);
            label.setForeground(AeroColors.TEXT_MUTED);
            top.add(label, BorderLayout.WEST);

            JPanel iconBadge = new JPanel(new BorderLayout()) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(iconBg);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            iconBadge.setOpaque(false);
            iconBadge.setPreferredSize(new Dimension(30, 30));
            iconBadge.setBorder(new EmptyBorder(7, 7, 7, 7));
            iconBadge.add(new AeroIcon(iconType, 16).color(iconColor));
            top.add(iconBadge, BorderLayout.EAST);

            JPanel center = new JPanel();
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            center.setOpaque(false);

            valueLabel = new JLabel("...");
            valueLabel.setFont(AeroFonts.KPI_VALUE);
            valueLabel.setForeground(AeroColors.TEXT_MUTED);
            valueLabel.setAlignmentX(LEFT_ALIGNMENT);

            subLabel = new JLabel("Loading live counts");
            subLabel.setFont(AeroFonts.MUTED);
            subLabel.setForeground(AeroColors.TEXT_MUTED);
            subLabel.setAlignmentX(LEFT_ALIGNMENT);

            top.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            center.add(top);
            center.add(Box.createVerticalStrut(10));
            center.add(valueLabel);
            center.add(Box.createVerticalStrut(4));
            center.add(subLabel);

            add(center, BorderLayout.CENTER);
        }

        void applyCount(int count) {
            valueLabel.setText(String.valueOf(count));
            valueLabel.setForeground(AeroColors.TEXT_MAIN);
            subLabel.setText(count == 0 ? emptyText : populatedText);
        }

        void setLoading() {
            valueLabel.setText("...");
            valueLabel.setForeground(AeroColors.TEXT_MUTED);
            subLabel.setText("Loading live counts");
        }

        void setUnavailable() {
            valueLabel.setText("—");
            valueLabel.setForeground(AeroColors.TEXT_MUTED);
            subLabel.setText("Status unavailable");
        }

        void setValueColor(Color color) { valueLabel.setForeground(color); }
    }

    private static class StyledProgressBar extends JProgressBar {
        public StyledProgressBar() {
            super(0, 100);
            setStringPainted(true);
            setFont(AeroFonts.CAPTION_BOLD);
            setForeground(AeroColors.PRIMARY);
            setBackground(new Color(0xE2, 0xE8, 0xF0));
            setBorder(BorderFactory.createEmptyBorder());
            setPreferredSize(new Dimension(140, 18));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            g2.setColor(getBackground());
            g2.fillRoundRect(0, 0, w, h, 6, 6);

            int fillW = (int) (w * (getPercentComplete()));
            if (fillW > 0) {
                g2.setColor(getForeground());
                g2.fillRoundRect(0, 0, fillW, h, 6, 6);
            }

            if (isStringPainted()) {
                g2.setColor(AeroColors.TEXT_MAIN);
                g2.setFont(getFont());
                var fm = g2.getFontMetrics();
                String str = getString();
                int x = (w - fm.stringWidth(str)) / 2;
                int y = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(str, x, y);
            }

            g2.dispose();
        }
    }
}
