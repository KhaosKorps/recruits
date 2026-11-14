package com.talhanation.recruits.khaoskorps;

/**
 * How one following recruit fared over a window of ticks, as one row of the path log's recruits
 * file. The server tick adds each tick; the navigation and follow goal add their events. A row is
 * written every 100 ticks, or fewer when the recruit leaves, and the counts start again. Server
 * thread only. Plain Java, covered by JUnit.
 */
public final class PathLogSummary {

    public static final String HEADER = "tick,recruit,window_ticks,follow_ticks,goal_off_ticks,goal_starts,stall_ticks,"
            + "giveup_skips,inflight_reuses,dist_avg,dist_max,speed_milli,dropped_rows";

    public static final int WINDOW_TICKS = 100;

    private int windowTicks;
    private int followTicks;
    private int goalOffTicks;
    private int goalStarts;
    private int stallTicks;
    private int giveupSkips;
    private int inflightReuses;
    private long distanceSum;
    private int distanceSamples;
    private int distanceMax = -1;
    private boolean goalRunning;

    /**
     * One tick: whether the recruit is in Follow Me, the distance to its owner (negative when
     * there's no owner in its level), and whether the owner is past the follow stop distance.
     * A tick in Follow Me with the follow goal not running and the owner past the stop distance
     * is a goal-off tick: the recruit should be following but isn't.
     */
    public void tick(boolean following, double ownerDistance, boolean ownerPastStop) {
        this.windowTicks++;
        if (!following) return;
        this.followTicks++;
        if (!this.goalRunning && ownerPastStop) this.goalOffTicks++;
        if (ownerDistance >= 0) {
            int tenths = PathLogRecord.tenths(ownerDistance);
            this.distanceSum += tenths;
            this.distanceSamples++;
            this.distanceMax = Math.max(this.distanceMax, tenths);
        }
    }

    public void goalStarted() {
        this.goalStarts++;
        this.goalRunning = true;
    }

    public void goalStopped() {
        this.goalRunning = false;
    }

    /** The navigation held a path still being searched, so it gave no walk input this tick. */
    public void stalled() {
        this.stallTicks++;
    }

    /** A follow move was refused because the navigation had given up on its target. */
    public void gaveUp() {
        this.giveupSkips++;
    }

    /** A request was answered with the search already running for the same positions. */
    public void reused() {
        this.inflightReuses++;
    }

    public boolean windowFull() {
        return this.windowTicks >= WINDOW_TICKS;
    }

    public boolean isEmpty() {
        return this.windowTicks == 0;
    }

    /** The row for this window, then the counts start again; whether the goal runs carries over. */
    public String row(long tick, String recruit, int speedMilli, long droppedRows) {
        int average = this.distanceSamples == 0 ? -1 : (int) Math.round((double) this.distanceSum / this.distanceSamples);
        String row = tick + "," + recruit + "," + this.windowTicks + "," + this.followTicks + "," + this.goalOffTicks + ","
                + this.goalStarts + "," + this.stallTicks + "," + this.giveupSkips + "," + this.inflightReuses + ","
                + average + "," + this.distanceMax + "," + speedMilli + "," + droppedRows;
        this.windowTicks = 0;
        this.followTicks = 0;
        this.goalOffTicks = 0;
        this.goalStarts = 0;
        this.stallTicks = 0;
        this.giveupSkips = 0;
        this.inflightReuses = 0;
        this.distanceSum = 0;
        this.distanceSamples = 0;
        this.distanceMax = -1;
        return row;
    }
}
