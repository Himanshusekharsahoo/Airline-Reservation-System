package airline.management.system.operations.pir;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * State machine validator enforcing strictly permitted Property Irregularity Report lifecycle transitions.
 */
public final class PIRLifecycleValidator {
    private static final Map<PIRStatus, Set<PIRStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(PIRStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(PIRStatus.OPEN, EnumSet.of(
            PIRStatus.UNDER_INVESTIGATION,
            PIRStatus.CLOSED
        ));

        ALLOWED_TRANSITIONS.put(PIRStatus.UNDER_INVESTIGATION, EnumSet.of(
            PIRStatus.LOCATED,
            PIRStatus.UNRESOLVED,
            PIRStatus.CLOSED
        ));

        ALLOWED_TRANSITIONS.put(PIRStatus.LOCATED, EnumSet.of(
            PIRStatus.DELIVERED,
            PIRStatus.UNDER_INVESTIGATION
        ));

        ALLOWED_TRANSITIONS.put(PIRStatus.DELIVERED, EnumSet.of(
            PIRStatus.CLOSED
        ));

        ALLOWED_TRANSITIONS.put(PIRStatus.UNRESOLVED, EnumSet.of(
            PIRStatus.UNDER_INVESTIGATION,
            PIRStatus.CLOSED
        ));

        // Terminal state: CLOSED cannot transition to any other status
        ALLOWED_TRANSITIONS.put(PIRStatus.CLOSED, Collections.emptySet());
    }

    private PIRLifecycleValidator() {}

    /**
     * Validates whether a transition from {@code current} to {@code next} is permitted.
     * Throws {@link IllegalStateException} if prohibited.
     */
    public static void validateTransition(PIRStatus current, PIRStatus next) {
        if (current == null || next == null) {
            throw new IllegalArgumentException("PIR statuses cannot be null.");
        }
        if (current == next) {
            return; // No-op
        }
        Set<PIRStatus> permitted = ALLOWED_TRANSITIONS.getOrDefault(current, Collections.emptySet());
        if (!permitted.contains(next)) {
            throw new IllegalStateException(
                "Invalid PIR lifecycle transition: cannot transition from " +
                current.name() + " (" + current.getDisplayName() + ") to " +
                next.name() + " (" + next.getDisplayName() + ")."
            );
        }
    }

    /**
     * Returns the set of valid next statuses for a given current status.
     */
    public static Set<PIRStatus> getAvailableNextStates(PIRStatus current) {
        if (current == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(ALLOWED_TRANSITIONS.getOrDefault(current, Collections.emptySet()));
    }
}
