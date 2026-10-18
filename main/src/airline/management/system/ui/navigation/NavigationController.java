package airline.management.system.ui.navigation;

import airline.management.system.UserSession;
import airline.management.system.security.Role;

import java.awt.CardLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;

public class NavigationController {
    public interface NavigationListener {
        void onNavigate(NavRoute route, Object parameter);
    }

    private final JPanel cardsPanel;
    private final CardLayout cardLayout;
    private final List<NavigationListener> listeners = new ArrayList<>();
    private NavRoute currentRoute = NavRoute.DASHBOARD;

    public NavigationController(JPanel cardsPanel, CardLayout cardLayout) {
        this.cardsPanel = cardsPanel;
        this.cardLayout = cardLayout;
    }

    public void addListener(NavigationListener listener) {
        listeners.add(listener);
    }

    public void navigateTo(NavRoute route) {
        navigateTo(route, null);
    }

    public void navigateTo(NavRoute route, Object parameter) {
        if (route == null) {
            return;
        }

        // Authorization check: if authenticated and unauthorized, reject navigation
        if (UserSession.isAuthenticated() && !NavigationAccessController.isRouteAccessible(route)) {
            Role currentRole = UserSession.getRole();
            NavRoute fallback = NavigationAccessController.getDefaultRoute(currentRole);
            if (fallback != route && fallback != currentRoute) {
                navigateTo(fallback, parameter);
            }
            return;
        }

        this.currentRoute = route;
        cardLayout.show(cardsPanel, route.getKey());
        for (NavigationListener listener : listeners) {
            listener.onNavigate(route, parameter);
        }
    }

    public NavRoute getCurrentRoute() {
        return currentRoute;
    }
}
