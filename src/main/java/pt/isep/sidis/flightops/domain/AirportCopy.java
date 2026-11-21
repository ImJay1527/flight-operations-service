package pt.isep.sidis.flightops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This instance's copy of an airport of the Airports &amp; Routes service: its status and the aircraft models it is
 * certified for. Events: airport.created, airport.status(.changed), airport.certification.added. See
 * {@link ReferenceCopy}.
 */
@Entity
@Table(name = "airport_copy")
public class AirportCopy extends ReferenceCopy {

    private static final String SEPARATOR = "\n";

    @Id
    private String iataCode;
    private String status;
    @Column(length = 2000)
    private String certifiedModels;     // one per line (model names have spaces: "737 MAX")

    protected AirportCopy() {
    }

    public AirportCopy(String iataCode) {
        this.iataCode = iataCode.toUpperCase();
    }

    /** The whole airport (airport.created, or a fresh read of the REST API); false if it is older than the copy. */
    public boolean replace(String status, List<String> models, Instant at, String from) {
        if (!accept(at, from)) {
            return false;
        }
        this.status = status;
        this.certifiedModels = models == null ? "" : String.join(SEPARATOR, models);
        return true;
    }

    public boolean changeStatus(String newStatus, Instant at) {
        if (!accept(at, "event")) {
            return false;
        }
        this.status = newStatus;
        return true;
    }

    /** airport.certification.added: the whole list if the event has it, otherwise the one model added. */
    public boolean certify(List<String> allModels, String addedModel, Instant at) {
        if (!accept(at, "event")) {
            return false;
        }
        List<String> models = allModels != null ? allModels : new ArrayList<>(getCertifiedModels());
        if (allModels == null && addedModel != null && !models.contains(addedModel)) {
            models.add(addedModel);
        }
        this.certifiedModels = String.join(SEPARATOR, models);
        return true;
    }

    public String getIataCode() {
        return iataCode;
    }

    public String getStatus() {
        return status;
    }

    public List<String> getCertifiedModels() {
        return certifiedModels == null || certifiedModels.isEmpty() ? List.of()
                : Arrays.asList(certifiedModels.split(SEPARATOR));
    }
}
