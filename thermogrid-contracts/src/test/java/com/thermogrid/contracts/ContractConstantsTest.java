package com.thermogrid.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContractConstantsTest {

    @Test
    void exchangesMatchPlanSection84() {
        assertThat(Exchanges.EVENTS).isEqualTo("thermogrid.events");
        assertThat(Exchanges.DEAD_LETTER).isEqualTo("thermogrid.dlx");
    }

    @Test
    void routingKeysMatchPlanSection85() {
        assertThat(RoutingKeys.TELEMETRY_RACK_READING).isEqualTo("telemetry.rack.reading");
        assertThat(RoutingKeys.TELEMETRY_ZONE_READING).isEqualTo("telemetry.zone.reading");
        assertThat(RoutingKeys.THERMAL_ALERT_RAISED).isEqualTo("thermal.alert.raised");
        assertThat(RoutingKeys.THERMAL_ALERT_LOWERED).isEqualTo("thermal.alert.lowered");
        assertThat(RoutingKeys.THERMAL_ALERT_CLEARED).isEqualTo("thermal.alert.cleared");
        assertThat(RoutingKeys.WORKLOAD_SUBMITTED).isEqualTo("workload.submitted");
        assertThat(RoutingKeys.WORKLOAD_ASSIGNED).isEqualTo("workload.assigned");
        assertThat(RoutingKeys.WORKLOAD_REMOVED).isEqualTo("workload.removed");
        assertThat(RoutingKeys.COOLING_UNIT_STATE).isEqualTo("cooling.unit.state");
        assertThat(RoutingKeys.THERMAL_ALERT_ALL).isEqualTo("thermal.alert.#");
    }

    @Test
    void headerNamesMatchPlanSection8() {
        assertThat(Headers.INTERNAL_TOKEN).isEqualTo("X-Internal-Token");
        assertThat(Headers.USER_NAME).isEqualTo("X-User-Name");
        assertThat(Headers.USER_ROLE).isEqualTo("X-User-Role");
    }
}