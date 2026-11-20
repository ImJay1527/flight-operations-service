package pt.isep.sidis.flightops.readmodel;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.common.crypto.FieldEncryption;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Read database unreachable (no Docker needed: nothing listens on the port). */
class ReadDatabaseDownTest {

    private MongoClient client;
    private MongoReadModel readModel;

    @BeforeEach
    void setUp() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        client = MongoClients.create("mongodb://localhost:" + closedPort
                + "/?serverSelectionTimeoutMS=300&connectTimeoutMS=300");
        readModel = new MongoReadModel(new MongoTemplate(client, "read"),
                new FieldEncryption("9BvH3kXoOq3m1o9mJmPFjB6m4cO1kz3Hqk2iY7G1M8o="));
        readModel.state().ready();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void failedProjectionMakesTheReadModelStaleWithoutFailingTheWrite() {
        LocalDateTime dep = LocalDateTime.now().plusDays(1);
        readModel.project(new FlightView("F-1", "route-opo-lis", "CS-TPA", "A320neo", "OPO", "LIS", 277, 3.8,
                dep, dep.plusMinutes(45), "SCHEDULED", 1));   // must not throw

        assertThat(readModel.isReady()).isFalse();
        assertThat(readModel.state().current().state()).isEqualTo(ReadModelState.State.STALE);
        assertThat(readModel.state().current().reason()).startsWith("read database unavailable");
    }

    @Test
    void failedQueryThrowsSoTheCallerFallsBack() {
        assertThatThrownBy(() -> readModel.flight("F-1")).isInstanceOf(ReadModelUnavailableException.class);
        assertThat(readModel.isReady()).isFalse();
    }
}
