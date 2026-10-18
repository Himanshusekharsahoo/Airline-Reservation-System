package airline.management.system.ui.layout;

/**
 * Operational airport stations and flight dispatch hubs recognized by AeroDesk.
 */
public enum OperationalStation {
    DEL("DEL", "Indira Gandhi Intl", "Delhi, IND", "Primary Dispatch Hub"),
    MNL("MNL", "Ninoy Aquino Intl", "Manila, PHL", "Pacific Hub & Gate Center"),
    BOM("BOM", "Chhatrapati Shivaji Intl", "Mumbai, IND", "Western Operational Hub"),
    BLR("BLR", "Kempegowda Intl", "Bengaluru, IND", "Southern Regional Hub"),
    DXB("DXB", "Dubai International", "Dubai, UAE", "Middle East Gateway"),
    JFK("JFK", "John F. Kennedy Intl", "New York, USA", "Transatlantic Gateway");

    private final String code;
    private final String airportName;
    private final String city;
    private final String roleDescription;

    OperationalStation(String code, String airportName, String city, String roleDescription) {
        this.code = code;
        this.airportName = airportName;
        this.city = city;
        this.roleDescription = roleDescription;
    }

    public String getCode() {
        return code;
    }

    public String getAirportName() {
        return airportName;
    }

    public String getCity() {
        return city;
    }

    public String getRoleDescription() {
        return roleDescription;
    }

    @Override
    public String toString() {
        return code + " - " + airportName + " (" + city + ")";
    }
}
