package pt.isep.sidis.flightops.readmodel;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import pt.isep.sidis.flightops.events.FlightChanged;

/**
 * Keeps the read model up to date with the write side's changes on this instance: projects each
 * {@link FlightChanged} once its transaction has committed (never a change that is rolled back), or at once if it
 * was published outside a transaction. Runs in the request's thread, so the client that made a change reads it
 * back from this instance straight away (read your own writes).
 */
@Component
public class ReadModelProjection {

    private final ReadModel readModel;

    public ReadModelProjection(ReadModel readModel) {
        this.readModel = readModel;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void on(FlightChanged event) {
        readModel.project(event.flight());
    }
}
