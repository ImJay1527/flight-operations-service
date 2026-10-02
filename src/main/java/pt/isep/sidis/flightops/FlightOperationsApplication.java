package pt.isep.sidis.flightops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // peer health checks (resilience.HealthChecker)
public class FlightOperationsApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlightOperationsApplication.class, args);
    }
}
