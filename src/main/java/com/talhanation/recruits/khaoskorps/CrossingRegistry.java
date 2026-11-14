package com.talhanation.recruits.khaoskorps;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who may cross a risky narrow spot (a one-block bridge, a one-wide ridge with deadly drops on both
 * sides): one following recruit of a group at a time, the rest queued in the order they asked.
 *
 * - The first to ask a free spot holds it; a recruit refused is queued, and while the spot is free
 *   only the first in the queue may take it.
 * - The holder keeps it while it makes progress: getting a block nearer the spot before it is on
 *   it, moving a block along it while on it. After {@link #STALL_TICKS} without progress it loses
 *   the spot, and asks again at the back of the queue.
 * - It also loses it once it leaves the spot after being on it (crossed, or turned back), or when it
 *   is gone: dead, removed, no longer following, mounted.
 * - A queued recruit that hasn't asked for {@link #FORGET_TICKS} is dropped from the queue.
 *
 * Plain Java, covered by JUnit; the game side (CrossingLocks) tells it where the holders are.
 */
public final class CrossingRegistry {

    public static final int STALL_TICKS = 200;
    public static final int FORGET_TICKS = 60;

    /** A spot, for one following group (its owner), named by its two ends in either order. */
    public record Spot(UUID group, long endA, long endB) {
        public static Spot of(UUID group, long end, long otherEnd) {
            return new Spot(group, Math.min(end, otherEnd), Math.max(end, otherEnd));
        }
    }

    /** Where a holder is, as the game sees it. {@code present} is false when it is gone or no longer following. */
    public record Status(boolean present, boolean onSpot, double x, double y, double z, double distanceToSpot) {
        public static final Status GONE = new Status(false, false, 0, 0, 0, 0);
    }

    public interface Holders {
        Status status(UUID holder, Spot spot);
    }

    private static final class Lock {
        @Nullable UUID holder;
        boolean entered;
        long progressTick;
        double px, py, pz;
        double bestDistance;
        final LinkedHashMap<UUID, Long> queue = new LinkedHashMap<>();
    }

    private final Map<Spot, Lock> locks = new HashMap<>();

    /** Whether {@code recruit} may cross the spot now; one refused is queued, or kept in its place. */
    public boolean request(Spot spot, UUID recruit, long now, double distanceToSpot) {
        Lock lock = this.locks.computeIfAbsent(spot, s -> new Lock());
        if (recruit.equals(lock.holder)) return true;
        if (lock.holder == null) {
            UUID first = this.firstStillAsking(lock, now);
            if (first == null || first.equals(recruit)) {
                lock.queue.remove(recruit);
                lock.holder = recruit;
                lock.entered = false;
                lock.progressTick = now;
                lock.bestDistance = distanceToSpot;
                lock.px = Double.NaN;
                return true;
            }
        }
        lock.queue.put(recruit, now);
        return false;
    }

    /** Checks every holder; called once a tick. */
    public void tick(long now, Holders holders) {
        for (Iterator<Map.Entry<Spot, Lock>> it = this.locks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Spot, Lock> entry = it.next();
            Lock lock = entry.getValue();
            if (lock.holder != null) this.check(entry.getKey(), lock, now, holders);
            if (lock.holder == null) {
                this.firstStillAsking(lock, now);
                if (lock.queue.isEmpty()) it.remove();
            }
        }
    }

    @Nullable
    public UUID holder(Spot spot) {
        Lock lock = this.locks.get(spot);
        return lock == null ? null : lock.holder;
    }

    public int queued(Spot spot) {
        Lock lock = this.locks.get(spot);
        return lock == null ? 0 : lock.queue.size();
    }

    public void clear() {
        this.locks.clear();
    }

    private void check(Spot spot, Lock lock, long now, Holders holders) {
        Status status = holders.status(lock.holder, spot);
        if (!status.present() || (lock.entered && !status.onSpot())) {
            lock.holder = null;
            return;
        }
        boolean progress;
        if (status.onSpot()) {
            if (!lock.entered || Double.isNaN(lock.px)) {
                lock.entered = true;
                progress = true;
            } else {
                double dx = status.x() - lock.px, dy = status.y() - lock.py, dz = status.z() - lock.pz;
                progress = dx * dx + dy * dy + dz * dz >= 1.0;
            }
        } else {
            progress = status.distanceToSpot() <= lock.bestDistance - 1.0;
            if (progress) lock.bestDistance = status.distanceToSpot();
        }
        if (progress) {
            lock.progressTick = now;
            lock.px = status.x();
            lock.py = status.y();
            lock.pz = status.z();
        } else if (now - lock.progressTick > STALL_TICKS) {
            lock.holder = null;
        }
    }

    /** The first queued recruit that has asked within {@link #FORGET_TICKS}; older ones are dropped. */
    @Nullable
    private UUID firstStillAsking(Lock lock, long now) {
        for (Iterator<Map.Entry<UUID, Long>> it = lock.queue.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> waiting = it.next();
            if (now - waiting.getValue() > FORGET_TICKS) {
                it.remove();
                continue;
            }
            return waiting.getKey();
        }
        return null;
    }
}
