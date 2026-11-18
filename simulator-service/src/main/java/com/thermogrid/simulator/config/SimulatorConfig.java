package com.thermogrid.simulator.config;

import com.thermogrid.simulator.physics.PhysicsModel;
import com.thermogrid.simulator.sim.SimState;
import com.thermogrid.simulator.topology.RackGrid;
import com.thermogrid.simulator.topology.TopologyBootstrapper;
import com.thermogrid.simulator.topology.TopologyClient;
import com.thermogrid.contracts.Exchanges;
import java.time.Clock;
import org.springframework.amqp.core.TopicExchange;
import java.util.Random;
import java.util.function.DoubleSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(SimulatorProperties.class)
public class SimulatorConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Blocks startup until the topology is loaded (or fails), so the simulation never starts empty. */
    @Bean
    RackGrid rackGrid(TopologyClient client, SimulatorProperties props) {
        return new TopologyBootstrapper(client, props, Thread::sleep).load();
    }

    /** Durable topic exchange for all domain events. Declared on the first broker connection by RabbitAdmin. */
    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(Exchanges.EVENTS, true, false);
    }

    @Bean
    PhysicsModel physicsModel(SimulatorProperties props) {
        Random random = props.noiseSeed() == null ? new Random() : new Random(props.noiseSeed());
        DoubleSupplier gaussian = random::nextGaussian;
        return new PhysicsModel(props.physicsParams(), gaussian);
    }

    @Bean
    SimState simState(RackGrid grid, PhysicsModel model, SimulatorProperties props, Clock clock) {
        return new SimState(grid, model, props.initial(), clock);
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "thermogrid.simulator.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
