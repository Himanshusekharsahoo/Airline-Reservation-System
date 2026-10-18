package airline.management.system.ui.components;

import airline.management.system.security.InitialAdminProvisioningService;
import airline.management.system.security.InitialAdminProvisioningService.ProvisioningResult;
import airline.management.system.security.Role;
import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;

/**
 * First-time Administrator Setup Dialog for AeroDesk.
 *
 * <p>Provides a secure modal dialog allowing the initial system administrator
 * to provision their credentials on an uninitialized database.</p>
 */
public class FirstAdminSetupDialog extends JDialog {

    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(FirstAdminSetupDialog.class.getName());

    private final ModernTextField usernameField;
    private final ModernPasswordField passwordField;
    private final ModernPasswordField confirmPasswordField;
    private final NotificationBanner banner;
    private final ModernButton createButton;
    private final ModernButton cancelButton;
    private final Consumer<String> onSuccessCallback;
    private boolean busy = false;

    public FirstAdminSetupDialog(Window owner, Consumer<String> onSuccessCallback) {
        super(owner, "AeroDesk — First-Time Administrator Setup", ModalityType.APPLICATION_MODAL);
        this.onSuccessCallback = onSuccessCallback;

        setSize(480, 520);
        setResizable(false);
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(AeroColors.CARD_BG);
        content.setBorder(new EmptyBorder(24, 28, 24, 28));

        // Header Title
        JLabel titleLabel = new JLabel("Initialize Administrator");
        titleLabel.setFont(AeroFonts.TITLE_PAGE);
        titleLabel.setForeground(AeroColors.TEXT_MAIN);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(titleLabel);

        content.add(Box.createVerticalStrut(4));

        JLabel subtitleLabel = new JLabel("Configure the primary Super-Administrator account for this system.");
        subtitleLabel.setFont(AeroFonts.BODY);
        subtitleLabel.setForeground(AeroColors.TEXT_SECONDARY);
        subtitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(subtitleLabel);

        content.add(Box.createVerticalStrut(16));

        // Notification Banner
        banner = new NotificationBanner();
        banner.setAlignmentX(Component.LEFT_ALIGNMENT);
        banner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        content.add(banner);

        content.add(Box.createVerticalStrut(12));

        // Username Field
        JLabel userLabel = new JLabel("Administrator Username");
        userLabel.setFont(AeroFonts.BODY_BOLD);
        userLabel.setForeground(AeroColors.TEXT_MAIN);
        userLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(userLabel);
        content.add(Box.createVerticalStrut(6));

        usernameField = new ModernTextField(18);
        usernameField.setPlaceholder("e.g. ops_director");
        usernameField.setAlignmentX(Component.LEFT_ALIGNMENT);
        usernameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, AeroConstants.INPUT_HEIGHT));
        content.add(usernameField);

        content.add(Box.createVerticalStrut(14));

        // Password Field
        JLabel passLabel = new JLabel("Master Password (min. 8 characters)");
        passLabel.setFont(AeroFonts.BODY_BOLD);
        passLabel.setForeground(AeroColors.TEXT_MAIN);
        passLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(passLabel);
        content.add(Box.createVerticalStrut(6));

        passwordField = new ModernPasswordField(18);
        passwordField.setPlaceholder("Enter a strong master password");
        passwordField.setAlignmentX(Component.LEFT_ALIGNMENT);
        passwordField.setMaximumSize(new Dimension(Integer.MAX_VALUE, AeroConstants.INPUT_HEIGHT));
        content.add(passwordField);

        content.add(Box.createVerticalStrut(14));

        // Confirm Password Field
        JLabel confirmLabel = new JLabel("Confirm Password");
        confirmLabel.setFont(AeroFonts.BODY_BOLD);
        confirmLabel.setForeground(AeroColors.TEXT_MAIN);
        confirmLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(confirmLabel);
        content.add(Box.createVerticalStrut(6));

        confirmPasswordField = new ModernPasswordField(18);
        confirmPasswordField.setPlaceholder("Re-enter master password");
        confirmPasswordField.setAlignmentX(Component.LEFT_ALIGNMENT);
        confirmPasswordField.setMaximumSize(new Dimension(Integer.MAX_VALUE, AeroConstants.INPUT_HEIGHT));
        content.add(confirmPasswordField);

        content.add(Box.createVerticalStrut(20));

        // Action Buttons Panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttonPanel.setOpaque(false);
        buttonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttonPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        cancelButton = new ModernButton("Cancel", ModernButton.Style.GHOST);
        cancelButton.addActionListener(e -> dispose());
        buttonPanel.add(cancelButton);

        createButton = new ModernButton("Create Administrator", ModernButton.Style.PRIMARY);
        createButton.addActionListener(e -> submitProvisioning());
        buttonPanel.add(createButton);

        content.add(buttonPanel);

        setContentPane(content);

        // Keyboard & Focus setup
        getRootPane().setDefaultButton(createButton);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                usernameField.requestFocusInWindow();
            }
        });
    }

    private void submitProvisioning() {
        if (busy) {
            return;
        }

        String username = usernameField.getText().trim();
        char[] password = passwordField.getPassword();
        char[] confirmPassword = confirmPasswordField.getPassword();

        if (username.isEmpty()) {
            banner.showMessage("Username is required.", NotificationBanner.Type.WARNING);
            usernameField.requestFocusInWindow();
            wipe(password);
            wipe(confirmPassword);
            return;
        }

        if (password.length < InitialAdminProvisioningService.MIN_PASSWORD_LENGTH) {
            banner.showMessage("Password must be at least " + InitialAdminProvisioningService.MIN_PASSWORD_LENGTH + " characters.", NotificationBanner.Type.WARNING);
            passwordField.requestFocusInWindow();
            wipe(password);
            wipe(confirmPassword);
            return;
        }

        if (!Arrays.equals(password, confirmPassword)) {
            banner.showMessage("Passwords do not match.", NotificationBanner.Type.WARNING);
            confirmPasswordField.setText("");
            confirmPasswordField.requestFocusInWindow();
            wipe(password);
            wipe(confirmPassword);
            return;
        }

        setFormBusy(true);

        SwingWorker<ProvisioningResult, Void> worker = new SwingWorker<>() {
            @Override
            protected ProvisioningResult doInBackground() {
                try {
                    return InitialAdminProvisioningService.provisionInitialAdministrator(
                        username, password, confirmPassword, Role.SUPERADMIN
                    );
                } catch (Exception e) {
                    LOGGER.log(java.util.logging.Level.SEVERE, "Initial administrator provisioning failed due to database error", e);
                    return new ProvisioningResult(
                        InitialAdminProvisioningService.ProvisioningStatus.DATABASE_ERROR,
                        null, username, Role.SUPERADMIN,
                        "Unable to complete setup due to a database communication error. Please check the database service and system logs."
                    );
                } finally {
                    wipe(password);
                    wipe(confirmPassword);
                }
            }

            @Override
            protected void done() {
                setFormBusy(false);
                try {
                    ProvisioningResult result = get();
                    if (result.isSuccess()) {
                        if (onSuccessCallback != null) {
                            onSuccessCallback.accept(result.username());
                        }
                        dispose();
                    } else {
                        banner.showMessage(result.message(), NotificationBanner.Type.ERROR);
                        passwordField.setText("");
                        confirmPasswordField.setText("");
                        passwordField.requestFocusInWindow();
                    }
                } catch (Exception e) {
                    LOGGER.log(java.util.logging.Level.SEVERE, "Unexpected error processing provisioning result", e);
                    banner.showMessage("An unexpected error occurred during setup. Please check the system logs.", NotificationBanner.Type.ERROR);
                }
            }
        };

        worker.execute();
    }

    private void setFormBusy(boolean busy) {
        this.busy = busy;
        usernameField.setEnabled(!busy);
        passwordField.setEnabled(!busy);
        confirmPasswordField.setEnabled(!busy);
        createButton.setEnabled(!busy);
        cancelButton.setEnabled(!busy);
        if (busy) {
            createButton.setText("Provisioning...");
        } else {
            createButton.setText("Create Administrator");
        }
    }

    private static void wipe(char[] a) {
        if (a != null) {
            Arrays.fill(a, '\0');
        }
    }
}
