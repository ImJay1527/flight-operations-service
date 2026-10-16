package pt.isep.sidis.flightops.monitoring;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/**
 * CPU used by this process, measured from its CPU time every 2 s (100 = one full core). The JVM's own
 * process.cpu.usage gauge often reads 0 inside a container, which is where the auto-scaler is watched.
 */
@Component
public class CpuSampler {

    private final com.sun.management.OperatingSystemMXBean os =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private long lastCpuNanos = os.getProcessCpuTime();
    private long lastWallNanos = System.nanoTime();
    private volatile Double percent;

    @Scheduled(fixedRate = 2000)
    void sample() {
        long cpu = os.getProcessCpuTime();
        long wall = System.nanoTime();
        if (cpu >= 0 && wall > lastWallNanos) {
            percent = Math.round(1000.0 * (cpu - lastCpuNanos) / (wall - lastWallNanos)) / 10.0;
        }
        lastCpuNanos = cpu;
        lastWallNanos = wall;
    }

    /** Null until the first sample. */
    public Double percent() {
        return percent;
    }
}
