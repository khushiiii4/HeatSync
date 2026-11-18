package com.thermogrid.simulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Helpers for tests that need a throw-away H2 database with the real schema.sql, or a clock they can move. */
public final class TestSupport {

    private TestSupport() {
    }

    /** Fresh in-memory H2 with the production schema.sql applied. Never touches a file or any real database. */
    public static JdbcTemplate memoryDb() {
        return withSchema(new DriverManagerDataSource("jdbc:h2:mem:t" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "sa", ""));
    }

    public static JdbcTemplate withSchema(DriverManagerDataSource ds) {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        return new JdbcTemplate(ds);
    }

    public static final class MutableClock extends Clock {
        private Instant now;

        public MutableClock(Instant start) {
            this.now = start;
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
