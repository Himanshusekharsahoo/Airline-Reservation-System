package airline.management.system.ui.panels;

import airline.management.system.operations.report.OperationalReportService;
import airline.management.system.operations.report.OperationalReportService.Category;
import airline.management.system.operations.report.OperationalReportService.ReportData;
import airline.management.system.operations.report.OperationalReportService.ReportFilter;
import airline.management.system.ui.components.AeroScrollPane;
import airline.management.system.ui.components.DashboardCard;
import airline.management.system.ui.components.ModernButton;
import airline.management.system.ui.components.ModernComboBox;
import airline.management.system.ui.components.ModernTextField;
import airline.management.system.ui.components.NotificationBanner;
import airline.management.system.ui.components.StyledTable;
import airline.management.system.ui.navigation.NavigationController;
import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

/**
 * AeroDesk Operational Intelligence and Reporting Panel.
 * Provides tenant-scoped KPI metrics and tabular breakdowns across flights,
 * passengers, reservations, financial settlement, and gate utilization.
 */
public class ReportsPanel extends JPanel {
    private final NavigationController navigationController;

    private final ModernComboBox<Category> categoryFilter;
    private final ModernComboBox<String> dateRangeFilter;
    private final ModernTextField flightSearchField;
    private final ModernComboBox<String> statusFilter;
    private final ModernButton refreshBtn;
    private final ModernButton exportBtn;
    private final NotificationBanner notificationBanner;

    private final JPanel kpiContainer;
    private final StyledTable reportTable;
    private final CardLayout tableCardLayout = new CardLayout();
    private final JPanel tableCardContainer = new JPanel(tableCardLayout);

