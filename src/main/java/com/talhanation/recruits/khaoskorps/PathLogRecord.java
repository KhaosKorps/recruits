package com.talhanation.recruits.khaoskorps;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One path search, as one row of the path log's paths file: whole numbers and one-letter codes,
 * so no field ever needs quoting. A record is filled in three places: at the request (server
 * thread), by the search (a path worker or the server thread) and when the result arrives back
 * (server thread). Each hands it on through the executor or server.execute, which orders the
 * writes, so the fields are plain. Distances are in tenths of a block; -1 means not known.
 * Plain Java, covered by JUnit.
 */
public final class PathLogRecord {

    public static final String HEADER = "seq,settings_v,req_tick,recruit,nav,purpose,dist,dy,budget,queue,"
            + "nodes,reached,len,end_dist,end_dy,thread,cpu_us,wait_ticks,outcome";

    /** nav: the recruit navigation, or vanilla's (a mounted recruit follows with its horse's). */
    public static final char NAV_RECRUIT = 'R', NAV_VANILLA = 'V';
    /** purpose: following the owner, walking a detour waypoint, planning a detour, melee, anything else. */
    public static final char FOLLOW = 'F', WAYPOINT = 'W', DETOUR = 'D', MELEE = 'M', OTHER = 'O';
    /**
     * thread: a path worker; the server thread because the worker queue was full; the server
     * thread because the workers were shut down; inline on the server thread with async searches
     * off or on vanilla's navigation; or never run.
     */
    public static final char WORKER = 'W', QUEUE_FULL = 'M', WORKERS_OFF = 'S', INLINE = 'I', NOT_RUN = '-';
    /** outcome: walked; dropped because a newer path replaced it; no path; the search failed. */
    public static final char APPLIED = 'A', DISCARDED = 'D', NO_PATH = 'N', ERROR = 'E';

    private final long seq;
    private final int settingsVersion;
    private final long requestTick;
    private final String recruit;
    private final char nav;
    private final char purpose;
    private final int distance;
    private final int dy;
    private final int budget;
    private final int queue;
    private final long requestNanos;

    private int nodes = -1;
    private boolean reached;
    private int length;
    private int endDistance = -1;
    private int endDy;
    private char thread = NOT_RUN;
    private long cpuMicros = -1;
    private boolean failed;
    private final AtomicBoolean written = new AtomicBoolean();

    public PathLogRecord(long seq, int settingsVersion, long requestTick, String recruit, char nav, char purpose,
                         double distance, int dy, int budget, int queue, long requestNanos) {
        this.seq = seq;
        this.settingsVersion = settingsVersion;
        this.requestTick = requestTick;
        this.recruit = recruit;
        this.nav = nav;
        this.purpose = purpose;
        this.distance = tenths(distance);
        this.dy = dy;
        this.budget = budget;
        this.queue = queue;
        this.requestNanos = requestNanos;
    }

    /** The search ran on {@code thread} for {@code cpuMicros}, taking {@code nodes} nodes off the open set (-1 if not known). */
    public void searched(char thread, int nodes, long cpuMicros) {
        this.thread = thread;
        this.nodes = nodes;
        this.cpuMicros = cpuMicros;
    }

    /** The path it returned: whether it reaches the target, its node count, and where it ends relative to the target. */
    public void result(boolean reached, int length, double endDistance, int endDy) {
        this.reached = reached;
        this.length = length;
        this.endDistance = tenths(endDistance);
        this.endDy = endDy;
    }

    /** The search threw; the row's outcome is then {@link #ERROR} whatever arrives. */
    public void failed() {
        this.failed = true;
    }

    public boolean hasFailed() {
        return this.failed;
    }

    /** Whether the search has run, found or failed. */
    public boolean ran() {
        return this.thread != NOT_RUN;
    }

    public long requestNanos() {
        return this.requestNanos;
    }

    /**
     * The row, once: the first call returns it, any later call returns null, so a record can't
     * be written twice from two places. {@code waitTicks} is -1 when no result came back.
     */
    public String finish(long waitTicks, char outcome) {
        if (!this.written.compareAndSet(false, true)) return null;
        return this.seq + "," + this.settingsVersion + "," + this.requestTick + "," + this.recruit + ","
                + this.nav + "," + this.purpose + "," + this.distance + "," + this.dy + "," + this.budget + "," + this.queue + ","
                + this.nodes + "," + (this.reached ? 1 : 0) + "," + this.length + "," + this.endDistance + "," + this.endDy + ","
                + this.thread + "," + this.cpuMicros + "," + waitTicks + "," + (this.failed ? ERROR : outcome);
    }

    public long requestTick() {
        return this.requestTick;
    }

    static int tenths(double blocks) {
        return blocks < 0 || Double.isNaN(blocks) ? -1 : (int) Math.min(Integer.MAX_VALUE, Math.round(blocks * 10.0));
    }
}
