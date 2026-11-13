package com.talhanation.recruits.khaoskorps;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * KhaosKorps fork: makes a failed message handler visible (gap P6).
 *
 * Forge's NetworkEvent.Context.enqueueWork runs a handler on the game thread and returns a
 * future. If the handler throws, only that future fails, and nothing reads it, so neither a
 * forged packet that throws nor a real bug left a trace.
 *
 * Plain Java, covered by JUnit.
 */
public final class HandlerFailures {

    private HandlerFailures() {
    }

    /**
     * When {@code work} fails, passes {@code what} and the handler's own exception to
     * {@code report}. {@code what} is only built on failure, so a working handler costs nothing.
     */
    public static void reportIfFailed(CompletableFuture<?> work, Supplier<String> what, BiConsumer<String, Throwable> report) {
        work.whenComplete((ignored, failure) -> {
            if (failure == null) return;
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            report.accept(what.get(), cause);
        });
    }
}
