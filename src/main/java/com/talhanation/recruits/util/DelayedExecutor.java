package com.talhanation.recruits.util;

/**
 * KhaosKorps fork: was a ScheduledExecutorService, whose thread kept the server's process running
 * after it stopped and ran tasks off the server thread. Tasks now run on the server thread at the
 * end of the tick they are due (ServerTickTasks).
 */
public class DelayedExecutor {

    public static void runLater(Runnable task, long delayMillis) {
        com.talhanation.recruits.khaoskorps.ServerTickTasks.runLater(task, delayMillis); // KhaosKorps fork
    }
}
