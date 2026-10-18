package airline.management.system.operations.baggage;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Centralized state machine validator enforcing strictly permitted baggage status transitions.
 */
public final class BaggageLifecycleValidator {
    private static final Map<BaggageStatus, Set<BaggageStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(BaggageStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(BaggageStatus.REGISTERED, EnumSet.of(
            BaggageStatus.ACCEPTED,
            BaggageStatus.CANCELLED
        ));

        ALLOWED_TRANSITIONS.put(BaggageStatus.ACCEPTED, EnumSet.of(
            BaggageStatus.LOADED,
            BaggageStatus.DELAYED,
            BaggageStatus.MISSING,
            BaggageStatus.CANCELLED
        ));

        ALLOWED_TRANSITIONS.put(BaggageStatus.LOADED, EnumSet.of(
            BaggageStatus.IN_TRANSIT,
            BaggageStatus.DELAYED,
            BaggageStatus.MISSING
        ));

        ALLOWED_TRANSITIONS.put(BaggageStatus.IN_TRANSIT, EnumSet.of(
            BaggageStatus.DELIVERED,
            BaggageStatus.DELAYED,
            BaggageStatus.MISSING
        ));

        ALLOWED_TRANSITIONS.put(BaggageStatus.DELAYED, EnumSet.of(
            BaggageStatus.ACCEPTED,
            BaggageStatus.LOADED,
            BaggageStatus.DELIVERED,
            BaggageStatus.MISSING
        ));

        ALLOWED_TRANSITIONS.put(BaggageStatus.MISSING, EnumSet.of(
            BaggageStatus.ACCEPTED,
            BaggageStatus.DELIVERED
        ));

        // Terminal states
        ALLOWED_TRANSITIONS.put(BaggageStatus.DELIVERED, Collections.emptySet());
        ALLOWED_TRANSITIONS.put(BaggageStatus.CANCELLED, Collections.emptySet());
    }

    private BaggageLifecycleValidator() {}

    /**
     * Validates whether a transition from {@code current} to {@code next} is permitted.
     * Throws {@link IllegalStateException} if the transition is prohibited.
     */
    public static void validateTransition(BaggageStatus current, BaggageStatus next) {
        if (current == null || next == null) {
            throw new IllegalArgumentException("Baggage statuses cannot be null.");
        }
        if (current == next) {
            return; // No-op transition is benign
        }
        Set<BaggageStatus> permitted = ALLOWED_TRANSITIONS.getOrDefault(current, Collections.emptySet());
        if (!permitted.contains(next)) {
            throw new IllegalStateException(
                "Invalid baggage lifecycle transition: cannot transition from " +
                current.name() + " (" + current.getDisplayName() + ") to " +
                next.name() + " (" + next.getDisplayName() + ")."
            );
        }
    }

    /**
     * Returns the set of valid next statuses for a given current status.
     */
    public static Set<BaggageStatus> getAvailableNextStates(BaggageStatus current) {
        if (current == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(ALLOWED_TRANSITIONS.getOrDefault(current, Collections.emptySet()));
    }
}
