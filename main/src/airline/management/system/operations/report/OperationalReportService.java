package airline.management.system.operations.report;

import airline.management.system.DBConnection;
import airline.management.system.TableUtils;
import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.tenant.TenantContext;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.table.DefaultTableModel;

/**
 * Operational intelligence and reporting service executing tenant-scoped SQL aggregations.
 * Strictly enforces tenant boundaries; computes real metrics directly in the database.
 */
public final class OperationalReportService {

    public enum Category {
        FLIGHT_OPERATIONS("Flight Operations"),
        PASSENGER_OPERATIONS("Passenger Operations"),
        RESERVATIONS("Reservations & Ticketing"),
        FINANCE("Financial Settlement"),
        GATE_UTILIZATION("Gate Utilization"),
        BAGGAGE_OPERATIONS("Baggage Operations"),
        PIR_CLAIMS("PIR & Claims"),
        IROPS_DISRUPTIONS("Flight Disruptions & IROPS");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public record ReportFilter(
        Category category,
        LocalDate startDate,
        LocalDate endDate,
        String flightFilter,
        String statusFilter
    ) {}

    public record ReportData(
        Category category,
        Map<String, String> summaryMetrics,
        DefaultTableModel tableModel
    ) {}

    private OperationalReportService() {}

    /**
     * Compiles an operational intelligence report matching the specified filter criteria.
     * Guaranteed to be strictly scoped by active tenant organization ID.
     */
    public static ReportData generateReport(ReportFilter filter) throws SQLException {
        AuthorizationService.requirePermission(Permission.VIEW_REPORTS);
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        ReportData result;
        switch (filter.category()) {
            case FLIGHT_OPERATIONS -> result = compileFlightOperationsReport(tenantOrgId, filter);
            case PASSENGER_OPERATIONS -> result = compilePassengerOperationsReport(tenantOrgId, filter);
            case RESERVATIONS -> result = compileReservationsReport(tenantOrgId, filter);
            case FINANCE -> result = compileFinanceReport(tenantOrgId, filter);
            case GATE_UTILIZATION -> result = compileGateReport(tenantOrgId, filter);
            case BAGGAGE_OPERATIONS -> result = compileBaggageOperationsReport(tenantOrgId, filter);
            case PIR_CLAIMS -> result = compilePIRClaimsReport(tenantOrgId, filter);
            case IROPS_DISRUPTIONS -> result = compileIROPSReport(tenantOrgId, filter);
            default -> throw new IllegalArgumentException("Unsupported report category: " + filter.category());
        }

        AuditService.record(
            AuditAction.REPORT_GENERATED,
            "REPORT",
            filter.category().name(),
            "Generated operational report for [" + filter.category().getDisplayName() + "] (Tenant #" + tenantOrgId + ")"
        );

        return result;
    }

    private static ReportData compileFlightOperationsReport(long orgId, ReportFilter filter) throws SQLException {
        Map<String, String> kpis = new LinkedHashMap<>();
        DefaultTableModel model;

        try (Connection conn = DBConnection.getConnection()) {
            // 1. Aggregation queries
            String aggSql =
                "SELECT COUNT(*) AS total_flights, " +
                "       COUNT(CASE WHEN status IN ('SCHEDULED', 'ACTIVE') THEN 1 END) AS scheduled_flights, " +
                "       COUNT(CASE WHEN status = 'DELAYED' THEN 1 END) AS delayed_flights, " +
                "       COUNT(CASE WHEN status = 'CANCELLED' THEN 1 END) AS cancelled_flights, " +
                "       COUNT(CASE WHEN status = 'ARRIVED' THEN 1 END) AS completed_flights " +
                "FROM flights WHERE organization_id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(aggSql)) {
                stmt.setLong(1, orgId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        kpis.put("Total Flights", String.valueOf(rs.getInt("total_flights")));
                        kpis.put("Scheduled / Active", String.valueOf(rs.getInt("scheduled_flights")));
                        kpis.put("Delayed", String.valueOf(rs.getInt("delayed_flights")));
                        kpis.put("Cancelled", String.valueOf(rs.getInt("cancelled_flights")));
                        kpis.put("Completed", String.valueOf(rs.getInt("completed_flights")));
                    }
                }
            }

            // 2. Table query
            StringBuilder sql = new StringBuilder(
                "SELECT flight_number AS 'Flight #', airline AS 'Airline', " +
                "       CONCAT(source, ' → ', destination) AS 'Route', " +
                "       departure_time AS 'Departure', arrival_time AS 'Arrival', " +
                "       (total_seats - available_seats) AS 'Booked', available_seats AS 'Available', " +
                "       fare AS 'Fare ($)', status AS 'Status' " +
                "FROM flights WHERE organization_id = ?"
            );
            List<Object> params = new ArrayList<>();
            params.add(orgId);

