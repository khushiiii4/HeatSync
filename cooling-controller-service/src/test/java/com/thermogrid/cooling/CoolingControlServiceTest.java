package com.thermogrid.cooling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.thermogrid.cooling.domain.CoolingAction;
import com.thermogrid.cooling.domain.CoolingUnit;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;

/** The control loop end to end on H2: persistence, publishing, ordering, fault handling, fail-safe and heartbeat. */
class CoolingControlServiceTest extends CoolingTestBase {

    private long actionCount(String zone) {
        return actions.findAll().stream().filter(a -> a.getZoneId().equals(zone)).count();
    }

    // ---- seeding ----

    @Test
    void oneUnitPerZoneIsSeededWithTheDefaultTargetAndIsIdempotent() {
        assertThat(units.count()).isEqualTo(4);
        CoolingUnit z2 = units.findById("Z2").orElseThrow();
        assertThat(z2.getMode()).isEqualTo("AUTO");
        assertThat(z2.getHealth()).isEqualTo("OK");
        assertThat(z2.getOutputPct()).isEqualTo(40);
        assertThat(z2.getFloorPct()).isEqualTo(30);
        assertThat(z2.getTargetTempC()).isEqualByComparingTo("26.0");
        assertThat(seeder.seed(null)).isZero();
        assertThat(units.count()).isEqualTo(4);
    }

    @Test
    void topologyTargetsAreUsedAndExistingUnitsAreNotOverwritten() {
        units.deleteAll();
        seeder.seed(List.of(new com.thermogrid.cooling.client.TopologyClient.Zone("Z1", new java.math.BigDecimal("24.5")),
                new com.thermogrid.cooling.client.TopologyClient.Zone("Z2", null)));
        assertThat(units.findById("Z1").orElseThrow().getTargetTempC()).isEqualByComparingTo("24.5");
        assertThat(units.findById("Z2").orElseThrow().getTargetTempC()).isEqualByComparingTo("26.0");
        seeder.seed(List.of(new com.thermogrid.cooling.client.TopologyClient.Zone("Z1", new java.math.BigDecimal("30"))));
        assertThat(units.findById("Z1").orElseThrow().getTargetTempC()).isEqualByComparingTo("24.5");
    }

    // ---- control, persistence, publishing ----

    @Test
    void aHotReadingRaisesTheOutputByTheSlewLimitSavesAnActionAndPublishes() {
        reading("Z2", 30.0, "OK");
        assertThat(unit("Z2").outputPct()).isEqualTo(50);
        assertThat(units.findById("Z2").orElseThrow().getOutputPct()).isEqualTo(50);
        List<CoolingAction> as = actions.findAll();
        assertThat(as).hasSize(1);
        assertThat(as.get(0).getOldOutputPct()).isEqualTo(40);
        assertThat(as.get(0).getNewOutputPct()).isEqualTo(50);
        assertThat(as.get(0).getMode()).isEqualTo("AUTO");
        assertThat(as.get(0).getReason()).isNotBlank();
        var s = sentFor("Z2");
        assertThat(s).hasSize(1);
        assertThat(s.get(0).routingKey()).isEqualTo("cooling.unit.state");
        assertThat(s.get(0).output()).isEqualTo(50);
        assertThat(s.get(0).mode()).isEqualTo("AUTO");
        assertThat(s.get(0).health()).isEqualTo("OK");
        assertThat(s.get(0).data().get("targetTempC").asDouble()).isEqualTo(26.0);
        assertThat(s.get(0).envelope().get("producer").asText()).isEqualTo("COOLING-CONTROLLER-SERVICE");
        assertThat(s.get(0).envelope().get("eventType").asText()).isEqualTo("cooling.unit.state");
    }

    @Test
    void outputClimbsTenPointsPerReadingAndStopsAtOneHundred() {
        for (int i = 1; i <= 8; i++) {
            clock.advance(Duration.ofSeconds(2));
            reading("Z2", 40.0, "OK");
            assertThat(unit("Z2").outputPct()).isEqualTo(Math.min(100, 40 + 10 * i));
        }
        assertThat(sentFor("Z2").stream().map(Sent::output)).containsExactly(50, 60, 70, 80, 90, 100);
    }

