package com.liverpool.ms_clp_blp.shared.logging;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the reason this class exists: Logback 1.5's MDC is a plain {@code ThreadLocal}, so the
 * correlation fields silently vanish inside the virtual-thread fan-outs used to resolve blocks.
 */
class MdcPropagationTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void mdcIsNotInheritedByVirtualThreads_whichIsWhyWrappingIsNeeded() throws Exception {
        MDC.put("requestId", "req-1");

        assertThat(submit(() -> MDC.get("requestId"))).isNull();
    }

    @Test
    void wrap_carriesTheCallersMdcIntoTheTask() throws Exception {
        MDC.put("requestId", "req-1");
        MDC.put("correlationId", "corr-1");

        assertThat(submit(MdcPropagation.wrap(() -> MDC.get("requestId") + "/" + MDC.get("correlationId"))))
                .isEqualTo("req-1/corr-1");
    }

    @Test
    void wrap_clearsTheContextAfterTheTaskSoThreadsAreNeverReusedWithForeignIdentity() throws Exception {
        MDC.put("requestId", "req-1");

        String leaked = submit(() -> {
            MdcPropagation.wrap(() -> MDC.get("requestId")).call();
            return MDC.get("requestId");
        });

        assertThat(leaked).isNull();
    }

    @Test
    void wrap_returnsTheTaskUntouched_whenThereIsNoContextToCarry() {
        java.util.concurrent.Callable<String> task = () -> "value";

        assertThat(MdcPropagation.wrap(task)).isSameAs(task);
    }

    private static <T> T submit(java.util.concurrent.Callable<T> task) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<T> future = executor.submit(task);
            return future.get();
        }
    }
}
