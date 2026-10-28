package com.liverpool.ms_clp_blp.shared.logging;

import java.util.Map;
import java.util.concurrent.Callable;

import org.slf4j.MDC;

/**
 * Carries the request's MDC across a thread hand-off.
 *
 * <p>Logback's {@code LogbackMDCAdapter} stopped backing the context with an
 * {@code InheritableThreadLocal} (it leaked across pooled threads), so a task submitted to
 * {@code Executors.newVirtualThreadPerTaskExecutor()} starts with an <em>empty</em> MDC — verified
 * against Logback 1.5.34. Every fan-out in this service resolves blocks on virtual threads, which
 * means precisely the lines worth correlating (a degraded block, a failed upstream call) were the
 * ones losing {@code requestId}/{@code correlationId}/{@code operation}.</p>
 *
 * <p>Wrap the task at the submit site — where the caller thread still holds the context — and the
 * copy is installed for the duration of the task and cleared afterwards, so a virtual thread is
 * never observed carrying another request's identity.</p>
 */
public final class MdcPropagation {

    private MdcPropagation() {
    }

    /**
     * Wraps a task so it runs with a snapshot of the calling thread's MDC.
     *
     * @param task the work to run on another thread
     * @param <T>  the task's result type
     * @return the task, decorated to install and clear the captured context
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        if (context == null || context.isEmpty()) {
            return task;
        }
        return () -> {
            MDC.setContextMap(context);
            try {
                return task.call();
            } finally {
                MDC.clear();
            }
        };
    }
}