    @Test
    void stateIsPersistedBeforeItIsPublished() {
        AtomicInteger checked = new AtomicInteger();
        doAnswer(inv -> {
            Message m = inv.getArgument(2);
            var env = mapper.readTree(m.getBody());
            CoolingUnit row = units.findById(env.get("data").get("zoneId").asText()).orElseThrow();
            // At the moment the message leaves, the database already holds the same seq and output.
            assertThat(row.getSeq()).isEqualTo(env.get("data").get("seq").asLong());
            assertThat(row.getOutputPct()).isEqualTo(env.get("data").get("outputPct").asInt());
            checked.incrementAndGet();
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class));
        reading("Z3", 31.0, "OK");
        control.tick();
        assertThat(checked.get()).isGreaterThanOrEqualTo(2);        // the change and the first heartbeats
    }

    @Test
    void changesSmallerThanTwoPointsAreNeitherSavedNorPublished() {
        // 26.0 gives desired 35 from 40: delta -5, applied. From 35 a target-level reading gives delta 0.
        reading("Z1", 26.0, "OK");
        assertThat(unit("Z1").outputPct()).isEqualTo(35);
        clock.advance(Duration.ofSeconds(2));
        reading("Z1", 26.0, "OK");
        clock.advance(Duration.ofSeconds(2));
        reading("Z1", 26.04, "OK");                                 // raw 36.4, rounds to 36: delta 1
        assertThat(unit("Z1").outputPct()).isEqualTo(35);
        assertThat(actionCount("Z1")).isEqualTo(1);
        assertThat(sentFor("Z1")).hasSize(1);
    }

    @Test
    void duplicateOutOfOrderAndStaleReadingsAreIgnored() {
        long rejectedBefore = control.rejectedReadings();
        var t = clock.instant();
        readingAt("Z2", 30.0, "OK", t);
        assertThat(unit("Z2").outputPct()).isEqualTo(50);
        readingAt("Z2", 45.0, "OK", t);                             // duplicate timestamp
        readingAt("Z2", 45.0, "OK", t.minusSeconds(2));             // out of order
        assertThat(unit("Z2").outputPct()).isEqualTo(50);
        clock.advance(Duration.ofSeconds(30));
        readingAt("Z2", 45.0, "OK", clock.instant().minusSeconds(11));   // 11 s old: a replayed backlog
        assertThat(unit("Z2").outputPct()).isEqualTo(50);
        assertThat(control.rejectedReadings() - rejectedBefore).isEqualTo(3);
        reading("Z2", 45.0, "OK");
        assertThat(unit("Z2").outputPct()).isEqualTo(60);
    }

    @Test
    void readingsForUnknownZonesAreIgnored() {
        long accepted = control.acceptedReadings();
        reading("Z9", 40.0, "OK");
        assertThat(control.acceptedReadings()).isEqualTo(accepted);
        assertThat(sent()).isEmpty();
    }

    // ---- alerts ----

    @Test
    void aCriticalAlertBoostsTheZoneToOneHundredEvenOnACoolZoneAndHoldsAfterItClears() {
        alert("thermal.alert.raised", "Z2", "R23", "CRITICAL");
        for (int i = 0; i < 7; i++) {
            reading("Z2", 20.0, "OK");
            clock.advance(Duration.ofSeconds(2));
        }
        assertThat(unit("Z2").outputPct()).isEqualTo(100);

        alert("thermal.alert.cleared", "Z2", "R23", "NORMAL");
        reading("Z2", 20.0, "OK");                                   // hold starts now (60 s)
        clock.advance(Duration.ofSeconds(58));
        reading("Z2", 20.0, "OK");
        assertThat(unit("Z2").outputPct()).isEqualTo(100);            // still held
        clock.advance(Duration.ofSeconds(4));                          // past the hold
        reading("Z2", 20.0, "OK");
        assertThat(unit("Z2").outputPct()).isEqualTo(90);             // released, slewing down by 10
        assertThat(sentFor("Z2").get(sentFor("Z2").size() - 1).reason()).isNotBlank();
    }

