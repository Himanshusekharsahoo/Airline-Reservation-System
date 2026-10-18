package airline.management.system.staff;

import airline.management.system.DBConnection;
import airline.management.system.PasswordUtils;
import airline.management.system.UserSession;
import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.security.Role;
import airline.management.system.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Authoritative staff account administration. UI code never handles password hashes. */
public final class StaffService {
    private StaffService() { }

    public static List<StaffRecord> find(String query, String roleFilter, Boolean activeFilter) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_USERS);
        StringBuilder sql = new StringBuilder("SELECT id, username, role, active, created_at FROM app_users WHERE 1=1");
        List<Object> values = new ArrayList<>();
        if (query != null && !query.isBlank()) { sql.append(" AND username LIKE ?"); values.add("%" + query.trim() + "%"); }
        if (roleFilter != null && !roleFilter.isBlank() && !"ALL".equals(roleFilter)) { sql.append(" AND role=?"); values.add(requireRole(roleFilter).name()); }
        if (activeFilter != null) { sql.append(" AND active=?"); values.add(activeFilter); }
        sql.append(" ORDER BY username");
        List<StaffRecord> result = new ArrayList<>();
        try (Connection c = DBConnection.getConnection(); PreparedStatement p = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < values.size(); i++) p.setObject(i + 1, values.get(i));
            try (ResultSet r = p.executeQuery()) { while (r.next()) {
                Timestamp created = r.getTimestamp("created_at");
                result.add(new StaffRecord(r.getLong("id"), r.getString("username"), Role.fromString(r.getString("role")), r.getBoolean("active"), created == null ? null : created.toLocalDateTime()));
            }}
        }
        return result;
    }

    /** Takes ownership of {@code password} and clears it after hashing, including on failure. */
    public static StaffRecord create(String username, char[] password, String roleValue) throws SQLException {
        try {
            AuthorizationService.requirePermission(Permission.MANAGE_USERS);
            String name = requireUsername(username); Role role = requireRole(roleValue);
            if (password == null || password.length < 8) throw new IllegalArgumentException("A staff password must contain at least 8 characters.");
            try (Connection c = DBConnection.getConnection()) {
                c.setAutoCommit(false);
                try {
                    long id;
                    try (PreparedStatement p = c.prepareStatement("INSERT INTO app_users(username,password_hash,role,active) VALUES(?,?,?,TRUE)", Statement.RETURN_GENERATED_KEYS)) {
                        p.setString(1, name); p.setString(2, PasswordUtils.hash(password)); p.setString(3, role.name()); p.executeUpdate();
                        try (ResultSet keys = p.getGeneratedKeys()) { if (!keys.next()) throw new SQLException("Staff account ID was not generated."); id = keys.getLong(1); }
                    }
                    assignRoleRow(c, id, role);
                    c.commit();
                    StaffRecord record = new StaffRecord(id, name, role, true, LocalDateTime.now());
                    AuditService.record(AuditAction.STAFF_CREATED, "STAFF", String.valueOf(id), "Created staff account [" + name + "] with role [" + role.name() + "]");
                    return record;
                } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
                finally { c.setAutoCommit(true); }
            }
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    public static void changeRole(long staffId, String roleValue) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_USERS);
        Role newRole = requireRole(roleValue);
        try (Connection c = DBConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                StaffRecord old = lockStaff(c, staffId);
                if (old == null) throw new IllegalArgumentException("Staff member was not found.");
                if (old.role() == newRole) { c.rollback(); return; }
                if (old.active() && isAdministrator(old.role()) && !isAdministrator(newRole) && activeAdministratorCount(c) <= 1) throw new IllegalStateException("The last active administrator cannot be removed.");
                try (PreparedStatement p = c.prepareStatement("UPDATE app_users SET role=? WHERE id=?")) { p.setString(1, newRole.name()); p.setLong(2, staffId); p.executeUpdate(); }
                try (PreparedStatement p = c.prepareStatement("DELETE FROM user_roles WHERE user_id=? AND organization_id=?")) { p.setLong(1, staffId); p.setLong(2, TenantContext.getOrganizationId() == null ? 1L : TenantContext.getOrganizationId()); p.executeUpdate(); }
                assignRoleRow(c, staffId, newRole); c.commit();
                AuditService.record(old.role() == null ? AuditAction.ROLE_ASSIGNED : AuditAction.ROLE_CHANGED, "STAFF", String.valueOf(staffId), "Role changed from [" + old.role().name() + "] to [" + newRole.name() + "]");
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        }
    }

    public static void setActive(long staffId, boolean active) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_USERS);
        if (!active && UserSession.userId() != null && UserSession.userId() == staffId) throw new IllegalStateException("You cannot disable your currently signed-in account.");
        try (Connection c = DBConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                StaffRecord staff = lockStaff(c, staffId);
                if (staff == null) throw new IllegalArgumentException("Staff member was not found.");
                if (staff.active() == active) { c.rollback(); return; }
                if (!active && isAdministrator(staff.role()) && activeAdministratorCount(c) <= 1) throw new IllegalStateException("The last active administrator cannot be disabled.");
                try (PreparedStatement p = c.prepareStatement("UPDATE app_users SET active=? WHERE id=?")) { p.setBoolean(1, active); p.setLong(2, staffId); p.executeUpdate(); }
                c.commit();
                AuditService.record(active ? AuditAction.STAFF_ENABLED : AuditAction.STAFF_DISABLED, "STAFF", String.valueOf(staffId), (active ? "Enabled" : "Disabled") + " staff account [" + staff.username() + "]");
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        }
    }

    /** Takes ownership of {@code password} and clears it after hashing, including on failure. */
    public static void resetPassword(long staffId, char[] password) throws SQLException {
        try {
            AuthorizationService.requirePermission(Permission.MANAGE_USERS);
            if (password == null || password.length < 8) throw new IllegalArgumentException("A staff password must contain at least 8 characters.");
            try (Connection c = DBConnection.getConnection(); PreparedStatement p = c.prepareStatement("UPDATE app_users SET password_hash=? WHERE id=?")) {
                p.setString(1, PasswordUtils.hash(password)); p.setLong(2, staffId);
                if (p.executeUpdate() == 0) throw new IllegalArgumentException("Staff member was not found.");
            }
            AuditService.record(AuditAction.STAFF_PERMISSION_CHANGE, "STAFF", String.valueOf(staffId), "Staff password reset without recording credential data");
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    public static List<String> auditHistory(long staffId) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_USERS);
        List<String> history = new ArrayList<>();
        try (Connection c = DBConnection.getConnection(); PreparedStatement p = c.prepareStatement(
                "SELECT action,details,created_at FROM audit_logs WHERE organization_id=? AND target_type='STAFF' AND target_id=? ORDER BY id DESC LIMIT 50")) {
            p.setLong(1, TenantContext.getOrganizationId() == null ? 1L : TenantContext.getOrganizationId()); p.setString(2, String.valueOf(staffId));
            try (ResultSet r = p.executeQuery()) { while (r.next()) history.add(r.getTimestamp(3) + "  " + r.getString(1) + " — " + r.getString(2)); }
        }
        return history;
    }

    private static StaffRecord lockStaff(Connection c, long id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT id,username,role,active,created_at FROM app_users WHERE id=? FOR UPDATE")) { p.setLong(1, id); try (ResultSet r = p.executeQuery()) { if (!r.next()) return null; Timestamp t=r.getTimestamp("created_at"); return new StaffRecord(r.getLong(1),r.getString(2),Role.fromString(r.getString(3)),r.getBoolean(4),t==null?null:t.toLocalDateTime()); }}
    }
    private static int activeAdministratorCount(Connection c) throws SQLException { try (PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM app_users WHERE active=TRUE AND role IN ('SUPERADMIN','AIRLINEADMIN','ADMIN')" ); ResultSet r=p.executeQuery()) { r.next(); return r.getInt(1); } }
    private static void assignRoleRow(Connection c, long userId, Role role) throws SQLException { try (PreparedStatement p=c.prepareStatement("INSERT IGNORE INTO user_roles(user_id,role_id,organization_id) SELECT ?,id,? FROM roles WHERE name=?")) { p.setLong(1,userId); p.setLong(2,TenantContext.getOrganizationId()==null?1L:TenantContext.getOrganizationId()); p.setString(3,role.name()); if (p.executeUpdate()==0) throw new IllegalStateException("The selected role is not configured in the database."); } }
    private static String requireUsername(String value) { String name=value==null?"":value.trim(); if (!name.matches("[A-Za-z0-9._-]{3,50}")) throw new IllegalArgumentException("Username must contain 3-50 letters, numbers, dots, underscores, or hyphens."); return name; }
    private static Role requireRole(String value) { try { return Role.valueOf(value==null?"":value.trim().toUpperCase()); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Select a valid staff role."); } }
    private static boolean isAdministrator(Role role) { return role == Role.SUPERADMIN || role == Role.AIRLINEADMIN; }
}
