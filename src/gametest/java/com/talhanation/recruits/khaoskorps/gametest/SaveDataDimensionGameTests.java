package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.world.RecruitsDiplomacyManager.DiplomacyStatus;
import com.talhanation.recruits.world.RecruitsDiplomacySaveData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Recruits' world data is one store, kept with the Overworld, whichever dimension asks for it: a
 * diplomacy change made in the Nether reads and writes the same store as one made in the Overworld,
 * and doesn't empty it.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class SaveDataDimensionGameTests {

    private static final String TEAM = "kktest_team_a";
    private static final String OTHER = "kktest_team_b";

    /** A relation set through the Overworld's store is still there after the Nether asks for the store. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void diplomacyAskedForFromTheNetherIsTheOverworldsAndKeepsItsRelations(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null) throw new GameTestAssertException("setup: the test server has no Nether");
        RecruitsDiplomacySaveData fromOverworld = RecruitsDiplomacySaveData.get(overworld);
        fromOverworld.setRelation(TEAM, OTHER, DiplomacyStatus.ALLY.getByteValue());
        try {
            RecruitsDiplomacySaveData fromNether = RecruitsDiplomacySaveData.get(nether);
            DiplomacyStatus after = fromOverworld.getDiplomacyMap().getOrDefault(TEAM, java.util.Map.of()).get(OTHER);
            if (fromNether != fromOverworld) {
                throw new GameTestAssertException("the Nether got its own diplomacy store, not the Overworld's; the relation is now " + after);
            }
            if (after != DiplomacyStatus.ALLY) {
                throw new GameTestAssertException("after the Nether asked for the diplomacy store, the relation set in the Overworld is " + after);
            }
        } finally {
            fromOverworld.getDiplomacyMap().getOrDefault(TEAM, new java.util.HashMap<>()).remove(OTHER);
        }
        helper.succeed();
    }
}
