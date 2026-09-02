package com.iwrite.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Deterministic interleaving seam for concurrency tests.
 *
 * <p>It commits a concurrent change from an independent connection immediately before the chosen SQL
 * statement runs, so a race between two statements of the same transaction becomes reproducible
 * instead of timing dependent. It hooks the statement itself rather than a repository method, so a
 * test keeps proving the invariant even when the queries behind it are reshaped.
 *
 * <p>{@link #hasFired()} exists so a test can fail loudly when its trigger stops matching, instead of
 * passing without the race it claims to reproduce.
 */
public class SqlInterleaveHook implements StatementInspector {

    private final AtomicBoolean fired = new AtomicBoolean(true);
    private volatile Predicate<String> trigger;
    private volatile Runnable action;

    /** Runs {@code action} exactly once, immediately before the first statement matching {@code trigger}. */
    public void armOn(Predicate<String> trigger, Runnable action) {
        this.trigger = trigger;
        this.action = action;
        fired.set(false);
    }

    public void disarm() {
        trigger = null;
        action = null;
    }

    public boolean hasFired() {
        return fired.get();
    }

    @Override
    public String inspect(String sql) {
        Predicate<String> armedTrigger = trigger;
        Runnable armedAction = action;
        if (armedTrigger != null && armedAction != null && armedTrigger.test(sql) && fired.compareAndSet(false, true)) {
            disarm();
            armedAction.run();
        }
        return sql;
    }
}
