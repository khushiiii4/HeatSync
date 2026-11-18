package com.thermogrid.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thermogrid.contracts.WorkloadRemovedData;
import com.thermogrid.workload.api.Dtos.SubmitRequest;
import com.thermogrid.workload.config.WorkloadProperties;
import com.thermogrid.workload.domain.Priority;
import com.thermogrid.workload.event.WorkloadEvent;
import com.thermogrid.workload.event.WorkloadEventPublisher;
import com.thermogrid.workload.service.WorkloadService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class WorkloadEventPublisherTest extends WorkloadTestBase {

    @Autowired PlatformTransactionManager txManager;
    @Autowired WorkloadService service;

    @Test
    void eventsAreSentOnlyAfterTheTransactionCommits() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            service.submit(new SubmitRequest("a", 10, Priority.LOW), "t");
            // Row is written, transaction still open: nothing may have reached the broker.
            verifyNoInteractions(rabbit);
        });
        verify(rabbit, times(1)).send(eq("thermogrid.events"), eq("workload.submitted"), any(Message.class));
    }

    @Test
    void rolledBackTransactionPublishesNothing() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            service.submit(new SubmitRequest("a", 10, Priority.LOW), "t");
            status.setRollbackOnly();
        });
        verifyNoInteractions(rabbit);
        assertThat(repo.count()).isZero();
    }

    @Test
    void failingSeedRollsBackWithoutEvents() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            service.submit(new SubmitRequest("a", 10, Priority.LOW), "t");
            service.submit(new SubmitRequest("b", 10, Priority.LOW), "t");
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(rabbit);
        assertThat(repo.count()).isZero();
    }

    @Test
    void brokerFailureAfterCommitKeepsTheDatabaseChange() throws Exception {
        doThrow(new AmqpConnectException(new java.io.IOException("down"))).when(rabbit)
                .send(any(String.class), any(String.class), any(Message.class));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workloads")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"cpuDemand\":10,\"priority\":\"LOW\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        assertThat(repo.count()).isEqualTo(1);
        verify(rabbit, times(3)).send(eq("thermogrid.events"), eq("workload.submitted"), any(Message.class));
        Mockito.reset(rabbit);
    }

    @Test
    void publisherRetriesThenSucceeds() {
        RabbitOperations ops = mock(RabbitOperations.class);
        doThrow(new AmqpConnectException(new java.io.IOException("down"))).doNothing().when(ops)
                .send(any(String.class), any(String.class), any(Message.class));
        var props = new WorkloadProperties(false, "WORKLOAD-SERVICE", 1, 10, 3, 0,
                new WorkloadProperties.Retention(7, 1000));
        var publisher = new WorkloadEventPublisher(ops, mapper(), props);
        publisher.onCommitted(new WorkloadEvent("workload.removed", new WorkloadRemovedData(1, "R11", 10), Instant.now()));
        verify(ops, times(2)).send(eq("thermogrid.events"), eq("workload.removed"), any(Message.class));
    }

    @Test
    void publisherGivesUpQuietlyAfterTheConfiguredAttempts() {
        RabbitOperations ops = mock(RabbitOperations.class);
        doThrow(new AmqpConnectException(new java.io.IOException("down"))).when(ops)
                .send(any(String.class), any(String.class), any(Message.class));
        var props = new WorkloadProperties(false, "WORKLOAD-SERVICE", 1, 10, 3, 0,
                new WorkloadProperties.Retention(7, 1000));
        new WorkloadEventPublisher(ops, mapper(), props)
                .onCommitted(new WorkloadEvent("workload.removed", new WorkloadRemovedData(1, "R11", 10), Instant.now()));
        verify(ops, times(3)).send(any(String.class), any(String.class), any(Message.class));
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
