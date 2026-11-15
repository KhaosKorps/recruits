package com.talhanation.recruits.khaoskorps;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * The blocks players have placed or broken lately, so a remembered failed detour plan is forgotten once a
 * player has changed the ground it searched: the plan after a bridge is built over a gap can use it at once.
 * Keeps the latest {@link #MAX_CHANGES}. Server thread only.
 */
public final class FailedSearches {

    /** How many of the latest block changes are kept. */
    static final int MAX_CHANGES = 1024;

    private record Change(BlockPos pos, long tick) {
    }

    private static final ArrayDeque<Change> CHANGES = new ArrayDeque<>();

    private FailedSearches() {
    }

    /**
     * Whether a player has placed or broken a block, since game tick {@code since}, inside the box spanning
     * {@code a} and {@code b} and widened by {@code margin} blocks every way.
     *
     * @param a one corner of the ground searched, such as the search's start
     * @param b the other corner, such as where it headed
     * @param margin how far beyond the box a change still counts, in blocks
     * @param since the game tick the failure was found on
     * @return true if such a change was made
     */
    public static boolean changedSince(BlockPos a, BlockPos b, int margin, long since) {
        int minX = Math.min(a.getX(), b.getX()) - margin, maxX = Math.max(a.getX(), b.getX()) + margin;
        int minY = Math.min(a.getY(), b.getY()) - margin, maxY = Math.max(a.getY(), b.getY()) + margin;
        int minZ = Math.min(a.getZ(), b.getZ()) - margin, maxZ = Math.max(a.getZ(), b.getZ()) + margin;
        Iterator<Change> newestFirst = CHANGES.descendingIterator();
        while (newestFirst.hasNext()) {
            Change change = newestFirst.next();
            if (change.tick() < since) return false;
            BlockPos p = change.pos();
            if (p.getX() >= minX && p.getX() <= maxX && p.getY() >= minY && p.getY() <= maxY && p.getZ() >= minZ && p.getZ() <= maxZ) return true;
        }
        return false;
    }

    /** Notes a block change at {@code pos} on game tick {@code tick}. */
    static void note(BlockPos pos, long tick) {
        CHANGES.addLast(new Change(pos.immutable(), tick));
        while (CHANGES.size() > MAX_CHANGES) CHANGES.removeFirst();
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() != null && !event.getLevel().isClientSide()) note(event.getPos(), event.getLevel().getLevelData().getGameTime());
    }

    /** Also gets the two-block placings (beds, doors), which are its subclass. */
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player && !event.getLevel().isClientSide()) note(event.getPos(), event.getLevel().getLevelData().getGameTime());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CHANGES.clear();
    }
}
