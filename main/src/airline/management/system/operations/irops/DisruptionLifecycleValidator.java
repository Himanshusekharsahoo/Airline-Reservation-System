package airline.management.system.operations.irops;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * State machine validator enforcing strictly permitted flight disruption lifecycle transitions.
 */
public final class DisruptionLifecycleValidator {

    private static final Map<DisruptionStatus, Set<DisruptionStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(DisruptionStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(DisruptionStatus.DECLARED, EnumSet.of(
            DisruptionStatus.INVESTIGATING,
            DisruptionStatus.REACCOMMODATING,
            DisruptionStatus.CANCELLED
        ));

        ALLOWED_TRANSITIONS.put(DisruptionStatus.INVESTIGATING, EnumSet.of(
            DisruptionStatus.REACCOMMODATING,
            DisruptionStatus.MITIGATED,
            DisruptionStatus.CANCELLED
        ));

        ALLOWED_TRANSITIONS.put(DisruptionStatus.REACCOMMODATING, EnumSet.of(
            DisruptionStatus.MITIGATED,
            DisruptionStatus.RESOLVED
        ));

        ALLOWED_TRANSITIONS.put(DisruptionStatus.MITIGATED, EnumSet.of(
            DisruptionStatus.RESOLVED,
            DisruptionStatus.REACCOMMODATING
        ));

        // Terminal states: RESOLVED and CANCELLED cannot transition to any other state
        ALLOWED_TRANSITIONS.put(DisruptionStatus.RESOLVED, Collections.emptySet());
        ALLOWED_TRANSITIONS.put(DisruptionStatus.CANCELLED, Collections.emptySet());
    }

    private DisruptionLifecycleValidator() {}

    /**
     * Validates whether a transition from current to next is permitted.
     * Throws IllegalStateException if prohibited.
     */
    public static void validateTransition(DisruptionStatus current, DisruptionStatus next) {
        if (current == null || next == null) {
            throw new IllegalArgumentException("Disruption statuses cannot be null.");
        }
        if (current == next) {
            return; // No-op
        }
        Set<DisruptionStatus> permitted = ALLOWED_TRANSITIONS.getOrDefault(current, Collections.emptySet());
        if (!permitted.contains(next)) {
            throw new IllegalStateException(
                "Invalid disruption lifecycle transition: cannot transition from " +
                current.name() + " (" + current.getDisplayName() + ") to " +
                next.name() + " (" + next.getDisplayName() + ")."
            );
        }
    }

    /**
     * Returns the unmodifiable set of valid next states for a given current status.
     */
    public static Set<DisruptionStatus> getAvailableNextStates(DisruptionStatus current) {
        if (current == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(ALLOWED_TRANSITIONS.getOrDefault(current, Collections.emptySet()));
    }
}
