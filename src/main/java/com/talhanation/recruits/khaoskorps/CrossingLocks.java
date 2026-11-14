package com.talhanation.recruits.khaoskorps;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CrossOneAtATime: a following group crosses a risky narrow spot one recruit at a time. A risky
 * narrow spot is a run of path points each with a drop deeper than the fall distance, or lava, on
 * both sides: a one-block bridge, a one-wide ridge. When a follower's new path crosses one, it asks
 * the group's {@link CrossingRegistry}; if another holds the spot, the path is cut back to the last
 * point before it with no such drop within the edge margin, and the follower waits there, asking
 * again with each new path. A follower already on the spot is never cut back. Each tick the holders'
 * positions go to the registry, which releases a spot once its holder has crossed, stalled for ten
 * seconds, or gone. Server thread only.
 */
public final class CrossingLocks {

    private static final CrossingRegistry REGISTRY = new CrossingRegistry();
    private static final Map<CrossingRegistry.Spot, SpotPlace> PLACES = new HashMap<>();

    private record SpotPlace(ServerLevel level, List<BlockPos> points) {
    }

    /** Where along a path a risky narrow spot runs, by point index, and its points. */
    record Found(int entry, int exit, List<BlockPos> points) {
    }

    private CrossingLocks() {
    }

    /**
     * How many of a follower's path points to keep: all of them, unless the path crosses a risky
     * narrow spot another follower of the group holds; then up to the last safe point before it.
     */
    public static int pointsToKeep(ServerLevel level, Mob mob, UUID group, Path path, int maxFall, int keepBack) {
        int count = path.getNodeCount();
        Found found = firstSpot(level, path, maxFall);
        if (found == null) return count;
        List<BlockPos> points = found.points();
        CrossingRegistry.Spot spot = CrossingRegistry.Spot.of(group, points.get(0).asLong(), points.get(points.size() - 1).asLong());
        PLACES.putIfAbsent(spot, new SpotPlace(level, points));
        double distance = distanceTo(mob, points);
        boolean granted = REGISTRY.request(spot, mob.getUUID(), level.getGameTime(), distance);
        if (granted || distance <= 1.0) return count;
        int clearance = Math.max(1, keepBack) + 1;
        for (int i = found.entry() - 1; i >= 0; i--) {
            Node node = path.getNode(i);
            if (EdgeProbe.distanceToDangerousDrop(level, node.x, node.y, node.z, maxFall, clearance, null) == EdgeProbe.NONE) {
                return i + 1;
            }
        }
        return 0;
    }

    /** The first run of path points with a dangerous drop on both sides across the way the path runs, or null. */
    @Nullable
    static Found firstSpot(BlockGetter level, Path path, int maxFall) {
        int count = path.getNodeCount();
        int entry = -1;
        List<BlockPos> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Node node = path.getNode(i);
            Node from = i + 1 < count ? node : path.getNode(i - 1 < 0 ? 0 : i - 1);
            Node to = i + 1 < count ? path.getNode(i + 1) : node;
            int dx = Integer.signum(to.x - from.x);
            int dz = Integer.signum(to.z - from.z);
            boolean narrow = (dx != 0 || dz != 0)
                    && EdgeProbe.isDangerousDropFrom(level, node.x, node.y, node.z, -dz, dx, maxFall)
                    && EdgeProbe.isDangerousDropFrom(level, node.x, node.y, node.z, dz, -dx, maxFall);
            if (narrow) {
                if (entry < 0) entry = i;
                points.add(new BlockPos(node.x, node.y, node.z));
            } else if (entry >= 0) {
                return new Found(entry, i - 1, points);
            }
        }
        return entry < 0 ? null : new Found(entry, count - 1, points);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        REGISTRY.tick(event.getServer().overworld().getGameTime(), CrossingLocks::status);
        PLACES.keySet().removeIf(spot -> REGISTRY.holder(spot) == null && REGISTRY.queued(spot) == 0);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        REGISTRY.clear();
        PLACES.clear();
    }

    /** The holder of a spot as it stands: gone if dead, removed, mounted or no longer following. */
    private static CrossingRegistry.Status status(UUID holder, CrossingRegistry.Spot spot) {
        SpotPlace place = PLACES.get(spot);
        if (place == null) return CrossingRegistry.Status.GONE;
        Entity entity = place.level().getEntity(holder);
        if (!(entity instanceof AbstractRecruitEntity recruit) || !recruit.isAlive() || recruit.isPassenger() || !recruit.isFollowing()) {
            return CrossingRegistry.Status.GONE;
        }
        double distance = distanceTo(recruit, place.points());
        return new CrossingRegistry.Status(true, distance <= 1.0, recruit.getX(), recruit.getY(), recruit.getZ(), distance);
    }

    /** Horizontal distance from the mob to the nearest of the spot's points, counting only points within a block of its height. */
    private static double distanceTo(Mob mob, List<BlockPos> points) {
        double best = Double.MAX_VALUE;
        for (BlockPos point : points) {
            double dx = mob.getX() - (point.getX() + 0.5);
            double dz = mob.getZ() - (point.getZ() + 0.5);
            double dy = Math.abs(mob.getY() - point.getY());
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            best = Math.min(best, dy <= 1.0 ? horizontal : horizontal + dy);
        }
        return best;
    }

    /** For tests: who holds the group's spot named by these two ends, or null. */
    @Nullable
    public static UUID holder(UUID group, BlockPos end, BlockPos otherEnd) {
        return REGISTRY.holder(CrossingRegistry.Spot.of(group, end.asLong(), otherEnd.asLong()));
    }
}
