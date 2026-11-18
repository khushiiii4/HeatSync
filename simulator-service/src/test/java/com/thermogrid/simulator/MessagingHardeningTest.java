package com.thermogrid.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thermogrid.simulator.events.SimulatorEventDispatcher;
import com.thermogrid.simulator.events.SimulatorEventHandler;
import com.thermogrid.simulator.workload.WorkloadIntegrationConfig;
import com.thermogrid.simulator.workload.WorkloadSyncProperties;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/** Plan 8.4 queue policies for the simulator, permanent-error detection and the state-message drop rule. */
class MessagingHardeningTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final SimulatorEventHandler handler = mock(SimulatorEventHandler.class);
    private final SimulatorEventDispatcher dispatcher = new SimulatorEventDispatcher(MAPPER, handler);
    private final WorkloadSyncProperties props = new WorkloadSyncProperties(true, 60000, 5000, 300000, 50000);

    private static byte[] json(String type, int version, String data) {
        return ("{\"eventId\":\"0b8e5c1e-6f3a-4a9e-8d57-1c2b3a4d5e6f\",\"eventType\":\"" + type
                + "\",\"occurredAt\":\"2026-11-18T07:10:06.310Z\",\"producer\":\"x\",\"version\":" + version
                + ",\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
    }

    // ---- topology (the queue beans live in a package-private nested class, so they are checked on a live context) ----

    @Test
    void workloadQueueDeclarationIsLosslessAndCoolingQueueIsAStateQueue() throws Exception {
        Class<?> cfgClass = Class.forName(WorkloadIntegrationConfig.class.getName() + "$ListenerConfig");
        var ctor = cfgClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object cfg = ctor.newInstance();

        Method wq = cfgClass.getDeclaredMethod("simulatorWorkloadQueue", WorkloadSyncProperties.class);
        Method cq = cfgClass.getDeclaredMethod("simulatorCoolingQueue", WorkloadSyncProperties.class);
        wq.setAccessible(true);
        cq.setAccessible(true);
        Queue workloads = (Queue) wq.invoke(cfg, props);
        Queue cooling = (Queue) cq.invoke(cfg, props);

        assertThat(workloads.getName()).isEqualTo("q.simulator.workloads");
        assertThat(workloads.isDurable()).isTrue();
        assertThat(workloads.getArguments()).containsEntry("x-dead-letter-exchange", "thermogrid.dlx")
                .containsEntry("x-max-length", 50000).doesNotContainKey("x-message-ttl");
        assertThat(cooling.getName()).isEqualTo("q.simulator.cooling");
        assertThat(cooling.getArguments()).containsEntry("x-message-ttl", 60000)
                .doesNotContainKey("x-dead-letter-exchange");

        Method ab = cfgClass.getDeclaredMethod("assignedBinding", Queue.class, TopicExchange.class);
        Method rb = cfgClass.getDeclaredMethod("removedBinding", Queue.class, TopicExchange.class);
        Method cb = cfgClass.getDeclaredMethod("coolingBinding", Queue.class, TopicExchange.class);
        for (Method m : new Method[] {ab, rb, cb}) {
            m.setAccessible(true);
        }
        TopicExchange events = new TopicExchange("thermogrid.events", true, false);
        assertThat(((Binding) ab.invoke(cfg, workloads, events)).getRoutingKey()).isEqualTo("workload.assigned");
        assertThat(((Binding) rb.invoke(cfg, workloads, events)).getRoutingKey()).isEqualTo("workload.removed");
        assertThat(((Binding) cb.invoke(cfg, cooling, events)).getRoutingKey()).isEqualTo("cooling.unit.state");
    }

    @Test
    void listenersUseTheFactoryOfTheirQueueType() throws Exception {
        Method w = WorkloadIntegrationConfig.WorkloadMessageListener.class.getMethod("onMessage", Message.class);
        Method c = WorkloadIntegrationConfig.CoolingMessageListener.class.getMethod("onMessage", Message.class);
        assertThat(w.getAnnotation(RabbitListener.class).containerFactory())
                .isEqualTo(WorkloadIntegrationConfig.LOSSLESS_FACTORY);
        assertThat(c.getAnnotation(RabbitListener.class).containerFactory())
                .isEqualTo(WorkloadIntegrationConfig.STATE_FACTORY);
    }

    // ---- permanent errors are IllegalArgumentException (never retried) ----

    @Test
    void anUnsupportedEventVersionIsAPermanentError() {
        assertThatThrownBy(() -> dispatcher.dispatch("workload.removed",
                json("workload.removed", 2, "{\"workloadId\":1,\"rackId\":\"R11\",\"cpuDemand\":10}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported");
    }

    @Test
    void invalidIdentifiersAndImpossibleValuesArePermanentErrors() {
        assertThatThrownBy(() -> dispatcher.dispatch("workload.assigned", json("workload.assigned", 1,
                "{\"workloadId\":0,\"cpuDemand\":30,\"priority\":\"LOW\",\"toRackId\":\"R41\",\"version\":1}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch("workload.assigned", json("workload.assigned", 1,
                "{\"workloadId\":5,\"cpuDemand\":30,\"priority\":\"LOW\",\"toRackId\":\" \",\"version\":1}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch("workload.assigned", json("workload.assigned", 1,
                "{\"workloadId\":5,\"cpuDemand\":500,\"priority\":\"LOW\",\"toRackId\":\"R41\",\"version\":1}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch("cooling.unit.state", json("cooling.unit.state", 1,
                "{\"zoneId\":\"Z2\",\"mode\":\"AUTO\",\"outputPct\":140,\"health\":\"OK\",\"seq\":1}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch("cooling.unit.state", json("cooling.unit.state", 1,
                "{\"zoneId\":\"\",\"mode\":\"AUTO\",\"outputPct\":50,\"health\":\"OK\",\"seq\":1}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch("workload.removed", json("workload.removed", 1,
                "{\"workloadId\":-3,\"rackId\":\"R11\",\"cpuDemand\":10}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingEnvelopeFieldsAndMalformedJsonArePermanentErrors() {
        assertThatThrownBy(() -> dispatcher.dispatch("workload.removed", "{\"eventType\":\"workload.removed\"}".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch("workload.removed", "not json".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- state queue drop rule ----

    @Test
    void aBadOrFailingCoolingMessageIsDroppedNotThrown() {
        SimulatorEventDispatcher failing = mock(SimulatorEventDispatcher.class);
        doThrow(new IllegalArgumentException("bad")).when(failing).dispatch(any(), any());
        var listener = new WorkloadIntegrationConfig.CoolingMessageListener(failing);
        MessageProperties p = new MessageProperties();
        p.setReceivedRoutingKey("cooling.unit.state");
        listener.onMessage(MessageBuilder.withBody("x".getBytes()).andProperties(p).build());   // must not throw
    }

    @Test
    void aBadLosslessMessageStillPropagatesSoItIsRetriedThenDeadLettered() {
        SimulatorEventDispatcher failing = mock(SimulatorEventDispatcher.class);
        doThrow(new IllegalStateException("transient")).when(failing).dispatch(any(), any());
        var listener = new WorkloadIntegrationConfig.WorkloadMessageListener(failing);
        MessageProperties p = new MessageProperties();
        p.setReceivedRoutingKey("workload.assigned");
        assertThatThrownBy(() -> listener.onMessage(MessageBuilder.withBody("x".getBytes()).andProperties(p).build()))
                .isInstanceOf(IllegalStateException.class);
    }
}