    public ReportsPanel(NavigationController controller) {
        this.navigationController = controller;
        setLayout(new BorderLayout());
        setBackground(AeroColors.BACKGROUND);

        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(AeroColors.BACKGROUND);
        contentPanel.setBorder(new EmptyBorder(24, 28, 28, 28));

        // 1. Header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);

        JPanel titleBlock = new JPanel();
        titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
        titleBlock.setOpaque(false);

        JLabel title = new JLabel("Operational Intelligence & Reports");
        title.setFont(AeroFonts.TITLE_PAGE);
        title.setForeground(AeroColors.TEXT_MAIN);

        JLabel subtitle = new JLabel("Real-time operational analytics, flight volumes, passenger manifests, settlement, and gate utilization.");
        subtitle.setFont(AeroFonts.BODY);
        subtitle.setForeground(AeroColors.TEXT_SECONDARY);

        titleBlock.add(title);
        titleBlock.add(Box.createVerticalStrut(2));
        titleBlock.add(subtitle);
        headerPanel.add(titleBlock, BorderLayout.WEST);

        contentPanel.add(headerPanel);
        contentPanel.add(Box.createVerticalStrut(14));

        notificationBanner = new NotificationBanner();
        contentPanel.add(notificationBanner);
        contentPanel.add(Box.createVerticalStrut(14));

        // 2. Filter Bar Card
        JPanel filterCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 10));
        filterCard.setBackground(Color.WHITE);
        filterCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(8, 14, 8, 14)
        ));
        filterCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 65));

        categoryFilter = new ModernComboBox<>(Category.values());
        categoryFilter.setPreferredSize(new Dimension(190, 34));
        categoryFilter.addActionListener(e -> updateStatusFilterOptions());

        dateRangeFilter = new ModernComboBox<>(new String[]{"All Time", "Today", "Last 7 Days", "Last 30 Days"});
        dateRangeFilter.setPreferredSize(new Dimension(130, 34));

        flightSearchField = new ModernTextField("Filter Flight #", 10);
        flightSearchField.setPreferredSize(new Dimension(130, 34));

        statusFilter = new ModernComboBox<>(new String[]{"ALL"});
        statusFilter.setPreferredSize(new Dimension(130, 34));
        updateStatusFilterOptions();

        refreshBtn = new ModernButton("Generate Report", ModernButton.Style.PRIMARY);
        refreshBtn.setPreferredSize(new Dimension(140, 34));
        refreshBtn.addActionListener(e -> loadReportAsync());

        exportBtn = new ModernButton("Export CSV", ModernButton.Style.SECONDARY);
        exportBtn.setPreferredSize(new Dimension(110, 34));
        exportBtn.addActionListener(e -> exportToCsv());

        filterCard.add(new JLabel("Category:"));
        filterCard.add(categoryFilter);
        filterCard.add(new JLabel("Date:"));
        filterCard.add(dateRangeFilter);
        filterCard.add(new JLabel("Flight:"));
        filterCard.add(flightSearchField);
        filterCard.add(new JLabel("Status:"));
        filterCard.add(statusFilter);
        filterCard.add(refreshBtn);
        filterCard.add(exportBtn);

        contentPanel.add(filterCard);
        contentPanel.add(Box.createVerticalStrut(18));

        // 3. KPI Cards Row
        kpiContainer = new JPanel(new GridLayout(1, 4, 16, 0));
        kpiContainer.setOpaque(false);
        kpiContainer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
        contentPanel.add(kpiContainer);
        contentPanel.add(Box.createVerticalStrut(18));

        // 4. Report Table Card
        JPanel tableCard = new JPanel(new BorderLayout());
        tableCard.setBackground(Color.WHITE);
        tableCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AeroColors.BORDER, 1),
            new EmptyBorder(16, 18, 18, 18)
        ));

        reportTable = new StyledTable();
        tableCardContainer.setOpaque(false);
        tableCardContainer.add(new JScrollPane(reportTable), "table");
        tableCardContainer.add(createEmptyState(), "empty");
        tableCardLayout.show(tableCardContainer, "empty");

        tableCard.add(tableCardContainer, BorderLayout.CENTER);
        contentPanel.add(tableCard);

        add(new AeroScrollPane(contentPanel), BorderLayout.CENTER);

        loadReportAsync();
    }

    private void updateStatusFilterOptions() {
        Category cat = (Category) categoryFilter.getSelectedItem();
        statusFilter.removeAllItems();
        statusFilter.addItem("ALL");
        if (cat == Category.FLIGHT_OPERATIONS) {
            statusFilter.addItem("SCHEDULED");
            statusFilter.addItem("BOARDING");
            statusFilter.addItem("DEPARTED");
            statusFilter.addItem("ARRIVED");
            statusFilter.addItem("DELAYED");
            statusFilter.addItem("CANCELLED");
        } else if (cat == Category.RESERVATIONS) {
            statusFilter.addItem("CONFIRMED");
            statusFilter.addItem("CANCELLED");
            statusFilter.addItem("PENDING_PAYMENT");
        } else if (cat == Category.FINANCE) {
            statusFilter.addItem("SUCCESS");
            statusFilter.addItem("COMPLETED");
            statusFilter.addItem("REFUNDED");
            statusFilter.addItem("PENDING");
        } else if (cat == Category.BAGGAGE_OPERATIONS) {
            statusFilter.addItem("REGISTERED");
            statusFilter.addItem("ACCEPTED");
            statusFilter.addItem("LOADED");
            statusFilter.addItem("IN_TRANSIT");
            statusFilter.addItem("DELIVERED");
            statusFilter.addItem("DELAYED");
            statusFilter.addItem("MISSING");
            statusFilter.addItem("CANCELLED");
        } else if (cat == Category.PIR_CLAIMS) {
            statusFilter.addItem("OPEN");
            statusFilter.addItem("UNDER_INVESTIGATION");
            statusFilter.addItem("LOCATED");
            statusFilter.addItem("DELIVERED");
            statusFilter.addItem("UNRESOLVED");
            statusFilter.addItem("CLOSED");
        } else if (cat == Category.IROPS_DISRUPTIONS) {
            statusFilter.addItem("DECLARED");
            statusFilter.addItem("ASSESSING");
            statusFilter.addItem("ACTION_REQUIRED");
            statusFilter.addItem("MITIGATED");
            statusFilter.addItem("RESOLVED");
            statusFilter.addItem("CANCELLED");
        }
    }

    private JPanel createEmptyState() {
        JPanel emptyPanel = new JPanel();
        emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
        emptyPanel.setBackground(Color.WHITE);
        emptyPanel.setBorder(new EmptyBorder(60, 20, 60, 20));

        JLabel title = new JLabel("No data matching report criteria.");
        title.setFont(AeroFonts.BODY_BOLD);
        title.setForeground(AeroColors.TEXT_SECONDARY);
        title.setAlignmentX(CENTER_ALIGNMENT);

        JLabel desc = new JLabel("Try adjusting your filters or selecting a different reporting category.");
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

    public void loadReportAsync() {
        refreshBtn.setEnabled(false);
        refreshBtn.setText("Compiling…");

        Category selectedCategory = (Category) categoryFilter.getSelectedItem();
        String dateSelection = (String) dateRangeFilter.getSelectedItem();
        String flightFilter = flightSearchField.getText().trim();
        String status = (String) statusFilter.getSelectedItem();

        LocalDate start = null;
        LocalDate end = null;
        LocalDate today = LocalDate.now();

        if ("Today".equalsIgnoreCase(dateSelection)) {
            start = today;
            end = today;
        } else if ("Last 7 Days".equalsIgnoreCase(dateSelection)) {
            start = today.minusDays(7);
            end = today;
        } else if ("Last 30 Days".equalsIgnoreCase(dateSelection)) {
            start = today.minusDays(30);
            end = today;
        }

        ReportFilter filter = new ReportFilter(selectedCategory, start, end, flightFilter, status);

        SwingWorker<ReportData, Void> worker = new SwingWorker<>() {
            @Override
            protected ReportData doInBackground() throws Exception {
                return OperationalReportService.generateReport(filter);
            }

            @Override
            protected void done() {
                refreshBtn.setEnabled(true);
                refreshBtn.setText("Generate Report");
                try {
                    ReportData data = get();
                    renderReport(data);
                } catch (Exception ex) {
                    notificationBanner.showMessage("Error compiling report: " + ex.getMessage(), NotificationBanner.Type.ERROR);
                }
            }
        };
        worker.execute();
    }

    private void renderReport(ReportData data) {
        // Update KPI Cards
        kpiContainer.removeAll();
        Map<String, String> metrics = data.summaryMetrics();
        int count = 0;
        for (Map.Entry<String, String> entry : metrics.entrySet()) {
            if (count++ >= 4) break;
            DashboardCard card = new DashboardCard(entry.getKey(), entry.getValue(), "Tenant Scoped");
            kpiContainer.add(card);
        }
        kpiContainer.revalidate();
        kpiContainer.repaint();

        // Update Table
        DefaultTableModel model = data.tableModel();
        if (model != null && model.getRowCount() > 0) {
            reportTable.setModel(model);
            tableCardLayout.show(tableCardContainer, "table");
        } else {
            tableCardLayout.show(tableCardContainer, "empty");
        }
    }

    private void exportToCsv() {
        DefaultTableModel model = (DefaultTableModel) reportTable.getModel();
        if (model == null || model.getRowCount() == 0) {
            notificationBanner.showMessage("No data available to export.", NotificationBanner.Type.WARNING);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export Report CSV");
        chooser.setSelectedFile(new File("aerodesk_report_" + System.currentTimeMillis() + ".csv"));
        chooser.setFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));

        int userSelection = chooser.showSaveDialog(this);
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToSave = chooser.getSelectedFile();
            if (!fileToSave.getName().toLowerCase().endsWith(".csv")) {
                fileToSave = new File(fileToSave.getAbsolutePath() + ".csv");
            }

            try (PrintWriter pw = new PrintWriter(new FileWriter(fileToSave))) {
                int colCount = model.getColumnCount();
                for (int c = 0; c < colCount; c++) {
                    pw.print(escapeCsv(model.getColumnName(c)));
                    if (c < colCount - 1) pw.print(",");
                }
                pw.println();

                int rowCount = model.getRowCount();
                for (int r = 0; r < rowCount; r++) {
                    for (int c = 0; c < colCount; c++) {
                        Object val = model.getValueAt(r, c);
                        pw.print(escapeCsv(val != null ? val.toString() : ""));
                        if (c < colCount - 1) pw.print(",");
                    }
                    pw.println();
                }
                notificationBanner.showMessage("Report successfully exported to " + fileToSave.getName(), NotificationBanner.Type.SUCCESS);
            } catch (Exception e) {
                notificationBanner.showMessage("Failed to export report: " + e.getMessage(), NotificationBanner.Type.ERROR);
            }
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
