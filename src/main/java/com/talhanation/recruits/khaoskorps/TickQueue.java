package com.talhanation.recruits.khaoskorps;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;

/**
 * Tasks to run on one thread after a delay counted in ticks of that thread. Tasks may be submitted
 * from any thread; {@link #tick} runs the due ones, in order, on the thread that calls it. A task
 * that throws is reported and doesn't stop the others. Plain Java, covered by JUnit.
 */
public final class TickQueue {

    private record Submitted(Runnable task, int delayTicks) {
    }

    private record Due(long tick, long order, Runnable task) {
    }

    private final Queue<Submitted> submitted = new ConcurrentLinkedQueue<>();
    private final PriorityQueue<Due> due = new PriorityQueue<>(Comparator.comparingLong(Due::tick).thenComparingLong(Due::order));
    private long order;

    /** Queues {@code task} to run {@code delayTicks} ticks after the tick that picks it up. Any thread. */
    public void submit(Runnable task, int delayTicks) {
        this.submitted.add(new Submitted(task, Math.max(0, delayTicks)));
    }

    /** The ticks a delay in milliseconds takes at 20 ticks a second, rounded up. */
    public static int ticksFor(long delayMillis) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, (delayMillis + 49) / 50));
    }

    /**
     * Picks up the tasks submitted since the last call, then runs every task due at or before
     * {@code now}, earliest first, and in submission order when due together. Call from one thread only.
     */
    public void tick(long now, BiConsumer<Runnable, RuntimeException> onFailure) {
        Submitted next;
        while ((next = this.submitted.poll()) != null) {
            this.due.add(new Due(now + next.delayTicks(), this.order++, next.task()));
        }
        while (!this.due.isEmpty() && this.due.peek().tick() <= now) {
            Runnable task = this.due.poll().task();
            try {
                task.run();
            } catch (RuntimeException failure) {
                onFailure.accept(task, failure);
            }
        }
    }

    /** Drops every waiting task. */
    public void clear() {
        this.submitted.clear();
        this.due.clear();
    }

    /** How many tasks are waiting. */
    public int pending() {
        return this.submitted.size() + this.due.size();
    }
}
