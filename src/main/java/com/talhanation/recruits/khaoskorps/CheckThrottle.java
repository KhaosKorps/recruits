package com.talhanation.recruits.khaoskorps;

/**
 * Lets a repeated check run once every {@code interval} calls, staggered by a seed (an entity id),
 * so many mobs that start together spread their checks out instead of all checking on the same
 * call. An interval of 1 runs the check on every call. A changed interval starts again from the
 * stagger, so a shorter one takes effect at once and a longer one spreads the checks out again.
 * One per goal; plain Java, covered by JUnit.
 */
public final class CheckThrottle {

    private int countdown = -1;
    private int interval = -1;

    /** Whether the check is due on this call; {@code interval} is in calls, {@code stagger} any int. */
    public boolean due(int interval, int stagger) {
        int every = Math.max(1, interval);
        if (every != this.interval) {
            this.interval = every;
            this.countdown = Math.floorMod(stagger, every);
        }
        if (this.countdown > 0) {
            this.countdown--;
            return false;
        }
        this.countdown = every - 1;
        return true;
    }

    /**
     * For a goal method vanilla calls every second tick: whether the check is due, with the
     * interval given in game ticks and rounded up to whole calls.
     */
    public boolean dueEveryTicks(int ticks, int stagger) {
        return this.due((ticks + 1) / 2, stagger);
    }
}
