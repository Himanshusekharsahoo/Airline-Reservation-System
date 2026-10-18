package airline.management.system.ui.layout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Shared state governing the active operational station/hub context across AeroDesk views.
 */
public final class StationContext {
    private static OperationalStation currentStation = OperationalStation.DEL;
    private static final List<Consumer<OperationalStation>> listeners = new CopyOnWriteArrayList<>();

    private StationContext() {}

    public static OperationalStation getCurrentStation() {
        return currentStation;
    }

    public static void setStation(OperationalStation station) {
        if (station != null && station != currentStation) {
            currentStation = station;
            notifyListeners(currentStation);
        }
    }

    public static OperationalStation findByCode(String code) {
        if (code == null || code.isBlank()) {
            return OperationalStation.DEL;
        }
        for (OperationalStation s : OperationalStation.values()) {
            if (s.getCode().equalsIgnoreCase(code.trim())) {
                return s;
            }
        }
        return OperationalStation.DEL;
    }

    public static java.util.Optional<OperationalStation> findStation(String code) {
        if (code == null || code.isBlank()) {
            return java.util.Optional.empty();
        }
        for (OperationalStation s : OperationalStation.values()) {
            if (s.getCode().equalsIgnoreCase(code.trim())) {
                return java.util.Optional.of(s);
            }
        }
        return java.util.Optional.empty();
    }

    public static void addListener(Consumer<OperationalStation> listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void addStationChangeListener(Consumer<OperationalStation> listener) {
        addListener(listener);
    }

    public static void removeListener(Consumer<OperationalStation> listener) {
        listeners.remove(listener);
    }

    public static void removeStationChangeListener(Consumer<OperationalStation> listener) {
        removeListener(listener);
    }

    private static void notifyListeners(OperationalStation station) {
        if (SwingUtilities.isEventDispatchThread()) {
            for (Consumer<OperationalStation> listener : listeners) {
                try {
                    listener.accept(station);
                } catch (Exception ignored) {}
            }
        } else {
            SwingUtilities.invokeLater(() -> {
                for (Consumer<OperationalStation> listener : listeners) {
                    try {
                        listener.accept(station);
                    } catch (Exception ignored) {}
                }
            });
        }
    }
}
