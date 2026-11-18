package com.thermogrid.workload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.workload.repo.WorkloadRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Full context on in-memory H2 with a mocked broker connection. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class WorkloadTestBase {

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected WorkloadRepository repo;
    @MockitoBean protected org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;

    @BeforeEach
    void clean() {
        repo.deleteAll();
        Mockito.clearInvocations(rabbit);
    }

    /** Routing keys and bodies of everything sent so far, in order. */
    protected record Sent(String routingKey, JsonNode body) {
    }

    protected List<Sent> sent() {
        ArgumentCaptor<String> exchange = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Message> msg = ArgumentCaptor.forClass(Message.class);
        Mockito.verify(rabbit, Mockito.atLeast(0)).send(exchange.capture(), key.capture(), msg.capture());
        return java.util.stream.IntStream.range(0, key.getAllValues().size()).mapToObj(i -> {
            try {
                org.junit.jupiter.api.Assertions.assertEquals("thermogrid.events", exchange.getAllValues().get(i));
                return new Sent(key.getAllValues().get(i), mapper.readTree(msg.getAllValues().get(i).getBody()));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }
}
