package airline.management.system.operations.irops;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * State machine validator enforcing strictly permitted passenger re-accommodation transitions.
 */
public final class ReaccommodationLifecycleValidator {

    private static final Map<ReaccommodationStatus, Set<ReaccommodationStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(ReaccommodationStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(ReaccommodationStatus.PENDING, EnumSet.of(
            ReaccommodationStatus.SEARCHING,
            ReaccommodationStatus.OFFERED,
            ReaccommodationStatus.REBOOKED,
            ReaccommodationStatus.FAILED,
            ReaccommodationStatus.CANCELLED
        ));

        ALLOWED_TRANSITIONS.put(ReaccommodationStatus.SEARCHING, EnumSet.of(
            ReaccommodationStatus.OFFERED,
            ReaccommodationStatus.REBOOKED,
            ReaccommodationStatus.FAILED,
            ReaccommodationStatus.CANCELLED
        ));

        ALLOWED_TRANSITIONS.put(ReaccommodationStatus.OFFERED, EnumSet.of(
            ReaccommodationStatus.ACCEPTED,
            ReaccommodationStatus.DECLINED,
            ReaccommodationStatus.REBOOKED,
            ReaccommodationStatus.FAILED,
            ReaccommodationStatus.CANCELLED
        ));

        ALLOWED_TRANSITIONS.put(ReaccommodationStatus.ACCEPTED, EnumSet.of(
            ReaccommodationStatus.REBOOKED,
            ReaccommodationStatus.DECLINED,
            ReaccommodationStatus.FAILED,
            ReaccommodationStatus.CANCELLED
        ));

        // Allow FAILED to be retried into SEARCHING or cancelled
        ALLOWED_TRANSITIONS.put(ReaccommodationStatus.FAILED, EnumSet.of(
            ReaccommodationStatus.SEARCHING,
            ReaccommodationStatus.CANCELLED
        ));

        // Terminal states
        ALLOWED_TRANSITIONS.put(ReaccommodationStatus.REBOOKED, Collections.emptySet());
        ALLOWED_TRANSITIONS.put(ReaccommodationStatus.DECLINED, Collections.emptySet());
        ALLOWED_TRANSITIONS.put(ReaccommodationStatus.CANCELLED, Collections.emptySet());
    }

    private ReaccommodationLifecycleValidator() {}

    /**
     * Validates whether a transition from current to next is permitted.
     * Throws IllegalStateException if prohibited.
     */
    public static void validateTransition(ReaccommodationStatus current, ReaccommodationStatus next) {
        if (current == null || next == null) {
            throw new IllegalArgumentException("Reaccommodation statuses cannot be null.");
        }
        if (current == next) {
            return; // No-op
        }
        Set<ReaccommodationStatus> permitted = ALLOWED_TRANSITIONS.getOrDefault(current, Collections.emptySet());
        if (!permitted.contains(next)) {
            throw new IllegalStateException(
                "Invalid reaccommodation transition: cannot transition from " +
                current.name() + " (" + current.getDisplayName() + ") to " +
                next.name() + " (" + next.getDisplayName() + ")."
            );
        }
    }

    public static Set<ReaccommodationStatus> getAvailableNextStates(ReaccommodationStatus current) {
        if (current == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(ALLOWED_TRANSITIONS.getOrDefault(current, Collections.emptySet()));
    }
}
