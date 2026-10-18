package airline.management.system.operations.claim;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * State machine validator enforcing strictly permitted baggage claim lifecycle transitions.
 */
public final class ClaimLifecycleValidator {
    private static final Map<ClaimStatus, Set<ClaimStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(ClaimStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(ClaimStatus.SUBMITTED, EnumSet.of(ClaimStatus.UNDER_REVIEW));

        ALLOWED_TRANSITIONS.put(ClaimStatus.UNDER_REVIEW, EnumSet.of(
            ClaimStatus.APPROVED,
            ClaimStatus.REJECTED
        ));

        ALLOWED_TRANSITIONS.put(ClaimStatus.APPROVED, EnumSet.of(ClaimStatus.SETTLED));

        ALLOWED_TRANSITIONS.put(ClaimStatus.REJECTED, EnumSet.of(ClaimStatus.CLOSED));

        ALLOWED_TRANSITIONS.put(ClaimStatus.SETTLED, EnumSet.of(
            ClaimStatus.CLOSED
        ));

        // Terminal state: CLOSED cannot transition to any other status
        ALLOWED_TRANSITIONS.put(ClaimStatus.CLOSED, Collections.emptySet());
    }

    private ClaimLifecycleValidator() {}

    /**
     * Validates whether a transition from {@code current} to {@code next} is permitted.
     * Throws {@link IllegalStateException} if prohibited.
     */
    public static void validateTransition(ClaimStatus current, ClaimStatus next) {
        if (current == null || next == null) {
            throw new IllegalArgumentException("Claim statuses cannot be null.");
        }
        if (current == next) {
            return; // No-op
        }
        Set<ClaimStatus> permitted = ALLOWED_TRANSITIONS.getOrDefault(current, Collections.emptySet());
        if (!permitted.contains(next)) {
            throw new IllegalStateException(
                "Invalid claim lifecycle transition: cannot transition from " +
                current.name() + " (" + current.getDisplayName() + ") to " +
                next.name() + " (" + next.getDisplayName() + ")."
            );
        }
    }

    /**
     * Returns the set of valid next statuses for a given current status.
     */
    public static Set<ClaimStatus> getAvailableNextStates(ClaimStatus current) {
        if (current == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(ALLOWED_TRANSITIONS.getOrDefault(current, Collections.emptySet()));
    }
}
