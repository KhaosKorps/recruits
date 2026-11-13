package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.MessengerEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import com.talhanation.recruits.network.MessageAnswerTreaty;
import com.talhanation.recruits.world.RecruitsDiplomacyManager.DiplomacyStatus;
import com.talhanation.recruits.world.RecruitsPlayerInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;
import java.util.UUID;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * KhaosKorps fork: gap P4 (AUDT-261113-Recruits_Security_Port_Check_By_Anon).
 *
 * A treaty messenger can be addressed to any player of the other faction, and at the port any
 * addressee could accept it, setting both factions neutral without the target faction's leader.
 * Two factions start as enemies; a messenger from faction A's leader reaches a player of B, who
 * accepts. The leader case is the control: it proves the setup can make a treaty at all.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class TreatyAnswerGameTests {

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void aFactionLeaderCanAcceptATreaty(GameTestHelper helper) {
        DiplomacyStatus after = acceptTreaty(helper, true);
        if (after != DiplomacyStatus.NEUTRAL) {
            helper.fail("the leader accepted, but the factions are " + after + ", not NEUTRAL");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void aFactionMemberWhoIsNotItsLeaderCannotAcceptATreaty(GameTestHelper helper) {
        DiplomacyStatus after = acceptTreaty(helper, false);
        if (after != DiplomacyStatus.ENEMY) {
            helper.fail("a member who isn't the leader accepted a treaty: the factions are now " + after);
            return;
        }
        helper.succeed();
    }

    /** Runs the scenario and returns the relation from faction A to B after the answer. */
    private static DiplomacyStatus acceptTreaty(GameTestHelper helper, boolean answererLeadsFactionB) {
        if (FactionEvents.recruitsFactionManager == null || FactionEvents.recruitsDiplomacyManager == null
                || FactionEvents.recruitsTreatyManager == null) {
            throw new GameTestAssertException("precondition failed: the faction, diplomacy and treaty managers are loaded");
        }
        ServerLevel level = helper.getLevel();
        Scoreboard scoreboard = level.getScoreboard();
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String a = "kka" + suffix;
        String b = "kkb" + suffix;
        PlayerTeam teamA = scoreboard.addPlayerTeam(a);
        PlayerTeam teamB = scoreboard.addPlayerTeam(b);

        ServerPlayer leaderA = owner(helper, 2.5, 2, 2.5);
        ServerPlayer leaderB = owner(helper, 4.5, 2, 2.5);
        ServerPlayer memberB = owner(helper, 6.5, 2, 2.5);
        ServerPlayer answerer = answererLeadsFactionB ? leaderB : memberB;
        scoreboard.addPlayerToTeam(leaderA.getScoreboardName(), teamA);
        scoreboard.addPlayerToTeam(leaderB.getScoreboardName(), teamB);
        scoreboard.addPlayerToTeam(memberB.getScoreboardName(), teamB);

        FactionEvents.recruitsFactionManager.addTeam(a, "A", leaderA.getUUID(), leaderA.getScoreboardName(), new CompoundTag(), (byte) 0, ChatFormatting.WHITE);
        FactionEvents.recruitsFactionManager.addTeam(b, "B", leaderB.getUUID(), leaderB.getScoreboardName(), new CompoundTag(), (byte) 0, ChatFormatting.WHITE);
        FactionEvents.recruitsDiplomacyManager.setRelation(a, b, DiplomacyStatus.ENEMY, level, false);
        FactionEvents.recruitsDiplomacyManager.setRelation(b, a, DiplomacyStatus.ENEMY, level, false);

        MessengerEntity messenger = null;
        try {
            messenger = helper.spawn(ModEntityTypes.MESSENGER.get(), 5, 2, 2);
            messenger.setOwnerUUID(Optional.of(leaderA.getUUID()));
            messenger.setIsOwned(true);
            scoreboard.addPlayerToTeam(messenger.getScoreboardName(), teamA);
            messenger.setTargetPlayerInfo(new RecruitsPlayerInfo(answerer.getUUID(), answerer.getScoreboardName()));
            messenger.setTreatyDurationHours(1);
            messenger.setMessengerState(MessengerEntity.MessengerState.ARRIVED);

            new MessageAnswerTreaty(messenger.getUUID(), true).executeServerSide(contextFrom(answerer));
            return FactionEvents.recruitsDiplomacyManager.getRelation(a, b);
        } finally {
            // A real messenger records where it set off (initialPos) and walks home after an answer;
            // this one was never sent, so it must not outlive the test.
            if (messenger != null) messenger.discard();
            FactionEvents.recruitsFactionManager.removeTeam(a);
            FactionEvents.recruitsFactionManager.removeTeam(b);
            scoreboard.removePlayerTeam(teamA);
            scoreboard.removePlayerTeam(teamB);
            removeOwner(helper, leaderA);
            removeOwner(helper, leaderB);
            removeOwner(helper, memberB);
        }
    }
}
