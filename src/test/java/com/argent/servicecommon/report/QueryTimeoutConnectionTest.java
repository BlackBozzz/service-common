package com.argent.servicecommon.report;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Обёртка соединения выставляет созданным запросам таймаут и предел строк. */
class QueryTimeoutConnectionTest {

    @Test
    void appliesTimeoutAndMaxRowsPlusOne() throws Exception {
        Connection target = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(target.prepareStatement("select 1")).thenReturn(statement);

        Connection wrapped = QueryTimeoutConnection.wrap(target, Duration.ofMillis(1500), 100);
        wrapped.prepareStatement("select 1");

        verify(statement).setQueryTimeout(2);
        verify(statement).setMaxRows(101);
    }

    @Test
    void zeroLimitsLeaveStatementUntouched() throws Exception {
        Connection target = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(target.prepareStatement("select 1")).thenReturn(statement);

        QueryTimeoutConnection.wrap(target, Duration.ZERO, 0).prepareStatement("select 1");

        verify(statement, never()).setQueryTimeout(org.mockito.ArgumentMatchers.anyInt());
        verify(statement, never()).setMaxRows(org.mockito.ArgumentMatchers.anyInt());
    }
}
