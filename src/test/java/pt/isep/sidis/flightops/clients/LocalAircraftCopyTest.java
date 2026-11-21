package pt.isep.sidis.flightops.clients;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;
import pt.isep.sidis.flightops.common.exceptions.ServiceUnavailableException;
import pt.isep.sidis.flightops.common.metrics.FlightOpsMetrics;
import pt.isep.sidis.flightops.domain.AircraftCopy;
import pt.isep.sidis.flightops.repositories.AircraftCopyRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The aircraft copy without Spring: the table is a map, the REST API a mock. */
class LocalAircraftCopyTest {

    private final Map<String, AircraftCopy> table = new HashMap<>();
    private final AircraftDirectory restApi = mock(AircraftDirectory.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private AircraftCopyRepository repository;
    private PlatformTransactionManager transactions;
    private LocalAircraftCopy copy;

    private final AircraftInfo tpa = new AircraftInfo("CS-TPA", "AVAILABLE", "A320neo", 6300, 24000, 160);

    @BeforeEach
    void setUp() {
        repository = mock(AircraftCopyRepository.class);
        when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(table.get(i.<String>getArgument(0))));
        when(repository.save(any())).thenAnswer(i -> {
            AircraftCopy c = i.getArgument(0);
            table.put(c.getRegistration(), c);
            return c;
        });
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        copy = withMaxAge(Duration.ofHours(1));
    }

    private LocalAircraftCopy withMaxAge(Duration maxAge) {
        return new LocalAircraftCopy(repository, restApi, transactions, new FlightOpsMetrics(meters), maxAge.toMillis());
    }

    private double lookups(String source) {
        return meters.counter(FlightOpsMetrics.AIRCRAFT_LOOKUPS, "source", source).count();
    }

    @Test
    void aircraftNotInTheCopyIsReadFromTheRestApiOnceAndKept() {
        when(restApi.getAircraft("CS-TPA")).thenReturn(tpa);

        assertThat(copy.getAircraft("CS-TPA")).isEqualTo(tpa);
        assertThat(copy.getAircraft("cs-tpa")).isEqualTo(tpa);

        verify(restApi).getAircraft("CS-TPA");   // only the first time
        assertThat(table.get("CS-TPA").getSource()).isEqualTo("rest");
        assertThat(lookups("rest")).isEqualTo(1);
        assertThat(lookups("copy")).isEqualTo(1);
    }

    @Test
    void anAircraftFromAnEventNeedsNoRestCall() {
        assertThat(copy.created(tpa, Instant.now())).isTrue();

        assertThat(copy.getAircraft("CS-TPA")).isEqualTo(tpa);
        verify(restApi, never()).getAircraft(anyString());
    }

    @Test
    void statusChangeReachesTheBookingsAndAnOlderOneIsIgnored() {
        Instant t = Instant.now();
        copy.created(tpa, t);

        assertThat(copy.statusChanged("CS-TPA", "UNDER_MAINTENANCE", t.plusSeconds(10))).isTrue();
        assertThat(copy.statusChanged("CS-TPA", "AVAILABLE", t.plusSeconds(5))).isFalse();     // late
        assertThat(copy.created(tpa, t)).isFalse();                                            // duplicate, old

        assertThat(copy.getAircraft("CS-TPA").status()).isEqualTo("UNDER_MAINTENANCE");
    }

    @Test
    void statusChangeOfAnAircraftNotInTheCopyChangesNothing() {
        assertThat(copy.statusChanged("CS-TPZ", "INACTIVE", Instant.now())).isFalse();
        assertThat(table).isEmpty();
    }

    @Test
    void anOldCopyIsReadAgain() {
        copy = withMaxAge(Duration.ofMillis(-1));   // every copy is too old
        copy.created(tpa, Instant.now());
        AircraftInfo grounded = new AircraftInfo("CS-TPA", "INACTIVE", "A320neo", 6300, 24000, 160);
        when(restApi.getAircraft("CS-TPA")).thenReturn(grounded);

        assertThat(copy.getAircraft("CS-TPA")).isEqualTo(grounded);
        assertThat(table.get("CS-TPA").getStatus()).isEqualTo("INACTIVE");
        assertThat(lookups("refreshed")).isEqualTo(1);
    }

    @Test
    void anOldCopyIsUsedWhenTheRestApiIsDown() {
        copy = withMaxAge(Duration.ofMillis(-1));
        copy.created(tpa, Instant.now());
        when(restApi.getAircraft("CS-TPA")).thenThrow(new ServiceUnavailableException("down"));

        assertThat(copy.getAircraft("CS-TPA")).isEqualTo(tpa);
        assertThat(lookups("old_copy")).isEqualTo(1);
    }

    @Test
    void withoutACopyTheRestApiDecides() {
        when(restApi.getAircraft("CS-TPX")).thenThrow(new ServiceUnavailableException("down"));
        when(restApi.getAircraft("CS-NOPE")).thenThrow(new ResourceNotFoundException("no such aircraft"));

        assertThatThrownBy(() -> copy.getAircraft("CS-TPX")).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> copy.getAircraft("CS-NOPE")).isInstanceOf(ResourceNotFoundException.class);
        assertThat(table).isEmpty();
    }
}
