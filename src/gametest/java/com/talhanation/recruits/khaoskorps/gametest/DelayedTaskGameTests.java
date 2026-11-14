package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.util.DelayedExecutor;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Recruits' DelayedExecutor runs its tasks on the server thread, a tick or more later, so no thread
 * of its own can keep the server's process alive after it stops, and the tasks touch the world from
 * the thread that owns it.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class DelayedTaskGameTests {

    /** A task delayed 100 ms runs on the server thread, at least one tick later. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 40)
    public static void aDelayedTaskRunsOnTheServerThreadLater(GameTestHelper helper) {
        long[] ranAt = {-1};
        String[] thread = {null};
        boolean[] onServerThread = {false};
        long submittedAt = helper.getTick();
        DelayedExecutor.runLater(() -> {
            ranAt[0] = helper.getTick();
            thread[0] = Thread.currentThread().getName();
            // The executor's own thread inherits the server thread's group, so the thread itself is compared.
            onServerThread[0] = helper.getLevel().getServer().isSameThread();
        }, 100L);
        helper.succeedWhen(() -> {
            if (ranAt[0] < 0) throw new GameTestAssertException("the delayed task hasn't run yet");
            if (!onServerThread[0]) {
                throw new GameTestAssertException("the delayed task ran on thread " + thread[0] + ", not the server thread");
            }
            if (ranAt[0] <= submittedAt) {
                throw new GameTestAssertException("the delayed task ran at tick " + ranAt[0] + ", no later than it was submitted");
            }
        });
    }
}