    @Test
    void warningAndWatchBoostsAndTheZoneLevelIsTheMaximumOverItsRacks() {
        alert("thermal.alert.raised", "Z1", "R11", "WATCH");
        alert("thermal.alert.raised", "Z1", "R12", "WARNING");
        for (int i = 0; i < 4; i++) {
            reading("Z1", 20.0, "OK");
            clock.advance(Duration.ofSeconds(2));
        }
        assertThat(unit("Z1").outputPct()).isEqualTo(70);             // 40 -> 50 -> 60 -> 70 and capped by WARNING boost
        // Other zones are unaffected.
        reading("Z3", 20.0, "OK");
        assertThat(unit("Z3").outputPct()).isEqualTo(30);
    }

    @Test
    void outOfOrderAlertsDoNotOverwriteNewerOnes() {
        var t = clock.instant();
        control.onAlert(new com.thermogrid.contracts.EventEnvelope<>(java.util.UUID.randomUUID(), "thermal.alert.cleared",
                t, "T", 1, new com.thermogrid.contracts.ThermalAlertData(1, "R23", "Z2", "NORMAL", "WARNING",
                "OBSERVED", 28, 29)));
        control.onAlert(new com.thermogrid.contracts.EventEnvelope<>(java.util.UUID.randomUUID(), "thermal.alert.raised",
                t.minusSeconds(5), "T", 1, new com.thermogrid.contracts.ThermalAlertData(1, "R23", "Z2", "CRITICAL",
                "NORMAL", "OBSERVED", 39, 40)));                         // older than the clear
        reading("Z2", 20.0, "OK");
        assertThat(unit("Z2").outputPct()).isEqualTo(30);
    }

    // ---- fault detection ----

