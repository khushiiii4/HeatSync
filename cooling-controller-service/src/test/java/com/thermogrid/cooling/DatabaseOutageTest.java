package com.thermogrid.cooling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.thermogrid.cooling.domain.CoolingUnit;
import com.thermogrid.cooling.domain.Repositories.CoolingUnitRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Phase 7: persistence and publication outcomes are explicit and independent. A state is published only after it has
 * been stored; a database outage never produces a message, and a broker outage never loses a stored state.
 */
class DatabaseOutageTest extends CoolingTestBase {

    @MockitoSpyBean CoolingUnitRepository unitRepo;

    private void databaseDown() {
        doThrow(new DataAccessResourceFailureException("database is down")).when(unitRepo).saveAndFlush(any());
    }

    private void databaseBack() {
        Mockito.reset(unitRepo);
    }

    @Test
    void whileTheDatabaseIsDownControlContinuesInMemoryButNothingIsPublished() {
        databaseDown();
        reading("Z2", 30.0, "OK");

        assertThat(unit("Z2").outputPct()).isEqualTo(50);              // safe cooling behaviour is preserved
        assertThat(sent()).isEmpty();                                   // never claims a state that is not stored
        assertThat(control.persistenceDegraded()).isTrue();
        assertThat(control.persistFailures()).isGreaterThan(0);
        assertThat(units.findById("Z2").orElseThrow().getOutputPct()).isEqualTo(40);   // the database still holds 40
    }

    @Test
    void heartbeatsDuringTheOutagePublishNothingEither() {
        databaseDown();
        control.tick();
        clock.advance(Duration.ofSeconds(6));
        control.tick();
        assertThat(sent()).isEmpty();
        assertThat(control.persistenceDegraded()).isTrue();
    }

    @Test
    void onceTheDatabaseRecoversTheLatestStateIsStoredThenPublishedWithTheStoredSeq() {
        databaseDown();
        reading("Z2", 30.0, "OK");
        clock.advance(Duration.ofSeconds(2));
        reading("Z2", 30.0, "OK");
        assertThat(unit("Z2").outputPct()).isEqualTo(60);
        assertThat(sent()).isEmpty();

        databaseBack();
        control.tick();

        assertThat(control.persistenceDegraded()).isFalse();
        List<Sent> z2 = sentFor("Z2");
        assertThat(z2).hasSize(1);
        assertThat(z2.get(0).output()).isEqualTo(60);
        CoolingUnit row = units.findById("Z2").orElseThrow();
        assertThat(row.getOutputPct()).isEqualTo(60);
        assertThat(row.getSeq()).isEqualTo(z2.get(0).seq());           // what was published is what is stored
    }

    @Test
    void sequenceNumbersSeenByTheSimulatorNeverGoBackwardsAcrossAnOutageAndARestart() {
        reading("Z2", 30.0, "OK");                                      // stored and published
        long lastPublished = sentFor("Z2").get(0).seq();

        databaseDown();
        clock.advance(Duration.ofSeconds(2));
        reading("Z2", 30.0, "OK");                                      // in memory only
        control.load();                                                 // restart while the database was behind
        databaseBack();
        clock.advance(Duration.ofSeconds(6));
        control.tick();

        List<Sent> z2 = sentFor("Z2");
        for (int i = 1; i < z2.size(); i++) {
            assertThat(z2.get(i).seq()).isGreaterThan(z2.get(i - 1).seq());
        }
        assertThat(z2.get(z2.size() - 1).seq()).isGreaterThan(lastPublished);
    }

    @Test
    void aBrokerOutageStillStoresEveryStateAndIsNotReportedAsAPersistenceFailure() {
        doThrow(new AmqpConnectException(new java.io.IOException("broker down"))).when(rabbit)
                .send(anyString(), anyString(), any(Message.class));
        long failuresBefore = control.persistFailures();
        reading("Z2", 30.0, "OK");

        assertThat(control.persistenceDegraded()).isFalse();
        assertThat(control.persistFailures()).isEqualTo(failuresBefore);
        assertThat(units.findById("Z2").orElseThrow().getOutputPct()).isEqualTo(50);   // stored
        assertThat(publisher.failedCount()).isGreaterThan(0);                           // publish outcome is separate
    }
}