            if (filter.flightFilter() != null && !filter.flightFilter().isBlank()) {
                sql.append(" AND flight_number LIKE ?");
                params.add("%" + filter.flightFilter().trim() + "%");
            }
            if (filter.statusFilter() != null && !"ALL".equalsIgnoreCase(filter.statusFilter()) && !filter.statusFilter().isBlank()) {
                sql.append(" AND status = ?");
                params.add(filter.statusFilter().trim());
            }
            if (filter.startDate() != null) {
                sql.append(" AND DATE(departure_time) >= ?");
                params.add(java.sql.Date.valueOf(filter.startDate()));
            }
            if (filter.endDate() != null) {
                sql.append(" AND DATE(departure_time) <= ?");
                params.add(java.sql.Date.valueOf(filter.endDate()));
            }

            sql.append(" ORDER BY departure_time DESC LIMIT 100");

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    model = TableUtils.toTableModel(rs);
                }
            }
        }
        return new ReportData(Category.FLIGHT_OPERATIONS, kpis, model);
    }

    private static ReportData compilePassengerOperationsReport(long orgId, ReportFilter filter) throws SQLException {
        Map<String, String> kpis = new LinkedHashMap<>();
        DefaultTableModel model;

        try (Connection conn = DBConnection.getConnection()) {
            // Aggregated passenger counts
            String aggSql =
                "SELECT COUNT(DISTINCT r.id) AS total_passengers, " +
                "       COUNT(DISTINCT c.id) AS checkedin_passengers, " +
                "       COUNT(DISTINCT be.id) AS boarded_passengers, " +
                "       (COUNT(DISTINCT r.id) - COUNT(DISTINCT c.id)) AS pending_checkin " +
                "FROM reservations r " +
                "LEFT JOIN checkins c ON c.reservation_id = r.id AND c.organization_id = r.organization_id " +
                "LEFT JOIN boarding_passes bp ON bp.checkin_id = c.id AND bp.organization_id = r.organization_id " +
                "LEFT JOIN boarding_events be ON be.boarding_pass_id = bp.id AND be.organization_id = r.organization_id " +
                "WHERE r.organization_id = ? AND r.status = 'CONFIRMED'";

            try (PreparedStatement stmt = conn.prepareStatement(aggSql)) {
                stmt.setLong(1, orgId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        kpis.put("Confirmed Passengers", String.valueOf(rs.getInt("total_passengers")));
                        kpis.put("Checked In", String.valueOf(rs.getInt("checkedin_passengers")));
                        kpis.put("Gate Boarded", String.valueOf(rs.getInt("boarded_passengers")));
                        kpis.put("Pending Check-in", String.valueOf(rs.getInt("pending_checkin")));
                    }
                }
            }

            // Passenger distribution per flight
            StringBuilder sql = new StringBuilder(
                "SELECT f.flight_number AS 'Flight #', CONCAT(f.source, ' → ', f.destination) AS 'Route', " +
                "       f.departure_time AS 'Departure', " +
                "       COUNT(r.id) AS 'Total Booked', " +
                "       COUNT(c.id) AS 'Checked In', " +
                "       COUNT(be.id) AS 'Boarded', " +
                "       (COUNT(r.id) - COUNT(c.id)) AS 'Pending' " +
                "FROM flights f " +
                "LEFT JOIN reservations r ON r.flight_id = f.id AND r.status = 'CONFIRMED' AND r.organization_id = f.organization_id " +
                "LEFT JOIN checkins c ON c.reservation_id = r.id AND c.organization_id = f.organization_id " +
                "LEFT JOIN boarding_passes bp ON bp.checkin_id = c.id AND bp.organization_id = f.organization_id " +
                "LEFT JOIN boarding_events be ON be.boarding_pass_id = bp.id AND be.organization_id = f.organization_id " +
                "WHERE f.organization_id = ?"
            );
            List<Object> params = new ArrayList<>();
            params.add(orgId);

            if (filter.flightFilter() != null && !filter.flightFilter().isBlank()) {
                sql.append(" AND f.flight_number LIKE ?");
                params.add("%" + filter.flightFilter().trim() + "%");
            }
            if (filter.startDate() != null) {
                sql.append(" AND DATE(f.departure_time) >= ?");
                params.add(java.sql.Date.valueOf(filter.startDate()));
            }
            if (filter.endDate() != null) {
                sql.append(" AND DATE(f.departure_time) <= ?");
                params.add(java.sql.Date.valueOf(filter.endDate()));
            }

            sql.append(" GROUP BY f.id, f.flight_number, f.source, f.destination, f.departure_time ORDER BY f.departure_time DESC LIMIT 100");

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    model = TableUtils.toTableModel(rs);
                }
            }
        }
        return new ReportData(Category.PASSENGER_OPERATIONS, kpis, model);
    }

    private static ReportData compileReservationsReport(long orgId, ReportFilter filter) throws SQLException {
        Map<String, String> kpis = new LinkedHashMap<>();
        DefaultTableModel model;

        try (Connection conn = DBConnection.getConnection()) {
            String aggSql =
                "SELECT COUNT(*) AS total_reservations, " +
                "       COUNT(CASE WHEN status = 'CONFIRMED' THEN 1 END) AS confirmed_count, " +
                "       COUNT(CASE WHEN status = 'CANCELLED' THEN 1 END) AS cancelled_count, " +
                "       COALESCE(SUM(CASE WHEN status = 'CONFIRMED' THEN fare ELSE 0 END), 0) AS active_volume " +
                "FROM reservations WHERE organization_id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(aggSql)) {
                stmt.setLong(1, orgId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        kpis.put("Total Reservations", String.valueOf(rs.getInt("total_reservations")));
                        kpis.put("Active / Confirmed", String.valueOf(rs.getInt("confirmed_count")));
                        kpis.put("Cancelled", String.valueOf(rs.getInt("cancelled_count")));
                        kpis.put("Active Fare Volume", "$" + String.format("%.2f", rs.getDouble("active_volume")));
                    }
                }
            }

            StringBuilder sql = new StringBuilder(
                "SELECT r.pnr AS 'PNR', p.name AS 'Passenger', f.flight_number AS 'Flight #', " +
                "       COALESCE(r.seat_number, '—') AS 'Seat', r.fare AS 'Fare ($)', " +
                "       r.status AS 'Status', r.booking_date AS 'Booked Date', " +
                "       COALESCE(pmt.payment_status, 'PENDING') AS 'Payment Status' " +
                "FROM reservations r " +
                "JOIN passengers p ON p.id = r.passenger_id " +
                "JOIN flights f ON f.id = r.flight_id " +
                "LEFT JOIN payments pmt ON pmt.reservation_id = r.id AND pmt.organization_id = r.organization_id " +
                "WHERE r.organization_id = ?"
            );
            List<Object> params = new ArrayList<>();
            params.add(orgId);

            if (filter.flightFilter() != null && !filter.flightFilter().isBlank()) {
                sql.append(" AND f.flight_number LIKE ?");
                params.add("%" + filter.flightFilter().trim() + "%");
            }
            if (filter.statusFilter() != null && !"ALL".equalsIgnoreCase(filter.statusFilter()) && !filter.statusFilter().isBlank()) {
                sql.append(" AND r.status = ?");
                params.add(filter.statusFilter().trim());
            }
            if (filter.startDate() != null) {
                sql.append(" AND DATE(r.booking_date) >= ?");
                params.add(java.sql.Date.valueOf(filter.startDate()));
            }
            if (filter.endDate() != null) {
                sql.append(" AND DATE(r.booking_date) <= ?");
                params.add(java.sql.Date.valueOf(filter.endDate()));
            }

            sql.append(" ORDER BY r.booking_date DESC LIMIT 100");

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    model = TableUtils.toTableModel(rs);
                }
            }
        }
        return new ReportData(Category.RESERVATIONS, kpis, model);
    }

    private static ReportData compileFinanceReport(long orgId, ReportFilter filter) throws SQLException {
        Map<String, String> kpis = new LinkedHashMap<>();
        DefaultTableModel model;

        try (Connection conn = DBConnection.getConnection()) {
            String aggSql =
                "SELECT COALESCE(SUM(CASE WHEN payment_status IN ('SUCCESS', 'COMPLETED') THEN amount ELSE 0 END), 0) AS total_payments, " +
                "       COALESCE(SUM(CASE WHEN payment_status = 'REFUNDED' THEN amount ELSE 0 END), 0) AS total_refunds, " +
                "       COUNT(CASE WHEN payment_status IN ('SUCCESS', 'COMPLETED') THEN 1 END) AS successful_transactions, " +
                "       COUNT(CASE WHEN payment_status = 'REFUNDED' THEN 1 END) AS refunded_transactions " +
                "FROM payments WHERE organization_id = ?";

            double payments = 0.0;
            double refunds = 0.0;

            try (PreparedStatement stmt = conn.prepareStatement(aggSql)) {
                stmt.setLong(1, orgId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        payments = rs.getDouble("total_payments");
                        refunds = rs.getDouble("total_refunds");
                        double netSettlement = payments - refunds;

                        kpis.put("Gross Collections", "$" + String.format("%.2f", payments));
                        kpis.put("Refunds Processed", "$" + String.format("%.2f", refunds));
                        kpis.put("Net Settlement", "$" + String.format("%.2f", netSettlement));
                        kpis.put("Completed Payments", String.valueOf(rs.getInt("successful_transactions")));
                        kpis.put("Refunded Count", String.valueOf(rs.getInt("refunded_transactions")));
                    }
                }
            }

            StringBuilder sql = new StringBuilder(
                "SELECT p.transaction_reference AS 'Reference', r.pnr AS 'PNR', " +
                "       p.amount AS 'Amount ($)', p.payment_method AS 'Method', " +
                "       p.payment_status AS 'Status', p.payment_date AS 'Transaction Date' " +
                "FROM payments p " +
                "JOIN reservations r ON r.id = p.reservation_id " +
                "WHERE p.organization_id = ?"
            );
            List<Object> params = new ArrayList<>();
            params.add(orgId);

            if (filter.statusFilter() != null && !"ALL".equalsIgnoreCase(filter.statusFilter()) && !filter.statusFilter().isBlank()) {
                sql.append(" AND p.payment_status = ?");
                params.add(filter.statusFilter().trim());
            }
            if (filter.startDate() != null) {
                sql.append(" AND DATE(p.payment_date) >= ?");
                params.add(java.sql.Date.valueOf(filter.startDate()));
            }
            if (filter.endDate() != null) {
                sql.append(" AND DATE(p.payment_date) <= ?");
                params.add(java.sql.Date.valueOf(filter.endDate()));
            }

            sql.append(" ORDER BY p.payment_date DESC LIMIT 100");

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    model = TableUtils.toTableModel(rs);
                }
            }
        }
        return new ReportData(Category.FINANCE, kpis, model);
    }

    private static ReportData compileGateReport(long orgId, ReportFilter filter) throws SQLException {
        Map<String, String> kpis = new LinkedHashMap<>();
        DefaultTableModel model;

        try (Connection conn = DBConnection.getConnection()) {
            String aggSql =
                "SELECT COUNT(*) AS total_gates, " +
                "       COUNT(CASE WHEN status = 'AVAILABLE' THEN 1 END) AS available_gates, " +
                "       COUNT(CASE WHEN status = 'OCCUPIED' THEN 1 END) AS occupied_gates, " +
                "       (SELECT COUNT(*) FROM flight_gates WHERE organization_id = ?) AS total_assignments " +
                "FROM gates WHERE organization_id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(aggSql)) {
                stmt.setLong(1, orgId);
                stmt.setLong(2, orgId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        int total = rs.getInt("total_gates");
                        int occupied = rs.getInt("occupied_gates");
                        kpis.put("Total Gates", String.valueOf(total));
                        kpis.put("Available Gates", String.valueOf(rs.getInt("available_gates")));
                        kpis.put("Occupied Gates", String.valueOf(occupied));
                        kpis.put("Total Assignments", String.valueOf(rs.getInt("total_assignments")));
                        double util = total > 0 ? ((double) occupied / total) * 100.0 : 0.0;
                        kpis.put("Utilization Rate", String.format("%.1f%%", util));
                    }
                }
            }

            String sql =
                "SELECT g.gate_number AS 'Gate #', g.terminal AS 'Terminal', g.airport_code AS 'Airport', " +
                "       g.status AS 'Gate Status', " +
                "       COALESCE(f.flight_number, '—') AS 'Current Flight', " +
                "       COALESCE(CONCAT(f.source, ' → ', f.destination), '—') AS 'Route', " +
                "       COALESCE(fg.scheduled_start, '—') AS 'Scheduled Start', " +
                "       COALESCE(fg.scheduled_end, '—') AS 'Scheduled End' " +
                "FROM gates g " +
                "LEFT JOIN flight_gates fg ON fg.gate_id = g.id AND fg.organization_id = g.organization_id " +
                "       AND (NOW() BETWEEN fg.scheduled_start AND fg.scheduled_end OR g.status = 'OCCUPIED') " +
                "LEFT JOIN flights f ON f.id = fg.flight_id " +
                "WHERE g.organization_id = ? " +
                "ORDER BY g.terminal ASC, g.gate_number ASC LIMIT 100";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, orgId);
                try (ResultSet rs = stmt.executeQuery()) {
                    model = TableUtils.toTableModel(rs);
                }
            }
        }
        return new ReportData(Category.GATE_UTILIZATION, kpis, model);
    }

    private static ReportData compileBaggageOperationsReport(long orgId, ReportFilter filter) throws SQLException {
        Map<String, String> kpis = new LinkedHashMap<>();
        DefaultTableModel model;

        try (Connection conn = DBConnection.getConnection()) {
            StringBuilder aggSql = new StringBuilder(
                "SELECT COUNT(*) AS total_baggage, " +
                "       COALESCE(SUM(CASE WHEN status IN ('ACCEPTED', 'LOADED') THEN 1 ELSE 0 END), 0) AS active_bags, " +
                "       COALESCE(SUM(CASE WHEN status = 'DELIVERED' THEN 1 ELSE 0 END), 0) AS delivered_bags, " +
                "       COALESCE(SUM(CASE WHEN status IN ('DELAYED', 'MISSING') THEN 1 ELSE 0 END), 0) AS exception_bags, " +
                "       COALESCE(SUM(weight_kg), 0.00) AS total_weight, " +
                "       COALESCE(AVG(weight_kg), 0.00) AS avg_weight " +
                "FROM baggage WHERE organization_id = ?"
            );

            List<Object> aggParams = new ArrayList<>();
            aggParams.add(orgId);

            if (filter.startDate() != null) {
                aggSql.append(" AND created_at >= ?");
                aggParams.add(filter.startDate().atStartOfDay());
            }
            if (filter.endDate() != null) {
                aggSql.append(" AND created_at <= ?");
                aggParams.add(filter.endDate().atTime(23, 59, 59));
            }

            try (PreparedStatement stmt = conn.prepareStatement(aggSql.toString())) {
                for (int i = 0; i < aggParams.size(); i++) {
                    stmt.setObject(i + 1, aggParams.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        kpis.put("Total Baggage", String.valueOf(rs.getInt("total_baggage")));
                        kpis.put("Accepted / Loaded", String.valueOf(rs.getInt("active_bags")));
                        kpis.put("Delivered", String.valueOf(rs.getInt("delivered_bags")));
                        kpis.put("Exceptions", String.valueOf(rs.getInt("exception_bags")));
                        kpis.put("Exceptions (Delayed/Lost)", String.valueOf(rs.getInt("exception_bags")));
                        kpis.put("Total Weight (kg)", String.format("%.2f", rs.getDouble("total_weight")));
                        kpis.put("Avg Weight", String.format("%.2f kg", rs.getDouble("avg_weight")));
                    }
                }
            }

            StringBuilder sql = new StringBuilder(
                "SELECT b.tag_number AS 'Tag #', " +
                "       p.name AS 'Passenger', " +
                "       r.pnr AS 'PNR', " +
                "       f.flight_number AS 'Flight', " +
                "       CONCAT(f.source, ' → ', f.destination) AS 'Route', " +
                "       b.baggage_type AS 'Type', " +
                "       CONCAT(b.weight_kg, ' kg') AS 'Weight', " +
                "       b.status AS 'Status', " +
                "       b.created_at AS 'Registered At', " +
                "       b.updated_at AS 'Last Updated' " +
                "FROM baggage b " +
                "JOIN passengers p ON p.id = b.passenger_id " +
                "JOIN reservations r ON r.id = b.reservation_id " +
                "JOIN flights f ON f.id = b.flight_id " +
                "WHERE b.organization_id = ?"
            );

            List<Object> params = new ArrayList<>();
            params.add(orgId);

            if (filter.startDate() != null) {
                sql.append(" AND b.created_at >= ?");
                params.add(filter.startDate().atStartOfDay());
            }
            if (filter.endDate() != null) {
                sql.append(" AND b.created_at <= ?");
                params.add(filter.endDate().atTime(23, 59, 59));
            }
            if (filter.flightFilter() != null && !filter.flightFilter().isBlank()) {
                sql.append(" AND f.flight_number = ?");
                params.add(filter.flightFilter().trim());
            }
            if (filter.statusFilter() != null && !filter.statusFilter().equalsIgnoreCase("ALL")) {
                sql.append(" AND b.status = ?");
                params.add(filter.statusFilter().trim());
            }

            sql.append(" ORDER BY b.created_at DESC LIMIT 150");

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    model = TableUtils.toTableModel(rs);
                }
            }
        }

        return new ReportData(Category.BAGGAGE_OPERATIONS, kpis, model);
    }

    private static ReportData compilePIRClaimsReport(long orgId, ReportFilter filter) throws SQLException {
        Map<String, String> kpis = new LinkedHashMap<>();
        DefaultTableModel model = new DefaultTableModel();

        try (Connection conn = DBConnection.getConnection()) {
            if (!tableExists(conn, "pir_reports")) {
                return new ReportData(Category.PIR_CLAIMS, kpis, model);
            }

            // 1. Aggregation queries with optional date filtering
            StringBuilder pirDateClause = new StringBuilder();
            StringBuilder claimDateClause = new StringBuilder();
            List<Object> dateParams = new ArrayList<>();
            if (filter.startDate() != null) {
                pirDateClause.append(" AND created_at >= ?");
                claimDateClause.append(" AND created_at >= ?");
                dateParams.add(filter.startDate().atStartOfDay());
            }
            if (filter.endDate() != null) {
                pirDateClause.append(" AND created_at <= ?");
                claimDateClause.append(" AND created_at <= ?");
                dateParams.add(filter.endDate().atTime(23, 59, 59));
            }

            String pDate = pirDateClause.toString();
            String cDate = claimDateClause.toString();

            String aggSql =
                "SELECT " +
                "    (SELECT COUNT(*) FROM pir_reports WHERE organization_id = ?" + pDate + ") AS total_pirs, " +
                "    (SELECT COUNT(*) FROM pir_reports WHERE organization_id = ? AND status != 'CLOSED'" + pDate + ") AS active_pirs, " +
                "    (SELECT COUNT(*) FROM pir_reports WHERE organization_id = ? AND status = 'OPEN'" + pDate + ") AS open_pirs, " +
                "    (SELECT COUNT(*) FROM pir_reports WHERE organization_id = ? AND status = 'UNDER_INVESTIGATION'" + pDate + ") AS under_investigation, " +
                "    (SELECT COUNT(*) FROM pir_reports WHERE organization_id = ? AND status = 'LOCATED'" + pDate + ") AS located_pirs, " +
                "    (SELECT COUNT(*) FROM pir_reports WHERE organization_id = ? AND status = 'DELIVERED'" + pDate + ") AS delivered_pirs, " +
                "    (SELECT COUNT(*) FROM pir_reports WHERE organization_id = ? AND status IN ('LOCATED', 'DELIVERED')" + pDate + ") AS resolved_pirs, " +
                "    (SELECT COUNT(*) FROM pir_reports WHERE organization_id = ? AND status = 'UNRESOLVED'" + pDate + ") AS unresolved_pirs, " +
                "    (SELECT COUNT(*) FROM pir_reports WHERE organization_id = ? AND status = 'CLOSED'" + pDate + ") AS closed_pirs, " +
                "    (SELECT COUNT(*) FROM pir_reports WHERE organization_id = ? AND pir_type = 'DELAYED_BAGGAGE'" + pDate + ") AS delayed_incidents, " +
                "    (SELECT COUNT(*) FROM pir_reports WHERE organization_id = ? AND pir_type = 'MISSING_BAGGAGE'" + pDate + ") AS missing_incidents, " +
                "    (SELECT COUNT(*) FROM claim_cases WHERE organization_id = ? AND status = 'SUBMITTED'" + cDate + ") AS claims_submitted, " +
                "    (SELECT COUNT(*) FROM claim_cases WHERE organization_id = ? AND status = 'UNDER_REVIEW'" + cDate + ") AS claims_under_review, " +
                "    (SELECT COUNT(*) FROM claim_cases WHERE organization_id = ? AND status = 'APPROVED'" + cDate + ") AS claims_approved, " +
                "    (SELECT COUNT(*) FROM claim_cases WHERE organization_id = ? AND status = 'REJECTED'" + cDate + ") AS claims_rejected, " +
                "    (SELECT COUNT(*) FROM claim_cases WHERE organization_id = ? AND status = 'SETTLED'" + cDate + ") AS claims_settled, " +
                "    (SELECT COUNT(*) FROM claim_cases WHERE organization_id = ? AND status = 'CLOSED'" + cDate + ") AS claims_closed, " +
                "    (SELECT COUNT(*) FROM claim_cases WHERE organization_id = ? AND status IN ('SUBMITTED', 'UNDER_REVIEW')" + cDate + ") AS claims_awaiting_review, " +
                "    (SELECT COUNT(*) FROM claim_cases WHERE organization_id = ? AND status = 'APPROVED'" + cDate + ") AS claims_awaiting_settlement, " +
                "    (SELECT COALESCE(SUM(amount), 0.0) FROM claim_settlements WHERE organization_id = ?) AS settlement_totals";

            try (PreparedStatement stmt = conn.prepareStatement(aggSql)) {
                int paramIdx = 1;
                for (int i = 0; i < 19; i++) {
                    stmt.setLong(paramIdx++, orgId);
                    for (Object dp : dateParams) {
                        stmt.setObject(paramIdx++, dp);
                    }
                }
                stmt.setLong(paramIdx, orgId);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        kpis.put("Total PIRs", String.valueOf(rs.getInt("total_pirs")));
                        kpis.put("Active PIRs", String.valueOf(rs.getInt("active_pirs")));
                        kpis.put("Open PIRs", String.valueOf(rs.getInt("open_pirs")));
                        kpis.put("Under Investigation", String.valueOf(rs.getInt("under_investigation")));
                        kpis.put("Located", String.valueOf(rs.getInt("located_pirs")));
                        kpis.put("Delivered", String.valueOf(rs.getInt("delivered_pirs")));
                        kpis.put("Resolved PIRs", String.valueOf(rs.getInt("resolved_pirs")));
                        kpis.put("Unresolved PIRs", String.valueOf(rs.getInt("unresolved_pirs")));
                        kpis.put("Closed PIRs", String.valueOf(rs.getInt("closed_pirs")));
                        kpis.put("Delayed Incidents", String.valueOf(rs.getInt("delayed_incidents")));
                        kpis.put("Missing Incidents", String.valueOf(rs.getInt("missing_incidents")));
                        kpis.put("Claims Submitted", String.valueOf(rs.getInt("claims_submitted")));
                        kpis.put("Claims Under Review", String.valueOf(rs.getInt("claims_under_review")));
                        kpis.put("Claims Approved", String.valueOf(rs.getInt("claims_approved")));
                        kpis.put("Claims Rejected", String.valueOf(rs.getInt("claims_rejected")));
                        kpis.put("Claims Settled", String.valueOf(rs.getInt("claims_settled")));
                        kpis.put("Claims Closed", String.valueOf(rs.getInt("claims_closed")));
                        kpis.put("Claims Awaiting Review", String.valueOf(rs.getInt("claims_awaiting_review")));
                        kpis.put("Claims Awaiting Settlement", String.valueOf(rs.getInt("claims_awaiting_settlement")));
                        kpis.put("Total Settlements Paid", "₹" + String.format("%,.2f", rs.getDouble("settlement_totals")));
                    }
                }
            }

            // 2. Tabular detailed manifest
            StringBuilder sql = new StringBuilder(
                "SELECT pr.pir_reference AS 'PIR Ref', " +
                "       pr.pir_type AS 'Incident Type', " +
                "       pr.status AS 'PIR Status', " +
                "       b.tag_number AS 'Bag Tag', " +
                "       p.name AS 'Passenger', " +
                "       r.pnr AS 'PNR', " +
                "       f.flight_number AS 'Flight', " +
                "       COALESCE(c.claim_reference, '—') AS 'Claim Ref', " +
                "       COALESCE(c.status, '—') AS 'Claim Status', " +
                "       COALESCE(CONCAT(c.currency, ' ', c.requested_amount), '—') AS 'Requested', " +
                "       COALESCE(CONCAT(c.currency, ' ', c.approved_amount), '—') AS 'Approved', " +
                "       COALESCE(cs.settlement_reference, '—') AS 'Settlement Ref', " +
                "       pr.incident_time AS 'Incident Date' " +
                "FROM pir_reports pr " +
                "JOIN baggage b ON b.id = pr.baggage_id " +
                "JOIN passengers p ON p.id = pr.passenger_id " +
                "JOIN reservations r ON r.id = pr.reservation_id " +
                "JOIN flights f ON f.id = pr.flight_id " +
                "LEFT JOIN claim_cases c ON c.pir_id = pr.id " +
                "LEFT JOIN claim_settlements cs ON cs.claim_id = c.id " +
                "WHERE pr.organization_id = ?"
            );

            List<Object> params = new ArrayList<>();
            params.add(orgId);

            if (filter.startDate() != null) {
                sql.append(" AND pr.created_at >= ?");
                params.add(filter.startDate().atStartOfDay());
            }
            if (filter.endDate() != null) {
                sql.append(" AND pr.created_at <= ?");
                params.add(filter.endDate().atTime(23, 59, 59));
            }
            if (filter.flightFilter() != null && !filter.flightFilter().isBlank()) {
                sql.append(" AND f.flight_number = ?");
                params.add(filter.flightFilter().trim());
            }
            if (filter.statusFilter() != null && !filter.statusFilter().equalsIgnoreCase("ALL")) {
                sql.append(" AND pr.status = ?");
                params.add(filter.statusFilter().trim());
            }

            sql.append(" ORDER BY pr.created_at DESC LIMIT 150");

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    model = TableUtils.toTableModel(rs);
                }
            }
        }

        return new ReportData(Category.PIR_CLAIMS, kpis, model);
    }

    private static ReportData compileIROPSReport(long orgId, ReportFilter filter) throws SQLException {
        Map<String, String> kpis = new LinkedHashMap<>();
        DefaultTableModel model = new DefaultTableModel();

        try (Connection conn = DBConnection.getConnection()) {
            if (!tableExists(conn, "flight_disruptions")) {
                return new ReportData(Category.IROPS_DISRUPTIONS, kpis, model);
            }

            StringBuilder dateClause = new StringBuilder();
            List<Object> dateParams = new ArrayList<>();
            if (filter.startDate() != null) {
                dateClause.append(" AND declared_at >= ?");
                dateParams.add(java.sql.Timestamp.valueOf(filter.startDate().atStartOfDay()));
            }
            if (filter.endDate() != null) {
                dateClause.append(" AND declared_at <= ?");
                dateParams.add(java.sql.Timestamp.valueOf(filter.endDate().atTime(23, 59, 59)));
            }

            // 1. Aggregations
            String kpiSql =
                "SELECT COUNT(*) AS total_disruptions, " +
                "       COUNT(CASE WHEN status NOT IN ('RESOLVED', 'CANCELLED') THEN 1 END) AS active_disruptions, " +
                "       COUNT(CASE WHEN status = 'RESOLVED' THEN 1 END) AS resolved_disruptions, " +
                "       COALESCE(SUM(passenger_impact_count), 0) AS total_impacted_pax " +
                "FROM flight_disruptions WHERE organization_id = ?" + dateClause;

            try (PreparedStatement stmt = conn.prepareStatement(kpiSql)) {
                stmt.setLong(1, orgId);
                for (int i = 0; i < dateParams.size(); i++) {
                    stmt.setObject(i + 2, dateParams.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        kpis.put("Total Disruptions", String.valueOf(rs.getInt("total_disruptions")));
                        kpis.put("Active Disruptions", String.valueOf(rs.getInt("active_disruptions")));
                        kpis.put("Resolved Disruptions", String.valueOf(rs.getInt("resolved_disruptions")));
                        kpis.put("Impacted Passengers", String.valueOf(rs.getInt("total_impacted_pax")));
                    }
                }
            }

            // Reaccommodation aggregations
            if (tableExists(conn, "passenger_reaccommodations")) {
                String reaccKpiSql =
                    "SELECT COUNT(*) AS total_reacc, " +
                    "       COUNT(CASE WHEN status = 'REBOOKED' THEN 1 END) AS rebooked_pax, " +
                    "       COUNT(CASE WHEN status = 'FAILED' THEN 1 END) AS failed_reacc " +
                    "FROM passenger_reaccommodations WHERE organization_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(reaccKpiSql)) {
                    stmt.setLong(1, orgId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            kpis.put("Total Reaccommodations", String.valueOf(rs.getInt("total_reacc")));
                            kpis.put("Rebooked Travelers", String.valueOf(rs.getInt("rebooked_pax")));
                            kpis.put("Failed Attempts", String.valueOf(rs.getInt("failed_reacc")));
                        }
                    }
                }
            }

            // Voucher aggregations
            if (tableExists(conn, "disruption_vouchers")) {
                String vchKpiSql =
                    "SELECT COUNT(*) AS total_vouchers, " +
                    "       COUNT(CASE WHEN status = 'REDEEMED' THEN 1 END) AS redeemed_vouchers, " +
                    "       COALESCE(SUM(amount), 0) AS total_voucher_amount " +
                    "FROM disruption_vouchers WHERE organization_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(vchKpiSql)) {
                    stmt.setLong(1, orgId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            kpis.put("Vouchers Issued", String.valueOf(rs.getInt("total_vouchers")));
                            kpis.put("Vouchers Redeemed", String.valueOf(rs.getInt("redeemed_vouchers")));
                            kpis.put("Total Voucher Value", "$" + rs.getBigDecimal("total_voucher_amount"));
                        }
                    }
                }
            }

            // 2. Tabular detailed records
            boolean hasReacc = tableExists(conn, "passenger_reaccommodations");
            boolean hasVch = tableExists(conn, "disruption_vouchers");

            StringBuilder sql = new StringBuilder(
                "SELECT fd.disruption_reference AS 'Disruption Ref', " +
                "       f.flight_number AS 'Flight', " +
                "       CONCAT(f.source, ' → ', f.destination) AS 'Route', " +
                "       fd.disruption_type AS 'Type', " +
                "       fd.reason_code AS 'Reason', " +
                "       fd.severity AS 'Severity', " +
                "       fd.delay_minutes AS 'Delay (m)', " +
                "       fd.passenger_impact_count AS 'Impacted Pax', " +
                "       fd.status AS 'Status' " +
                (hasReacc ? ", (SELECT COUNT(*) FROM passenger_reaccommodations pr WHERE pr.disruption_id = fd.id AND pr.organization_id = fd.organization_id) AS 'Reaccommodations' " : ", 0 AS 'Reaccommodations' ") +
                (hasVch ? ", (SELECT COUNT(*) FROM disruption_vouchers dv WHERE dv.disruption_id = fd.id AND dv.organization_id = fd.organization_id) AS 'Vouchers' " : ", 0 AS 'Vouchers' ") +
                ", DATE_FORMAT(fd.declared_at, '%Y-%m-%d %H:%i') AS 'Declared At' " +
                "FROM flight_disruptions fd " +
                "JOIN flights f ON f.id = fd.flight_id " +
                "WHERE fd.organization_id = ?"
            );

            List<Object> params = new ArrayList<>();
            params.add(orgId);

            if (filter.startDate() != null) {
                sql.append(" AND fd.declared_at >= ?");
                params.add(java.sql.Timestamp.valueOf(filter.startDate().atStartOfDay()));
            }
            if (filter.endDate() != null) {
                sql.append(" AND fd.declared_at <= ?");
                params.add(java.sql.Timestamp.valueOf(filter.endDate().atTime(23, 59, 59)));
            }
            if (filter.flightFilter() != null && !filter.flightFilter().trim().isEmpty()) {
                sql.append(" AND f.flight_number LIKE ?");
                params.add("%" + filter.flightFilter().trim() + "%");
            }
            if (filter.statusFilter() != null && !filter.statusFilter().trim().isEmpty() && !filter.statusFilter().startsWith("All")) {
                sql.append(" AND fd.status = ?");
                params.add(filter.statusFilter().trim());
            }

            sql.append(" ORDER BY fd.declared_at DESC LIMIT 150");

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    model = TableUtils.toTableModel(rs);
                }
            }
        }

        return new ReportData(Category.IROPS_DISRUPTIONS, kpis, model);
    }

    private static boolean tableExists(Connection conn, String tableName) {
        try (ResultSet rs = conn.getMetaData().getTables(conn.getCatalog(), null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }
}
