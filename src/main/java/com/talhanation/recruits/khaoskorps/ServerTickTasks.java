package com.talhanation.recruits.khaoskorps;

import com.talhanation.recruits.Main;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Delayed tasks run on the server thread, at the end of the server tick they are due, instead of
 * on a separate thread: no thread of its own, so nothing keeps the server's process alive after
 * it stops, and tasks touch the world from the thread that owns it. Waiting tasks are dropped when
 * the server stops.
 */
public final class ServerTickTasks {

    private static final TickQueue QUEUE = new TickQueue();

    private ServerTickTasks() {
    }

    /** Runs {@code task} on the server thread once about {@code delayMillis} have passed, at 20 ticks a second. Any thread. */
    public static void runLater(Runnable task, long delayMillis) {
        QUEUE.submit(task, TickQueue.ticksFor(delayMillis));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        QUEUE.tick(event.getServer().getTickCount(), (task, failure) ->
                Main.LOGGER.error("[KhaosKorps] A delayed server task failed: {}", task, failure));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        QUEUE.clear();
    }
}
