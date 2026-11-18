package com.thermogrid.cooling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thermogrid.cooling.service.SingleInstanceGuard;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** The cooling controller takes a MySQL advisory lock at startup; a second controller must refuse to start. */
class SingleInstanceGuardTest {

    private static Connection connectionReturning(int lockResult) throws SQLException {
        Connection c = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(lockResult);
        return c;
    }

    @Test
    void firstInstanceKeepsItsConnectionOpenAndReleasesItOnShutdown() throws Exception {
        Connection c = connectionReturning(1);
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(c);
        SingleInstanceGuard guard = new SingleInstanceGuard(ds);
        guard.afterPropertiesSet();
        verify(c, never()).close();                      // the lock lives as long as this session
        java.lang.reflect.Method release = SingleInstanceGuard.class.getDeclaredMethod("release");
        release.setAccessible(true);
        release.invoke(guard);
        verify(c).close();
    }

    @Test
    void secondInstanceIsRefusedWithAClearMessage() throws Exception {
        Connection c = connectionReturning(0);
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(c);
        SingleInstanceGuard guard = new SingleInstanceGuard(ds);
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Another cooling-controller-service instance")
                .hasMessageContaining(SingleInstanceGuard.LOCK_NAME);
        verify(c).close();
    }

    @Test
    void databaseErrorsDuringLockingAreNotSwallowed() throws Exception {
        Connection c = mock(Connection.class);
        when(c.prepareStatement(anyString())).thenThrow(new SQLException("no GET_LOCK"));
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(c);
        assertThatThrownBy(() -> new SingleInstanceGuard(ds).afterPropertiesSet())
                .isInstanceOf(SQLException.class);
        assertThat(true).isTrue();
    }
}
