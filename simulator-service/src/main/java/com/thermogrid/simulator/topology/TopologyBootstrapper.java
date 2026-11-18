package com.thermogrid.simulator.topology;

import com.thermogrid.simulator.config.SimulatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches the topology, retrying transport failures with bounded exponential backoff. A malformed payload is not
 * retried: it fails at once with {@link InvalidTopologyException}. Gives up with IllegalStateException so the
 * simulator never runs on an empty topology.
 */
public class TopologyBootstrapper {

    private static final Logger log = LoggerFactory.getLogger(TopologyBootstrapper.class);

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final TopologyClient client;
    private final SimulatorProperties props;
    private final Sleeper sleeper;

    public TopologyBootstrapper(TopologyClient client, SimulatorProperties props, Sleeper sleeper) {
        this.client = client;
        this.props = props;
        this.sleeper = sleeper;
    }

    public RackGrid load() {
        SimulatorProperties.Bootstrap b = props.bootstrap();
        long delay = b.initialBackoffMs();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= b.maxAttempts(); attempt++) {
            try {
                RackGrid grid = TopologyMapper.toGrid(client.topology(), props.grid());
                log.info("Topology loaded: {} zones, {} racks ({} active)", grid.zones().size(),
                        grid.racks().size(), grid.racks().stream().filter(r -> r.active()).count());
                return grid;
            } catch (InvalidTopologyException e) {
                throw e;
            } catch (RuntimeException e) {
                last = e;
                if (attempt == b.maxAttempts()) {
                    break;
                }
                log.warn("Topology not available (attempt {}/{}): {}. Retrying in {} ms", attempt, b.maxAttempts(),
                        e.getMessage(), delay);
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for topology-service", ie);
                }
                delay = Math.min(b.maxBackoffMs(), delay * 2);
            }
        }
        throw new IllegalStateException("topology-service unavailable after " + b.maxAttempts()
                + " attempts; refusing to start the simulation with an empty topology", last);
    }
}
