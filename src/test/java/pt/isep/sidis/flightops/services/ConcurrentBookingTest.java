package pt.isep.sidis.flightops.services;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two bookings for the SAME aircraft in the SAME time window, sent to the same instance at the same moment:
 * exactly one may succeed (docs/architecture.md, "Consistency model"). Real database (H2), real transactions.
 */
@SpringBootTest
@ActiveProfiles("stub")
class ConcurrentBookingTest {

    private static final int ROUNDS = 20;

    @Autowired
    ScheduledFlightService service;

    @Test
    void simultaneousOverlappingBookingsForOneAircraftOnlyOneSucceeds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> successesPerRound = new ArrayList<>();
        try {
            for (int round = 0; round < ROUNDS; round++) {
                // a fresh window per round, far in the future (no sample flights there)
                LocalDateTime departure = LocalDateTime.now().plusDays(1000 + round * 10L).withNano(0);
                LocalDateTime arrival = departure.plusMinutes(45);

                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> attempts = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    attempts.add(pool.submit(() -> {
                        start.await();
                        try {
                            service.scheduleFlight("route-opo-lis", "CS-TPA", departure, arrival);
                            return true;
                        } catch (IllegalStateException alreadyScheduled) {
                            return false;
                        }
                    }));
                }
                start.countDown();   // both threads go at the same moment

                int successes = 0;
                for (Future<Boolean> attempt : attempts) {
                    if (attempt.get()) {
                        successes++;
                    }
                }
                successesPerRound.add(successes);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(successesPerRound)
                .as("successful bookings per round (each round: 2 simultaneous overlapping bookings)")
                .containsOnly(1);
    }
}
