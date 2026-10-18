package airline.management.system.audit;

import java.time.Instant;

/**
 * Immutable audit event record capturing operational staff and system events.
 * Strips and redacts any potential passwords, credit card numbers, or secret tokens.
 */
public record AuditEvent(
    Instant timestamp,
    Long userId,
    String username,
    String role,
    AuditAction action,
    String targetType,
    String targetId,
    String details,
    String clientIp
) {
    public AuditEvent {
        // Redact any accidentally passed sensitive strings
        details = sanitize(details);
    }

    private static String sanitize(String input) {
        if (input == null) return "";
        // Redact potential card numbers, passwords, or secret tokens
        return input.replaceAll("(?i)(password|secret|pwd|cvv)\\s*[:=]\\s*[^,;\\s]+", "$1=[REDACTED]")
                    .replaceAll("\\b(?:\\d[ -]*?){13,16}\\b", "[CARD_REDACTED]");
    }
}
