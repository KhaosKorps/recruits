package com.talhanation.recruits.khaoskorps;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KhaosKorps fork: gap P6 (AUDT-261113-Recruits_Security_Port_Check_By_Anon). A message
 * handler that throws inside Forge's enqueueWork only fails the returned future, which nothing
 * reads, so the failure left no trace. Each failure must be reported with what failed and why.
 */
class HandlerFailuresTest {

    private final List<String> reported = new ArrayList<>();
    private final List<Throwable> causes = new ArrayList<>();

    private void report(String what, Throwable cause) {
        reported.add(what);
        causes.add(cause);
    }

    @Test
    void aHandlerThatThrowsIsReportedWithWhatFailedAndTheOriginalCause() {
        CompletableFuture<Void> work = CompletableFuture.runAsync(() -> {
            throw new IllegalStateException("boom");
        }, Runnable::run);

        HandlerFailures.reportIfFailed(work, () -> "MessageMovement from Steve", this::report);

        assertEquals(List.of("MessageMovement from Steve"), reported);
        assertTrue(causes.get(0) instanceof IllegalStateException, "the handler's own exception, not Java's wrapper");
        assertEquals("boom", causes.get(0).getMessage());
    }

    @Test
    void aHandlerThatFailsLaterIsReportedWhenItFails() {
        CompletableFuture<Void> work = new CompletableFuture<>();
        HandlerFailures.reportIfFailed(work, () -> "MessageMovement from Steve", this::report);
        assertTrue(reported.isEmpty(), "nothing reported before the work runs");

        work.completeExceptionally(new IllegalArgumentException("late"));

        assertEquals(List.of("MessageMovement from Steve"), reported);
        assertEquals("late", causes.get(0).getMessage());
    }

    @Test
    void aHandlerThatSucceedsReportsNothing() {
        HandlerFailures.reportIfFailed(CompletableFuture.completedFuture(null), () -> "MessageMovement from Steve", this::report);
        assertTrue(reported.isEmpty());
    }
}
