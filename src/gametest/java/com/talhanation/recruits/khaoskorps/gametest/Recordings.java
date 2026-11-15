package com.talhanation.recruits.khaoskorps.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared pieces of the run recordings ({@link GroupRecorder}, {@link PathTraceGameTests}): which
 * recording a recruit's searches go to, the arena's blocks, and the JSON helpers.
 */
final class Recordings {

    private static final Map<UUID, SearchTracer.Sink> BY_RECRUIT = new ConcurrentHashMap<>();

    private Recordings() {
    }

    /** Where a recruit's searches are recorded, so the detour planner's searches for it are recorded too; null when nowhere. */
    static SearchTracer.Sink recorderFor(Mob mob) {
        return BY_RECRUIT.get(mob.getUUID());
    }

    /** Records {@code mob}'s searches into {@code sink} until {@link #forget}. */
    static void recordInto(Mob mob, SearchTracer.Sink sink) {
        BY_RECRUIT.put(mob.getUUID(), sink);
    }

    static void forget(Mob mob) {
        BY_RECRUIT.remove(mob.getUUID());
    }

    /** Every block of a 64 x 24 x 64 arena, x fastest, then z, then y: '#' solid, 'w' water, 'l' lava, '.' open. */
    static String blocks(GameTestHelper helper) {
        return blocks(helper, 64, 24, 64);
    }

    /** Every block of an arena of the given size, x fastest, then z, then y: '#' solid, 'w' water, 'l' lava, '.' open. */
    static String blocks(GameTestHelper helper, int sizeX, int sizeY, int sizeZ) {
        StringBuilder out = new StringBuilder(sizeX * sizeY * sizeZ);
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
                    BlockState state = helper.getLevel().getBlockState(pos);
                    if (state.getFluidState().is(FluidTags.LAVA)) out.append('l');
                    else if (state.getFluidState().is(FluidTags.WATER)) out.append('w');
                    else if (!state.getCollisionShape(helper.getLevel(), pos).isEmpty()) out.append('#');
                    else out.append('.');
                }
            }
        }
        return out.toString();
    }

    static String xyz(int x, int y, int z) {
        return "[" + x + "," + y + "," + z + "]";
    }

    static String num(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static String quote(String text) {
        if (text == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
