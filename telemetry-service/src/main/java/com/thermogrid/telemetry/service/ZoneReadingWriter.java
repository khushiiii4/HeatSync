package com.thermogrid.telemetry.service;

import com.thermogrid.telemetry.domain.ZoneReading;
import com.thermogrid.telemetry.repo.ZoneReadingRepository;
import com.thermogrid.telemetry.service.ReadingValidator.ZoneSample;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores zone readings idempotently: a duplicate (zoneId, recordedAt) is ignored. */
@Service
public class ZoneReadingWriter {

    private final ZoneReadingRepository zones;

    public ZoneReadingWriter(ZoneReadingRepository zones) {
        this.zones = zones;
    }

    /** @return true if a new row was inserted, false for a duplicate */
    @Transactional
    public boolean store(ZoneSample s) {
        if (zones.existsByZoneIdAndRecordedAt(s.zoneId(), s.recordedAt())) {
            return false;
        }
        zones.saveAndFlush(new ZoneReading(s.zoneId(), s.recordedAt(), s.avgTempC(), s.maxTempC(), s.supplyTempC(),
                s.fanActualPct(), s.unitHealth()));
        return true;
    }
}
