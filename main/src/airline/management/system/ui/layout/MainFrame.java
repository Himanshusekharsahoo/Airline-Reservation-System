package airline.management.system.ui.layout;

import airline.management.system.Login;
import airline.management.system.UserSession;
import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.ui.navigation.NavRoute;
import airline.management.system.ui.navigation.NavigationAccessController;
import airline.management.system.ui.navigation.NavigationController;
import airline.management.system.ui.panels.BoardingPanel;
import airline.management.system.ui.panels.BookingPanel;
import airline.management.system.ui.panels.CancellationsPanel;
import airline.management.system.ui.panels.CheckinPanel;
import airline.management.system.ui.panels.DashboardPanel;
import airline.management.system.ui.panels.FlightsPanel;
import airline.management.system.ui.panels.PassengersPanel;
import airline.management.system.ui.panels.PaymentsPanel;
import airline.management.system.ui.panels.IROPSDeskPanel;
import airline.management.system.ui.panels.PIRClaimsPanel;
import airline.management.system.ui.panels.ReportsPanel;
import airline.management.system.ui.panels.SettingsPanel;
import airline.management.system.ui.panels.BaggageOperationsPanel;
import airline.management.system.ui.panels.StaffManagementPanel;
import airline.management.system.ui.panels.TripsPanel;
import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public class MainFrame extends JFrame {
    private final NavigationController navigationController;
    private final CardLayout cardLayout;
    private final JPanel cardsPanel;

    private final DashboardPanel dashboardPanel;
    private final FlightsPanel flightsPanel;
    private final PassengersPanel passengersPanel;
    private final BookingPanel bookingPanel;
    private final CheckinPanel checkinPanel;
    private final BoardingPanel boardingPanel;
    private final TripsPanel tripsPanel;
    private final PaymentsPanel paymentsPanel;
    private final CancellationsPanel cancellationsPanel;
    private final SettingsPanel settingsPanel;
    private final StaffManagementPanel staffManagementPanel;

    public MainFrame() {
        super(AeroConstants.APP_TITLE);

        if (!UserSession.isAuthenticated()) {
            dispose();
            SwingUtilities.invokeLater(Login::new);
            navigationController = null;
            cardLayout = null;
            cardsPanel = null;
            dashboardPanel = null;
            flightsPanel = null;
            passengersPanel = null;
            bookingPanel = null;
            checkinPanel = null;
            boardingPanel = null;
            tripsPanel = null;
            paymentsPanel = null;
            cancellationsPanel = null;
            settingsPanel = null;
            staffManagementPanel = null;
            return;
        }

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                AuditService.shutdown();
                airline.management.system.DBConnection.shutdown();
            }
        });
        setMinimumSize(AeroConstants.MIN_WINDOW_SIZE);
        setSize(AeroConstants.MIN_WINDOW_SIZE);
        setLocationRelativeTo(null);
        getContentPane().setBackground(AeroColors.BACKGROUND);

        cardLayout = new CardLayout();
        cardsPanel = new JPanel(cardLayout);
        cardsPanel.setBackground(AeroColors.BACKGROUND);

        navigationController = new NavigationController(cardsPanel, cardLayout);

        // Initialize child panels
        dashboardPanel = new DashboardPanel(navigationController);
        flightsPanel = new FlightsPanel(navigationController);
        passengersPanel = new PassengersPanel(navigationController);
        bookingPanel = new BookingPanel(navigationController);
        checkinPanel = new CheckinPanel(navigationController);
        BaggageOperationsPanel baggagePanel = new BaggageOperationsPanel(navigationController);
        PIRClaimsPanel pirClaimsPanel = new PIRClaimsPanel(navigationController);
        IROPSDeskPanel iropsDeskPanel = new IROPSDeskPanel(navigationController);
        boardingPanel = new BoardingPanel(navigationController);
        tripsPanel = new TripsPanel(navigationController);
        paymentsPanel = new PaymentsPanel(navigationController);
        cancellationsPanel = new CancellationsPanel(navigationController);
        ReportsPanel reportsPanel = new ReportsPanel(navigationController);
        settingsPanel = new SettingsPanel(navigationController);
        staffManagementPanel = new StaffManagementPanel(navigationController);

        cardsPanel.add(dashboardPanel, NavRoute.DASHBOARD.getKey());
        cardsPanel.add(flightsPanel, NavRoute.FLIGHTS.getKey());
        cardsPanel.add(passengersPanel, NavRoute.PASSENGERS.getKey());
        cardsPanel.add(bookingPanel, NavRoute.BOOKING.getKey());
        cardsPanel.add(checkinPanel, NavRoute.CHECKIN.getKey());
        cardsPanel.add(baggagePanel, NavRoute.BAGGAGE.getKey());
        cardsPanel.add(pirClaimsPanel, NavRoute.PIR_CLAIMS.getKey());
        cardsPanel.add(iropsDeskPanel, NavRoute.IROPS.getKey());
        cardsPanel.add(boardingPanel, NavRoute.BOARDING.getKey());
        cardsPanel.add(tripsPanel, NavRoute.TRIPS.getKey());
        cardsPanel.add(paymentsPanel, NavRoute.PAYMENTS.getKey());
        cardsPanel.add(cancellationsPanel, NavRoute.CANCELLATIONS.getKey());
        cardsPanel.add(reportsPanel, NavRoute.REPORTS.getKey());
        cardsPanel.add(settingsPanel, NavRoute.SETTINGS.getKey());
        cardsPanel.add(staffManagementPanel, NavRoute.STAFF.getKey());

        // Sidebar & TopBar
        SidebarPanel sidebar = new SidebarPanel(navigationController, this::handleLogout);
        TopBarPanel topBar = new TopBarPanel(navigationController);

        JPanel rightContainer = new JPanel(new BorderLayout());
        rightContainer.setBackground(AeroColors.BACKGROUND);
        rightContainer.add(topBar, BorderLayout.NORTH);
        rightContainer.add(cardsPanel, BorderLayout.CENTER);

        setLayout(new BorderLayout());
        add(sidebar, BorderLayout.WEST);
        add(rightContainer, BorderLayout.CENTER);

        NavRoute defaultRoute = NavigationAccessController.getDefaultRoute(UserSession.getRole());
        navigationController.navigateTo(defaultRoute);
        setVisible(true);
    }

    private void handleLogout() {
        int choice = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to end your session and log out?",
            "Confirm Logout",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        );
        if (choice == JOptionPane.YES_OPTION) {
            AuditService.record(AuditAction.LOGOUT, "USER", String.valueOf(UserSession.userId()),
                    "Operator [" + UserSession.username() + "] logged out");
            UserSession.logout();
            dispose();
            SwingUtilities.invokeLater(Login::new);
        }
    }

    public NavigationController getNavigationController() {
        return navigationController;
    }
}
