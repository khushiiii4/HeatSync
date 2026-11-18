package com.thermogrid.scheduler.service;

import com.thermogrid.scheduler.config.SchedulerProperties;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Exactly one scheduler may run (plan: single instance, leader election out of scope). At startup the process takes the
 * MySQL advisory lock {@value #LOCK_NAME} on a dedicated connection that stays open for its whole life; a second
 * scheduler against the same database fails to start with a clear message. The lock is released by the database when
 * the process dies, so a crash never leaves the scheduler locked out.
 */
@Component
@ConditionalOnProperty(name = "thermogrid.scheduler.single-instance-lock", havingValue = "true", matchIfMissing = true)
public class SingleInstanceGuard implements InitializingBean {

    public static final String LOCK_NAME = "thermogrid-scheduler-singleton";
    private static final Logger log = LoggerFactory.getLogger(SingleInstanceGuard.class);

    private final DataSource dataSource;
    private Connection lockConnection;

    public SingleInstanceGuard(DataSource dataSource, SchedulerProperties props) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        acquire(dataSource.getConnection());
    }

    /** Package-visible so a test can drive it with a fake connection. */
    void acquire(Connection c) throws SQLException {
        boolean got;
        try (PreparedStatement ps = c.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            ps.setString(1, LOCK_NAME);
            try (ResultSet rs = ps.executeQuery()) {
                got = rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        if (!got) {
            c.close();
            throw new IllegalStateException("Another scheduler-service instance already holds the lock '" + LOCK_NAME
                    + "' on this database. Only one scheduler may run; refusing to start.");
        }
        this.lockConnection = c;
        log.info("Scheduler singleton lock acquired");
    }

    @PreDestroy
    void release() {
        if (lockConnection != null) {
            try {
                lockConnection.close();   // closing the session releases the advisory lock
            } catch (SQLException ignored) {
                // the database drops the lock when the session ends anyway
            }
        }
    }
}