    @Test
    void faultIsConfirmedAfterTwoReadingsAndRaisesTheFloorOfHealthyZones() {
        reading("Z3", 28.0, "FAULT");
        assertThat(unit("Z3").health()).isEqualTo("OK");              // one reading is not enough
        clock.advance(Duration.ofSeconds(2));
        reading("Z3", 28.0, "FAULT");
        assertThat(unit("Z3").health()).isEqualTo("FAULT");
        Sent fault = sentFor("Z3").stream().filter(s -> s.health().equals("FAULT")).findFirst().orElseThrow();
        assertThat(fault.reason()).contains("FAULT confirmed");
        assertThat(units.findById("Z3").orElseThrow().getHealth()).isEqualTo("FAULT");
        assertThat(control.unitViews()).allMatch(u -> u.floorPct() == 60);

        // A cold healthy zone is now held at 60 instead of 30, stepping up by at most 10.
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(2));
            reading("Z1", 20.0, "OK");
        }
        assertThat(unit("Z1").outputPct()).isEqualTo(60);
        assertThat(unit("Z1").floorPct()).isEqualTo(60);
    }

    @Test
    void recoveryNeedsThreeConsecutiveOkReadingsAndRemovesTheFloor() {
        reading("Z3", 28.0, "FAULT");
        clock.advance(Duration.ofSeconds(2));
        reading("Z3", 28.0, "FAULT");
        clock.advance(Duration.ofSeconds(2));
        reading("Z3", 28.0, "OK");
        clock.advance(Duration.ofSeconds(2));
        reading("Z3", 28.0, "OK");
        assertThat(unit("Z3").health()).isEqualTo("FAULT");
        clock.advance(Duration.ofSeconds(2));
        reading("Z3", 28.0, "OK");
        assertThat(unit("Z3").health()).isEqualTo("OK");
        assertThat(control.unitViews()).allMatch(u -> u.floorPct() == 30);
        assertThat(sentFor("Z3").stream().anyMatch(s -> s.reason().contains("recovered"))).isTrue();
    }

    @Test
    void anIntermediateDegradedReadingDoesNotCountTowardEitherDirection() {
        reading("Z3", 28.0, "FAULT");
        clock.advance(Duration.ofSeconds(2));
        reading("Z3", 28.0, "DEGRADED");
        clock.advance(Duration.ofSeconds(2));
        reading("Z3", 28.0, "FAULT");
        assertThat(unit("Z3").health()).isEqualTo("OK");
        clock.advance(Duration.ofSeconds(2));
        reading("Z3", 28.0, "FAULT");
        assertThat(unit("Z3").health()).isEqualTo("FAULT");
        for (String h : new String[] {"OK", "OK", "DEGRADED", "OK", "OK"}) {
            clock.advance(Duration.ofSeconds(2));
            reading("Z3", 28.0, h);
        }
        assertThat(unit("Z3").health()).isEqualTo("FAULT");
    }

    // ---- fail-safe ----

    @Test
    void noReadingForTenSecondsEntersFailsafeAtEightyPercentOnceOnly() {
        control.tick();
        clock.advance(Duration.ofMillis(9_999));
        control.tick();
        assertThat(unit("Z2").mode()).isEqualTo("AUTO");
        clock.advance(Duration.ofMillis(1));                         // exactly 10 s
        control.tick();
        assertThat(unit("Z2").mode()).isEqualTo("FAILSAFE");
        assertThat(unit("Z2").outputPct()).isEqualTo(80);
        long actionsAfterEntry = actionCount("Z2");
        assertThat(actionsAfterEntry).isEqualTo(1);
        for (int i = 0; i < 30; i++) {
            clock.advance(Duration.ofSeconds(1));
            control.tick();
        }
        assertThat(actionCount("Z2")).isEqualTo(actionsAfterEntry);   // identical watchdog result is not persisted again
        assertThat(actions.findAll().get(0).getReason()).contains("fail-safe");
        assertThat(units.findById("Z2").orElseThrow().getMode()).isEqualTo("FAILSAFE");
        assertThat(sentFor("Z2").stream().filter(s -> s.mode().equals("FAILSAFE") && s.output() == 80).count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void threeFreshReadingsReturnTheZoneToAutomaticControl() {
        clock.advance(Duration.ofSeconds(10));
        control.tick();
        assertThat(unit("Z2").mode()).isEqualTo("FAILSAFE");

        clock.advance(Duration.ofSeconds(2));
        reading("Z2", 30.0, "OK");
        assertThat(unit("Z2").mode()).isEqualTo("FAILSAFE");
        assertThat(unit("Z2").outputPct()).isEqualTo(80);             // held while recovering
        clock.advance(Duration.ofSeconds(2));
        reading("Z2", 30.0, "OK");
        assertThat(unit("Z2").mode()).isEqualTo("FAILSAFE");
        clock.advance(Duration.ofSeconds(2));
        reading("Z2", 30.0, "OK");
        assertThat(unit("Z2").mode()).isEqualTo("AUTO");
        assertThat(unit("Z2").outputPct()).isBetween(70, 90);          // slews from 80 toward the demand
        assertThat(sentFor("Z2").stream().anyMatch(s -> s.reason().contains("fail-safe cleared"))).isTrue();
    }

    @Test
    void staleAndDuplicateReadingsDoNotCountTowardRecovery() {
        clock.advance(Duration.ofSeconds(10));
        control.tick();
        var fresh = clock.instant().plusSeconds(1);
        clock.advance(Duration.ofSeconds(1));
        readingAt("Z2", 30.0, "OK", fresh);
        readingAt("Z2", 30.0, "OK", fresh);                          // duplicate
        readingAt("Z2", 30.0, "OK", fresh.minusSeconds(30));         // stale backlog
        readingAt("Z2", 30.0, "OK", fresh.minusSeconds(1));          // out of order
        assertThat(unit("Z2").mode()).isEqualTo("FAILSAFE");
        clock.advance(Duration.ofSeconds(2));
        reading("Z2", 30.0, "OK");
        assertThat(unit("Z2").mode()).isEqualTo("FAILSAFE");          // only 2 genuine readings so far
        clock.advance(Duration.ofSeconds(2));
        reading("Z2", 30.0, "OK");
        assertThat(unit("Z2").mode()).isEqualTo("AUTO");
    }

    @Test
    void aSilentZoneFailsafesWhileAZoneThatKeepsReportingDoesNot() {
        for (int i = 0; i < 8; i++) {
            clock.advance(Duration.ofSeconds(2));
            reading("Z1", 26.0, "OK");
            control.tick();
        }
        assertThat(unit("Z1").mode()).isEqualTo("AUTO");
        assertThat(unit("Z2").mode()).isEqualTo("FAILSAFE");
    }

    // ---- heartbeat and broker failures ----

    @Test
    void everyUnitIsRepublishedAtLeastEveryFiveSecondsWithIncreasingSeq() {
        control.tick();                                              // first heartbeat for every unit
        for (int i = 0; i < 4; i++) {
            for (int s = 0; s < 5; s++) {
                clock.advance(Duration.ofSeconds(1));
                reading("Z1", 26.0, "OK");                          // keep Z1 alive; the others will fail safe
                control.tick();
            }
        }
        List<Sent> z1 = sentFor("Z1");
        assertThat(z1.size()).isGreaterThanOrEqualTo(4);
        for (int i = 1; i < z1.size(); i++) {
            assertThat(z1.get(i).seq()).isGreaterThan(z1.get(i - 1).seq());
        }
        assertThat(units.findById("Z1").orElseThrow().getSeq()).isEqualTo(z1.get(z1.size() - 1).seq());
    }

    @Test
    void controlContinuesAndStatePersistsWhileRabbitMqIsDownAndPublishingResumesAfterwards() {
        doThrow(new AmqpConnectException(new java.io.IOException("down"))).when(rabbit)
                .send(anyString(), anyString(), any(Message.class));
        reading("Z2", 40.0, "OK");
        clock.advance(Duration.ofSeconds(2));
        reading("Z2", 40.0, "OK");
        assertThat(unit("Z2").outputPct()).isEqualTo(60);              // the loop kept controlling
        assertThat(units.findById("Z2").orElseThrow().getOutputPct()).isEqualTo(60);
        assertThat(actionCount("Z2")).isEqualTo(2);
        control.tick();                                              // heartbeat while down: no exception

        org.mockito.Mockito.reset(rabbit);                            // broker is back
        clock.advance(Duration.ofSeconds(6));                         // cooldown and heartbeat interval elapsed
        control.tick();
        var after = sentFor("Z2");
        assertThat(after).isNotEmpty();
        assertThat(after.get(after.size() - 1).output()).isEqualTo(60);   // the latest absolute state reaches the simulator
    }

    @Test
    void persistedStateSurvivesARestart() {
        reading("Z2", 40.0, "OK");
        reading("Z3", 28.0, "FAULT");
        clock.advance(Duration.ofSeconds(2));
        reading("Z3", 28.0, "FAULT");
        control.load();                                              // restart: rebuild from the database
        assertThat(unit("Z2").outputPct()).isEqualTo(50);
        assertThat(unit("Z3").health()).isEqualTo("FAULT");
        long seq = unit("Z2").seq();
        control.tick();
        assertThat(unit("Z2").seq()).isGreaterThan(seq);               // seq never goes backwards
    }

    @Test
    void manualModeHoldsAFixedOutputAndFailsafeStillApplies() {
        control.setMode("Z4", "MANUAL", 65);
        assertThat(unit("Z4").mode()).isEqualTo("MANUAL");
        assertThat(unit("Z4").outputPct()).isEqualTo(65);
        clock.advance(Duration.ofSeconds(2));
        reading("Z4", 40.0, "OK");                                   // would command 100: ignored in MANUAL
        assertThat(unit("Z4").outputPct()).isEqualTo(65);

        clock.advance(Duration.ofSeconds(10));
        control.tick();
        assertThat(unit("Z4").mode()).isEqualTo("FAILSAFE");
        assertThat(unit("Z4").outputPct()).isEqualTo(80);
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofSeconds(2));
            reading("Z4", 40.0, "OK");
        }
        assertThat(unit("Z4").mode()).isEqualTo("MANUAL");            // the operator's mode is restored
        assertThat(unit("Z4").outputPct()).isEqualTo(65);

        control.setMode("Z4", "AUTO", null);
        clock.advance(Duration.ofSeconds(2));
        reading("Z4", 40.0, "OK");
        assertThat(unit("Z4").outputPct()).isEqualTo(75);
    }
}
