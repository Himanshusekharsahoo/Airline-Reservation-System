package airline.management.system;

import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.database.SchemaPreflightGuard;
import airline.management.system.security.InitialAdminProvisioningService;
import airline.management.system.ui.components.FirstAdminSetupDialog;
import airline.management.system.ui.components.ModernButton;
import airline.management.system.ui.components.ModernPasswordField;
import airline.management.system.ui.components.ModernTextField;
import airline.management.system.ui.components.NotificationBanner;
import airline.management.system.ui.layout.MainFrame;
import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.GeneralPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;

/** AeroDesk Modern Enterprise Login Screen */
public class Login extends JFrame {
    private final ModernTextField usernameField;
    private final ModernPasswordField passwordField;
    private final ModernButton signInButton;
    private final ModernButton setupAdminButton;
    private final NotificationBanner notificationBanner;
    private boolean authenticating = false;

    public Login() {
        super("AeroDesk — Airline Operations");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                AuditService.shutdown();
                DBConnection.shutdown();
            }
        });
        setMinimumSize(new Dimension(940, 580));
        setSize(1040, 640);
        setLocationRelativeTo(null);

        JPanel rootPanel = new JPanel(new GridLayout(1, 2));

        // LEFT: Brand Panel
        BrandPanel brandPanel = new BrandPanel();
        rootPanel.add(brandPanel);

        // RIGHT: Login Form Panel
        JPanel rightContainer = new JPanel(new GridBagLayout());
        rightContainer.setBackground(AeroColors.BACKGROUND);

        JPanel formCard = new JPanel();
        formCard.setLayout(new BoxLayout(formCard, BoxLayout.Y_AXIS));
        formCard.setBackground(Color.WHITE);
        formCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(36, 36, 36, 36)
        ));
        formCard.setPreferredSize(new Dimension(410, 520));
        formCard.setMaximumSize(new Dimension(430, 540));

        // Header
        JLabel welcomeTitle = new JLabel("Welcome back");
        welcomeTitle.setFont(AeroFonts.TITLE_PAGE);
        welcomeTitle.setForeground(AeroColors.TEXT_MAIN);
        welcomeTitle.setAlignmentX(LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("Sign in to continue to AeroDesk");
        subtitle.setFont(AeroFonts.BODY);
        subtitle.setForeground(AeroColors.TEXT_SECONDARY);
        subtitle.setAlignmentX(LEFT_ALIGNMENT);

        formCard.add(welcomeTitle);
        formCard.add(Box.createVerticalStrut(4));
        formCard.add(subtitle);
        formCard.add(Box.createVerticalStrut(14));

        // Notification Banner
        notificationBanner = new NotificationBanner();
        notificationBanner.setAlignmentX(LEFT_ALIGNMENT);
        formCard.add(notificationBanner);
        formCard.add(Box.createVerticalStrut(12));

        // Username Field
        JLabel userLabel = new JLabel("Username");
        userLabel.setFont(AeroFonts.BODY_BOLD);
        userLabel.setForeground(AeroColors.TEXT_MAIN);
        userLabel.setAlignmentX(LEFT_ALIGNMENT);

        usernameField = new ModernTextField(18);
        usernameField.setPlaceholder("Enter your username");
        usernameField.setAlignmentX(LEFT_ALIGNMENT);
        usernameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, AeroConstants.INPUT_HEIGHT));

        formCard.add(userLabel);
        formCard.add(Box.createVerticalStrut(6));
        formCard.add(usernameField);
        formCard.add(Box.createVerticalStrut(16));

        // Password Field
        JLabel passLabel = new JLabel("Password");
        passLabel.setFont(AeroFonts.BODY_BOLD);
        passLabel.setForeground(AeroColors.TEXT_MAIN);
        passLabel.setAlignmentX(LEFT_ALIGNMENT);

        passwordField = new ModernPasswordField(18);
        passwordField.setPlaceholder("Enter your password");
        passwordField.setAlignmentX(LEFT_ALIGNMENT);
        passwordField.setMaximumSize(new Dimension(Integer.MAX_VALUE, AeroConstants.INPUT_HEIGHT));

        formCard.add(passLabel);
        formCard.add(Box.createVerticalStrut(6));
        formCard.add(passwordField);
        formCard.add(Box.createVerticalStrut(24));

        // Sign In Button
        signInButton = new ModernButton("Sign In", ModernButton.Style.PRIMARY);
        signInButton.setAlignmentX(LEFT_ALIGNMENT);
        signInButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        signInButton.setPreferredSize(new Dimension(Integer.MAX_VALUE, 40));

        formCard.add(signInButton);
        formCard.add(Box.createVerticalStrut(10));

        // Setup Initial Admin Button (initially hidden, shown if unprovisioned)
        setupAdminButton = new ModernButton("Initialize Administrator", ModernButton.Style.SECONDARY);
        setupAdminButton.setAlignmentX(LEFT_ALIGNMENT);
        setupAdminButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        setupAdminButton.setPreferredSize(new Dimension(Integer.MAX_VALUE, 36));
        setupAdminButton.setVisible(false);
        setupAdminButton.addActionListener(e -> openFirstAdminSetup());

        formCard.add(setupAdminButton);
        formCard.add(Box.createVerticalStrut(16));

        // Footer note
        JLabel securityNote = new JLabel("Secure Airline Operations • Role-based Access", SwingConstants.CENTER);
        securityNote.setFont(AeroFonts.MUTED);
        securityNote.setForeground(AeroColors.TEXT_MUTED);
        securityNote.setAlignmentX(LEFT_ALIGNMENT);
        formCard.add(securityNote);

        rightContainer.add(formCard);
        rootPanel.add(rightContainer);

        setContentPane(rootPanel);

        // Event listeners & keyboard navigation
        signInButton.addActionListener(e -> authenticate());
        passwordField.addActionListener(e -> authenticate());
        usernameField.addActionListener(e -> passwordField.requestFocusInWindow());
        getRootPane().setDefaultButton(signInButton);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                usernameField.requestFocusInWindow();
                checkInitialAdminStatus();
            }
        });

        setVisible(true);
    }

    private void checkInitialAdminStatus() {
        SwingWorker<Boolean, Void> worker = new SwingWorker<>() {
            @Override
            protected Boolean doInBackground() {
                try {
                    return InitialAdminProvisioningService.hasActiveAdministrator();
                } catch (Exception e) {
                    return true;
                }
            }

            @Override
            protected void done() {
                try {
                    boolean hasAdmin = get();
                    if (!hasAdmin) {
                        setupAdminButton.setVisible(true);
                        notificationBanner.showMessage("No administrator account found. Initialize administrator to continue.", NotificationBanner.Type.INFO);
                    } else {
                        setupAdminButton.setVisible(false);
                    }
                } catch (Exception ignored) {}
            }
        };
        worker.execute();
    }

    private void openFirstAdminSetup() {
        FirstAdminSetupDialog dialog = new FirstAdminSetupDialog(this, createdUsername -> {
            usernameField.setText(createdUsername);
            passwordField.setText("");
            passwordField.requestFocusInWindow();
            notificationBanner.showMessage("Administrator account created successfully. Please sign in.", NotificationBanner.Type.SUCCESS);
            setupAdminButton.setVisible(false);
        });
        dialog.setVisible(true);
    }

    private void setLoading(boolean loading) {
        this.authenticating = loading;
        signInButton.setEnabled(!loading);
        signInButton.setText(loading ? "Signing in..." : "Sign In");
        setupAdminButton.setEnabled(!loading);
        usernameField.setEnabled(!loading);
        passwordField.setEnabled(!loading);
    }

    private void authenticate() {
        if (authenticating) return;

        String name = usernameField.getText().trim();
        char[] secret = passwordField.getPassword();

        if (name.isEmpty()) {
            notificationBanner.showMessage("Username is required.", NotificationBanner.Type.WARNING);
            usernameField.requestFocusInWindow();
            Arrays.fill(secret, '\0');
            return;
        }

        if (secret.length == 0) {
            notificationBanner.showMessage("Password is required.", NotificationBanner.Type.WARNING);
            passwordField.requestFocusInWindow();
            Arrays.fill(secret, '\0');
            return;
        }

        setLoading(true);

        SwingWorker<AuthResult, Void> worker = new SwingWorker<>() {
            @Override
            protected AuthResult doInBackground() {
                if (!SchemaPreflightGuard.isSchemaVerified()) {
                    SchemaPreflightGuard.PreflightResult preflight = SchemaPreflightGuard.checkReadiness();
                    if (!preflight.isReady()) {
                        return new AuthResult(false, null, null, null, preflight.message(), preflight);
                    }
                }
                try (Connection connection = DBConnection.getConnection();
                     PreparedStatement statement = connection.prepareStatement(
                             "SELECT id, username, password_hash, role FROM app_users WHERE username=? AND active=TRUE")) {
                    statement.setString(1, name);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next() && PasswordUtils.matches(secret, result.getString("password_hash"))) {
                            return new AuthResult(true, result.getLong("id"), result.getString("username"), result.getString("role"), null, null);
                        } else {
                            return new AuthResult(false, null, null, null, "Invalid username or password.", null);
                        }
                    }
                } catch (SQLException exception) {
                    return new AuthResult(false, null, null, null,
                            "Unable to connect to the database. Please verify the database service and configuration.", null);
                } finally {
                    Arrays.fill(secret, '\0');
                }
            }

            @Override
            protected void done() {
                setLoading(false);
                try {
                    AuthResult res = get();
                    if (res.success) {
                        UserSession.login(res.userId, res.username, res.role);
                        AuditService.record(AuditAction.LOGIN, "USER", String.valueOf(res.userId),
                                "Operator [" + res.username + "] signed in with role [" + res.role + "]");
                        dispose();
                        SwingUtilities.invokeLater(MainFrame::new);
                    } else {
                        if (res.preflight != null && !res.preflight.isReady()) {
                            SchemaPreflightGuard.showDiagnosticDialog(Login.this, res.preflight);
                        }
                        notificationBanner.showMessage(res.errorMessage, NotificationBanner.Type.ERROR);
                        passwordField.setText("");
                        passwordField.requestFocusInWindow();
                    }
                } catch (Exception e) {
                    notificationBanner.showMessage("Unable to process login. Please try again.", NotificationBanner.Type.ERROR);
                }
            }
        };

        worker.execute();
    }

    private record AuthResult(boolean success, Long userId, String username, String role, String errorMessage, SchemaPreflightGuard.PreflightResult preflight) {}

    /** Left Brand Panel with aviation styling */
    private static class BrandPanel extends JPanel {
        public BrandPanel() {
            setLayout(new BorderLayout());
            setBackground(AeroColors.SIDEBAR);
            setBorder(new EmptyBorder(40, 44, 40, 44));

            JPanel header = new JPanel();
            header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
            header.setOpaque(false);

            JLabel appName = new JLabel(AeroConstants.BRAND_NAME);
            appName.setFont(new Font(AeroFonts.TITLE_PAGE.getFamily(), Font.BOLD, 28));
            appName.setForeground(Color.WHITE);

            JLabel appTagline = new JLabel("AIRLINE OPERATIONS DASHBOARD");
            appTagline.setFont(AeroFonts.CAPTION_BOLD);
            appTagline.setForeground(AeroColors.ACCENT);

            JLabel appDesc = new JLabel("<html>Manage flights, passengers, and reservations<br>from one unified operations platform.</html>");
            appDesc.setFont(AeroFonts.BODY);
            appDesc.setForeground(AeroColors.SIDEBAR_MUTED);

            header.add(appName);
            header.add(Box.createVerticalStrut(4));
            header.add(appTagline);
            header.add(Box.createVerticalStrut(14));
            header.add(appDesc);
            header.add(Box.createVerticalStrut(28));

            JPanel features = new JPanel();
            features.setLayout(new BoxLayout(features, BoxLayout.Y_AXIS));
            features.setOpaque(false);

            addFeatureItem(features, "Scheduled Flight Tracking & Route Management");
            addFeatureItem(features, "Passenger Profiles & Historical Travel Records");
            addFeatureItem(features, "Real-time Seat Map & Safe Locking Allocation");
            addFeatureItem(features, "Atomic PNR Generation & Payment Processing");

            header.add(features);
            add(header, BorderLayout.NORTH);

            AviationGraphic graphic = new AviationGraphic();
            add(graphic, BorderLayout.CENTER);

            JLabel footer = new JLabel("AeroDesk Enterprise • Flight Operations System");
            footer.setFont(AeroFonts.MUTED);
            footer.setForeground(new Color(0x64, 0x74, 0x8B));
            add(footer, BorderLayout.SOUTH);
        }

        private void addFeatureItem(JPanel container, String text) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
            row.setOpaque(false);

            JLabel dot = new JLabel("●");
            dot.setFont(AeroFonts.CAPTION_BOLD);
            dot.setForeground(AeroColors.ACCENT);

            JLabel label = new JLabel(text);
            label.setFont(AeroFonts.CAPTION);
            label.setForeground(AeroColors.SIDEBAR_TEXT);

            row.add(dot);
            row.add(label);
            container.add(row);
        }
    }

    /** Subtle vector aviation graphic */
    private static class AviationGraphic extends JPanel {
        public AviationGraphic() {
            setOpaque(false);
            setPreferredSize(new Dimension(340, 160));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                g2.dispose();
                return;
            }

            int cx = w / 2;
            int cy = h / 2;

            // Radar rings
            g2.setColor(new Color(0x13, 0x2A, 0x44));
            g2.drawOval(cx - 60, cy - 60, 120, 120);
            g2.drawOval(cx - 110, cy - 110, 220, 220);

            // Flight Path Arc
            GeneralPath path = new GeneralPath();
            float startX = 24;
            float startY = h - 30;
            float endX = w - 34;
            float endY = 32;
            float ctrlX = w * 0.45f;
            float ctrlY = 12;

            path.moveTo(startX, startY);
            path.quadTo(ctrlX, ctrlY, endX, endY);

            Stroke dashed = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{6, 6}, 0);
            g2.setStroke(dashed);
            g2.setColor(AeroColors.ACCENT);
            g2.draw(path);

            // Waypoints
            g2.setColor(AeroColors.ACCENT);
            g2.fillOval((int) startX - 4, (int) startY - 4, 8, 8);
            g2.setColor(Color.WHITE);
            g2.fillOval((int) startX - 2, (int) startY - 2, 4, 4);

            g2.setColor(AeroColors.SUCCESS);
            g2.fillOval((int) endX - 5, (int) endY - 5, 10, 10);
            g2.setColor(Color.WHITE);
            g2.fillOval((int) endX - 2, (int) endY - 2, 4, 4);

            // Aircraft silhouette marker
            drawAirplaneMarker(g2, endX - 22, endY + 14);

            g2.dispose();
        }

        private void drawAirplaneMarker(Graphics2D g2, float x, float y) {
            g2.translate(x, y);
            g2.rotate(Math.toRadians(45));

            GeneralPath plane = new GeneralPath();
            plane.moveTo(0, -10);
            plane.lineTo(3, -2);
            plane.lineTo(10, 2);
            plane.lineTo(10, 4);
            plane.lineTo(3, 2);
            plane.lineTo(3, 7);
            plane.lineTo(6, 10);
            plane.lineTo(6, 11);
            plane.lineTo(0, 9);
            plane.lineTo(-6, 11);
            plane.lineTo(-6, 10);
            plane.lineTo(-3, 7);
            plane.lineTo(-3, 2);
            plane.lineTo(-10, 4);
            plane.lineTo(-10, 2);
            plane.lineTo(-3, -2);
            plane.closePath();

            g2.setColor(Color.WHITE);
            g2.fill(plane);

            g2.rotate(-Math.toRadians(45));
            g2.translate(-x, -y);
        }
    }

    public static void main(String[] args) {
        SchemaPreflightGuard.PreflightResult preflight = SchemaPreflightGuard.checkReadiness();
        if (!preflight.isReady()) {
            SchemaPreflightGuard.showDiagnosticDialog(null, preflight);
            return;
        }
        SwingUtilities.invokeLater(Login::new);
    }
}
