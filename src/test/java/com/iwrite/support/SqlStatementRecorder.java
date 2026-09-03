package com.iwrite.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recording seam for query-shape invariants.
 *
 * <p>Non-enumeration is not proven by the response alone: two denials that return the same
 * {@code Book not found} still differ observably when one of them runs extra statements against the
 * resource. This inspector captures the SQL a call actually issued, so a test can compare the shape
 * of two denials instead of only their exceptions.
 *
 * <p>It hooks the statement rather than a repository method, so the invariant survives a reshaping of
 * the queries behind the authorization boundary.
 */
public class SqlStatementRecorder implements StatementInspector {

    private final List<String> recorded = new CopyOnWriteArrayList<>();
    private volatile boolean recording;

    /** Records, in order, every statement issued while {@code call} runs. */
    public List<String> recordStatementsOf(Runnable call) {
        recorded.clear();
        recording = true;
        try {
            call.run();
        } finally {
            recording = false;
        }
        return List.copyOf(recorded);
    }

    public void reset() {
        recording = false;
        recorded.clear();
    }

    @Override
    public String inspect(String sql) {
        if (recording) {
            recorded.add(sql);
        }
        return sql;
    }
}
