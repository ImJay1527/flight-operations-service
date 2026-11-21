package pt.isep.sidis.flightops.clients;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;
import pt.isep.sidis.flightops.common.exceptions.ServiceUnavailableException;
import pt.isep.sidis.flightops.common.metrics.FlightOpsMetrics;
import pt.isep.sidis.flightops.domain.AirportCopy;
import pt.isep.sidis.flightops.domain.RouteCopy;
import pt.isep.sidis.flightops.repositories.AirportCopyRepository;
import pt.isep.sidis.flightops.repositories.RouteCopyRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
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

/** The route and airport copies without Spring: the tables are maps, the REST API a mock. */
class LocalRouteCopyTest {

    private final Map<String, RouteCopy> routeTable = new HashMap<>();
    private final Map<String, AirportCopy> airportTable = new HashMap<>();
    private final RouteDirectory restApi = mock(RouteDirectory.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private RouteCopyRepository routes;
    private AirportCopyRepository airports;
    private PlatformTransactionManager transactions;
    private LocalRouteCopy copy;

    private final List<String> models = List.of("A320neo", "737 MAX");
    private final AirportInfo opo = new AirportInfo("OPO", "OPERATIONAL", models);
    private final AirportInfo lis = new AirportInfo("LIS", "OPERATIONAL", models);
    private final RouteInfo opoLis = new RouteInfo("route-opo-lis", "ACTIVE", 277, 350, 100, opo, lis);

    @BeforeEach
    void setUp() {
        routes = mock(RouteCopyRepository.class);
        when(routes.findById(anyString())).thenAnswer(i -> Optional.ofNullable(routeTable.get(i.<String>getArgument(0))));
        when(routes.save(any())).thenAnswer(i -> {
            RouteCopy r = i.getArgument(0);
            routeTable.put(r.getRouteId(), r);
            return r;
        });
        airports = mock(AirportCopyRepository.class);
        when(airports.findById(anyString()))
                .thenAnswer(i -> Optional.ofNullable(airportTable.get(i.<String>getArgument(0))));
        when(airports.save(any())).thenAnswer(i -> {
            AirportCopy a = i.getArgument(0);
            airportTable.put(a.getIataCode(), a);
            return a;
        });
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        copy = withMaxAge(Duration.ofHours(1));
    }

    private LocalRouteCopy withMaxAge(Duration maxAge) {
        return new LocalRouteCopy(routes, airports, restApi, transactions, new FlightOpsMetrics(meters),
                maxAge.toMillis());
    }

    private double lookups(String kind, String source) {
        return meters.counter(FlightOpsMetrics.ROUTE_LOOKUPS, "kind", kind, "source", source).count();
    }

    @Test
    void routeNotInTheCopyIsReadOnceWithItsAirportsAndKept() {
        when(restApi.getRoute("route-opo-lis")).thenReturn(opoLis);

        assertThat(copy.getRoute("route-opo-lis")).isEqualTo(opoLis);
        assertThat(copy.getRoute("route-opo-lis")).isEqualTo(opoLis);
        assertThat(copy.getAirport("lis")).isEqualTo(lis);   // came with the route

        verify(restApi).getRoute("route-opo-lis");
        verify(restApi, never()).getAirport(anyString());
        assertThat(lookups("route", "rest")).isEqualTo(1);
        assertThat(lookups("route", "copy")).isEqualTo(1);
    }

    @Test
    void routeAndAirportsFromEventsNeedNoRestCall() {
        Instant t = Instant.now();
        copy.airportCreated(opo, t);
        copy.airportCreated(lis, t);
        copy.routeCreated(new RouteInfo("route-opo-lis", "ACTIVE", 277, 350, 100,
                new AirportInfo("OPO", null, null), new AirportInfo("LIS", null, null)), t);

        assertThat(copy.getRoute("route-opo-lis")).isEqualTo(opoLis);
        verify(restApi, never()).getRoute(anyString());
    }

    @Test
    void aRouteWithoutItsAirportsInTheCopyIsReadFromTheRestApi() {
        copy.routeCreated(opoLis, Instant.now());   // airports not in the copy
        when(restApi.getRoute("route-opo-lis")).thenReturn(opoLis);

        assertThat(copy.getRoute("route-opo-lis")).isEqualTo(opoLis);
        verify(restApi).getRoute("route-opo-lis");
    }

    @Test
    void closuresDeactivationsAndCertificationsChangeTheCopyAndOlderEventsDont() {
        Instant t = Instant.now();
        when(restApi.getRoute("route-opo-lis")).thenReturn(opoLis);
        copy.getRoute("route-opo-lis");

        assertThat(copy.airportStatusChanged("LIS", "CLOSED", t.plusSeconds(10))).isTrue();
        assertThat(copy.airportStatusChanged("LIS", "OPERATIONAL", t.minusSeconds(10))).isFalse();   // late
        assertThat(copy.airportCertified("OPO", null, "777X", t.plusSeconds(10))).isTrue();
        assertThat(copy.routeDeactivated("route-opo-lis", t.plusSeconds(10))).isTrue();

        RouteInfo r = copy.getRoute("route-opo-lis");
        assertThat(r.status()).isEqualTo("DEACTIVATED");
        assertThat(r.destination().status()).isEqualTo("CLOSED");
        assertThat(r.origin().certifiedModels()).containsExactly("A320neo", "737 MAX", "777X");
    }

    @Test
    void changesOfWhatIsNotInTheCopyAreLeftForLater() {
        Instant t = Instant.now();
        assertThat(copy.airportStatusChanged("FAO", "CLOSED", t)).isFalse();
        assertThat(copy.routeDeactivated("route-x", t)).isFalse();
        assertThat(routeTable).isEmpty();
        assertThat(airportTable).isEmpty();
    }

    @Test
    void anOldCopyIsReadAgainOrUsedWhenTheRestApiIsDown() {
        copy = withMaxAge(Duration.ofMillis(-1));   // every copy is too old
        when(restApi.getRoute("route-opo-lis")).thenReturn(opoLis);
        copy.getRoute("route-opo-lis");
        assertThat(lookups("route", "rest")).isEqualTo(1);

        copy.getRoute("route-opo-lis");
        assertThat(lookups("route", "refreshed")).isEqualTo(1);

        when(restApi.getRoute("route-opo-lis")).thenThrow(new ServiceUnavailableException("down"));
        assertThat(copy.getRoute("route-opo-lis")).isEqualTo(opoLis);
        assertThat(lookups("route", "old_copy")).isEqualTo(1);
    }

    @Test
    void withoutACopyTheRestApiDecides() {
        when(restApi.getRoute("route-x")).thenThrow(new ServiceUnavailableException("down"));
        when(restApi.getAirport("XXX")).thenThrow(new ResourceNotFoundException("no such airport"));

        assertThatThrownBy(() -> copy.getRoute("route-x")).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> copy.getAirport("XXX")).isInstanceOf(ResourceNotFoundException.class);
    }
}
