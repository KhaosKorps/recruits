package com.talhanation.recruits.khaoskorps;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickQueueTest {

    private static final java.util.function.BiConsumer<Runnable, Throwable> NO_FAILURE =
            (task, failure) -> { throw new AssertionError("unexpected failure", failure); };

    @Test
    void aTaskRunsOnTheTickItIsDueAndNotBefore() {
        TickQueue queue = new TickQueue();
        List<Long> ranAt = new ArrayList<>();
        long[] now = {100};
        queue.submit(() -> ranAt.add(now[0]), 2);
        for (; now[0] <= 103; now[0]++) queue.tick(now[0], NO_FAILURE);
        assertEquals(List.of(102L), ranAt);
    }

    @Test
    void tasksDueTogetherRunInTheOrderTheyWereSubmitted() {
        TickQueue queue = new TickQueue();
        List<String> ran = new ArrayList<>();
        queue.submit(() -> ran.add("later"), 3);
        queue.submit(() -> ran.add("first"), 1);
        queue.submit(() -> ran.add("second"), 1);
        for (long tick = 0; tick <= 3; tick++) queue.tick(tick, NO_FAILURE);
        assertEquals(List.of("first", "second", "later"), ran);
    }

    @Test
    void aTaskThatThrowsAnErrorIsReportedAndTheOthersStillRun() {
        TickQueue queue = new TickQueue();
        List<String> ran = new ArrayList<>();
        List<Object> failures = new ArrayList<>();
        queue.submit(() -> { throw new NoClassDefFoundError("a class another mod removed"); }, 0);
        queue.submit(() -> ran.add("after"), 0);
        queue.tick(5, (task, failure) -> failures.add(failure));
        assertEquals(List.of("after"), ran);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0) instanceof NoClassDefFoundError, String.valueOf(failures.get(0)));
    }

    @Test
    void runningOutOfMemoryIsNotSwallowed() {
        TickQueue queue = new TickQueue();
        queue.submit(() -> { throw new OutOfMemoryError("test"); }, 0);
        org.junit.jupiter.api.Assertions.assertThrows(OutOfMemoryError.class, () -> queue.tick(5, (task, failure) -> { }));
    }

    @Test
    void aTaskThatThrowsIsReportedAndTheOthersStillRun() {
        TickQueue queue = new TickQueue();
        List<String> ran = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        queue.submit(() -> { throw new IllegalStateException("broken task"); }, 0);
        queue.submit(() -> ran.add("after"), 0);
        queue.tick(5, (task, failure) -> failures.add(failure));
        assertEquals(List.of("after"), ran);
        assertEquals(1, failures.size());
        assertEquals("broken task", failures.get(0).getMessage());
    }

    @Test
    void clearDropsEveryWaitingTask() {
        TickQueue queue = new TickQueue();
        queue.submit(() -> { throw new AssertionError("a cleared task ran"); }, 1);
        queue.tick(0, NO_FAILURE);
        queue.submit(() -> { throw new AssertionError("a cleared task ran"); }, 0);
        queue.clear();
        assertEquals(0, queue.pending());
        queue.tick(10, NO_FAILURE);
    }

    @Test
    void aDelayInMillisecondsBecomesTicksRoundedUp() {
        assertEquals(20, TickQueue.ticksFor(1000));
        assertEquals(10, TickQueue.ticksFor(500));
        assertEquals(1, TickQueue.ticksFor(1));
        assertEquals(0, TickQueue.ticksFor(0));
        assertTrue(TickQueue.ticksFor(-5) >= 0);
    }
}
