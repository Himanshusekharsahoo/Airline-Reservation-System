package airline.management.system.security;

/**
 * Thrown when an authenticated operator attempts an operation for which their assigned role
 * lacks the necessary permissions.
 */
public class AccessDeniedException extends SecurityException {
    public AccessDeniedException(String message) {
        super(message);
    }

    public AccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
