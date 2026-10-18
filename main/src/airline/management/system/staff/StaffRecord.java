package airline.management.system.staff;

import airline.management.system.security.Role;
import java.time.LocalDateTime;

/** Safe staff view model; password hashes are deliberately excluded. */
public record StaffRecord(long id, String username, Role role, boolean active, LocalDateTime createdAt) { }
