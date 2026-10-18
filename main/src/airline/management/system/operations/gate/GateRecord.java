package airline.management.system.operations.gate;

/**
 * Authoritative airport gate entity record.
 */
public record GateRecord(
    Long id,
    Long organizationId,
    String airportCode,
    String terminal,
    String gateNumber,
    String status
) {
    public Long gateId() {
        return id;
    }

    public String getDisplayName() {
        return "Gate " + gateNumber + " (" + terminal + ")";
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
