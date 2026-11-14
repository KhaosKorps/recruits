package com.talhanation.recruits.khaoskorps.gametest;

import com.google.common.collect.ImmutableSet;
import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import com.talhanation.recruits.khaoskorps.FollowSettings;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import com.talhanation.recruits.pathfinding.AsyncPath;
import com.talhanation.recruits.pathfinding.AsyncPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * Records real searches for the pathfinding visualiser: on rough ravine seed 1 at fall distance 3,
 * the follow search a recruit stuck on the north rim makes toward its owner across the ravine, with
 * the dirt-path preference as released (ORIGINAL) and off, and the follow search from the south side
 * where recruits that got round stopped. Each runs the real AsyncPathfinder with a
 * {@link TracingEvaluator}, the recruit evaluator with a recorder on it. The terrain and the searches
 * are written to pathtrace/ravine-seed1.json in the test server's folder.
 *
 * Optional, in a batch of its own: it checks only that every search ran and was recorded.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class PathTraceGameTests {

    private static final String TRACE = "khaoskorps_path_trace";
    private static final int RANGE = 32;
    private static final int BUDGET = 16 * RANGE;

    private record Search(String name, FollowSettings.DirtPathPreference dirtPath, int fromX, int fromZ, int toX, int toZ) {
    }

    private static final List<Search> SEARCHES = List.of(
            new Search("North rim to the owner, dirt-path cost as released (ORIGINAL)", FollowSettings.DirtPathPreference.ORIGINAL, 11, 21, 14, 56),
            new Search("North rim to the owner, dirt-path cost off", FollowSettings.DirtPathPreference.OFF, 11, 21, 14, 56),
            new Search("South side, where recruits that got round stopped, dirt-path cost off", FollowSettings.DirtPathPreference.OFF, 43, 49, 14, 56));

    @AfterBatch(batch = TRACE)
    public static void afterTrace(ServerLevel level) {
        resetForkSettings();
    }

    @GameTest(template = RavineGameTests.ARENA, timeoutTicks = 200, batch = TRACE, required = false)
    public static void recordSearchesAcrossARoughRavine(GameTestHelper helper) {
        RavineTerrain terrain = new RavineTerrain(1);
        terrain.build(helper);
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        List<TracingEvaluator> tracers = new ArrayList<>();
        List<Path> paths = new ArrayList<>();
        List<AbstractRecruitEntity> recruits = new ArrayList<>();
        helper.runAtTickTime(2, () -> {
            for (Search search : SEARCHES) {
                // The evaluator reads the dirt-path setting in prepare(), which findPath calls here, on the server thread.
                forkSettings(() -> {
                    KhaosKorpsServerConfig.MaxFallDistance.set(3);
                    KhaosKorpsServerConfig.DirtPathPreference.set(search.dirtPath());
                });
                AbstractRecruitEntity recruit = helper.spawn(ModEntityTypes.RECRUIT.get(), search.fromX(), terrain.stand[search.fromX()][search.fromZ()], search.fromZ());
                recruit.setNoAi(true);
                recruit.setOnGround(true);
                recruits.add(recruit);
                TracingEvaluator tracer = new TracingEvaluator();
                tracer.setCanOpenDoors(true);
                tracer.setCanPassDoors(true);
                tracer.setCanFloat(true);
                AsyncPathfinder finder = new AsyncPathfinder(tracer, BUDGET, () -> tracer, helper.getLevel());
                BlockPos from = recruit.blockPosition().above();
                int reach = RANGE + 16;
                PathNavigationRegion region = new PathNavigationRegion(helper.getLevel(), from.offset(-reach, -reach, -reach), from.offset(reach, reach, reach));
                BlockPos target = helper.absolutePos(new BlockPos(search.toX(), terrain.stand[search.toX()][search.toZ()], search.toZ()));
                Path path = finder.findPath(region, recruit, ImmutableSet.of(target), RANGE, 1, 1.0F);
                if (path == null) throw new GameTestAssertException("no search started for: " + search.name());
                tracers.add(tracer);
                paths.add(path);
            }
        });
        helper.onEachTick(() -> {
            if (paths.size() < SEARCHES.size()) return;
            for (Path path : paths) if (path instanceof AsyncPath async && !async.isProcessed()) return;
            recruits.forEach(AbstractRecruitEntity::discard);
            StringBuilder json = new StringBuilder();
            json.append("{\"seed\":1,\"size\":[64,24,64],\"maxFall\":3,\"margin\":").append(FollowSettings.current().edgeMarginBlocks())
                    .append(",\"range\":").append(RANGE).append(",\"budget\":").append(BUDGET)
                    .append(",\"blocks\":\"").append(blocks(helper)).append("\",\"searches\":[");
            for (int i = 0; i < SEARCHES.size(); i++) {
                if (i > 0) json.append(',');
                TracingEvaluator tracer = tracers.get(i);
                tracer.finish();
                if (tracer.pops.isEmpty()) throw new GameTestAssertException("nothing recorded for: " + SEARCHES.get(i).name());
                search(json, SEARCHES.get(i), terrain, tracer, paths.get(i), origin);
            }
            json.append("]}");
            java.nio.file.Path file = java.nio.file.Path.of("pathtrace", "ravine-seed1.json").toAbsolutePath();
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new GameTestAssertException("could not write " + file + ": " + e);
            }
            Main.LOGGER.info("[KhaosKorps measure] path trace written to {} ({} characters)", file, json.length());
            helper.succeed();
        });
    }

    /** Every block of the arena, x fastest, then z, then y: '#' something to stand on or bump into, 'w' water, 'l' lava, '.' open. */
    private static String blocks(GameTestHelper helper) {
        StringBuilder out = new StringBuilder(64 * 24 * 64);
        for (int y = 0; y < 24; y++) {
            for (int z = 0; z < 64; z++) {
                for (int x = 0; x < 64; x++) {
                    BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
                    BlockState state = helper.getLevel().getBlockState(pos);
                    if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) out.append('l');
                    else if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) out.append('w');
                    else if (!state.getCollisionShape(helper.getLevel(), pos).isEmpty()) out.append('#');
                    else out.append('.');
                }
            }
        }
        return out.toString();
    }

    private static void search(StringBuilder json, Search search, RavineTerrain terrain, TracingEvaluator tracer, Path path, BlockPos origin) {
        json.append("{\"name\":\"").append(search.name()).append("\",\"dirtPath\":\"").append(search.dirtPath())
                .append("\",\"start\":").append(xyz(search.fromX(), terrain.stand[search.fromX()][search.fromZ()], search.fromZ()))
                .append(",\"target\":").append(xyz(search.toX(), terrain.stand[search.toX()][search.toZ()], search.toZ()))
                .append(",\"reached\":").append(path.canReach()).append(",\"path\":[");
        for (int i = 0; i < path.getNodeCount(); i++) {
            Node node = path.getNode(i);
            if (i > 0) json.append(',');
            json.append(xyz(node.x - origin.getX(), node.y - origin.getY(), node.z - origin.getZ()));
        }
        json.append("],\"pops\":[");
        for (int p = 0; p < tracer.pops.size(); p++) {
            TracingEvaluator.Pop pop = tracer.pops.get(p);
            if (p > 0) json.append(',');
            json.append("{\"p\":").append(xyz(pop.x - origin.getX(), pop.y - origin.getY(), pop.z - origin.getZ()))
                    .append(",\"g\":").append(num(pop.g)).append(",\"h\":").append(num(pop.h)).append(",\"f\":").append(num(pop.f))
                    .append(",\"m\":").append(num(pop.costMalus)).append(",\"ed\":").append(pop.edgeDistance).append(",\"ec\":").append(num(pop.edgeCost))
                    .append(",\"ring\":\"").append(pop.ring).append('"');
            if (pop.from != null) json.append(",\"from\":").append(xyz(pop.from[0] - origin.getX(), pop.from[1] - origin.getY(), pop.from[2] - origin.getZ()));
            json.append(",\"cand\":[");
            for (int c = 0; c < pop.candidates.size(); c++) {
                TracingEvaluator.Candidate cand = pop.candidates.get(c);
                if (c > 0) json.append(',');
                json.append("{\"d\":\"").append(cand.direction()).append("\",\"ask\":")
                        .append(xyz(cand.askX() - origin.getX(), cand.askY() - origin.getY(), cand.askZ() - origin.getZ()))
                        .append(",\"r\":\"").append(cand.result()).append("\",\"at\":")
                        .append(xyz(cand.x() - origin.getX(), cand.y() - origin.getY(), cand.z() - origin.getZ()))
                        .append(",\"m\":").append(num(cand.costMalus())).append(",\"de\":").append(cand.deadEndSteps()).append('}');
            }
            json.append("],\"nb\":[");
            for (int n = 0; n < pop.neighbours.size(); n++) {
                TracingEvaluator.Neighbour nb = pop.neighbours.get(n);
                if (n > 0) json.append(',');
                json.append("{\"p\":").append(xyz(nb.x - origin.getX(), nb.y - origin.getY(), nb.z - origin.getZ()))
                        .append(",\"m\":").append(num(nb.costMalus)).append(",\"g0\":").append(num(nb.gBefore))
                        .append(",\"open\":").append(nb.openBefore).append(",\"g\":").append(num(nb.gAfter))
                        .append(",\"h\":").append(num(nb.hAfter)).append(",\"f\":").append(num(nb.fAfter))
                        .append(",\"upd\":").append(nb.updated).append('}');
            }
            json.append("]}");
        }
        json.append("]}");
    }

    private static String xyz(int x, int y, int z) {
        return "[" + x + "," + y + "," + z + "]";
    }

    private static String num(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
