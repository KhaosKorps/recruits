package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * The run recordings record what happened: the route a search found survives edge care cutting the
 * path the recruit then walks, as the recording keeps a copy taken when the search ended.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class RecorderGameTests {

    /**
     * On the cliff (EdgeSafetyGameTests), the path to an owner over the drop is cut back from the edge.
     * Every path the recruit walked is the start of its search's recorded route, and at least one is
     * shorter than it, so the recorded route outlived the cut.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 200)
    public static void aRecordingKeepsTheFoundRouteWhenEdgeCareCutsThePath(GameTestHelper helper) {
        EdgeSafetyGameTests.cliff(helper);
        ServerPlayer owner = owner(helper, 11.5, 8, 2.5);
        pin(helper, owner, 11.5, 8, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 7, 2);
        GroupRecorder recorder = new GroupRecorder("recording-check", helper, owner, 18, 9, 9, "{}", 0, 0, 0);
        GroupRecorder.Member member = recorder.add(recruit);
        helper.onEachTick(recorder::tick);
        helper.runAtTickTime(150, () -> {
            int walked = 0, cut = 0;
            List<String> wrong = new ArrayList<>();
            synchronized (member) {
                for (Map.Entry<Integer, Integer> entry : member.keptCount.entrySet()) {
                    SearchTracer.SearchRecord record = member.records.get(entry.getKey());
                    if (record.found == null || record.path == null) continue;
                    walked++;
                    int kept = record.path.getNodeCount();
                    boolean start = kept * 3 <= record.found.length;
                    for (int i = 0; start && i < kept; i++) {
                        Node node = record.path.getNode(i);
                        start = node.x == record.found[3 * i] && node.y == record.found[3 * i + 1] && node.z == record.found[3 * i + 2];
                    }
                    if (!start) wrong.add("search " + entry.getKey() + ": walked " + kept + " points, found " + record.found.length / 3);
                    else if (kept * 3 < record.found.length) cut++;
                }
            }
            recorder.discard();
            removeOwner(helper, owner);
            if (walked == 0) throw new GameTestAssertException("setup: no walked search was recorded");
            if (!wrong.isEmpty()) throw new GameTestAssertException("walked paths that aren't the start of their recorded route: " + wrong);
            if (cut == 0) throw new GameTestAssertException("setup: no walked path was cut back, so this proves nothing (" + walked + " walked)");
            helper.succeed();
        });
    }
}
