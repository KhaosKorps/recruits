package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.MessengerEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import com.talhanation.recruits.world.RecruitsPlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;
import java.util.UUID;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * KhaosKorps fork: the server crash Anon found next to the gap fixes
 * (AUDT-261113-Recruits_Security_Gap_Fixes_Check_By_Anon, "Adjacent issue"), older than the fork.
 *
 * A treaty messenger whose treaty was declined tells its owner on the way home. When the owner
 * is offline or in another dimension, getOwner() is null, and at the release that message threw
 * in the messenger's tick, which stops the server. MessengerEntity's MOVING_TO_OWNER case already
 * handles a missing owner a few lines earlier; this restores that for the message.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class MessengerReturnGameTests {

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 40)
    public static void aDeclinedTreatyMessengerReturningToAnAbsentOwnerDoesNotCrashTheServer(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 2);
        MessengerEntity messenger = helper.spawn(ModEntityTypes.MESSENGER.get(), 5, 2, 2);
        messenger.setOwnerUUID(Optional.of(UUID.randomUUID())); // an owner who isn't in this level
        messenger.setIsOwned(true);
        messenger.setIsTreatyMessenger(true);
        messenger.setTargetPlayerInfo(new RecruitsPlayerInfo(UUID.randomUUID(), "kkt-addressee")); // a sent treaty always has one
        messenger.initialPos = helper.absolutePos(new BlockPos(5, 2, 2)); // as a sent messenger records it
        messenger.treatyNotAccepted = true;
        messenger.setMessengerState(MessengerEntity.MessengerState.MOVING_TO_OWNER);

        helper.runAtTickTime(10, () -> {
            boolean alive = messenger.isAlive();
            MessengerEntity.MessengerState state = messenger.getMessengerState();
            messenger.discard();
            if (!alive) throw new GameTestAssertException("the messenger was removed while returning to an absent owner");
            if (state != MessengerEntity.MessengerState.IDLE) {
                throw new GameTestAssertException("the messenger should be IDLE after returning, but is " + state);
            }
            helper.succeed();
        });
    }
}
