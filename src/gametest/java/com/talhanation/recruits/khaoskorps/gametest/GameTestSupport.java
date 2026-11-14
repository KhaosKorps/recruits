package com.talhanation.recruits.khaoskorps.gametest;

import com.mojang.authlib.GameProfile;
import com.talhanation.recruits.Main;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import com.talhanation.recruits.pathfinding.AsyncPath;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * KhaosKorps fork: shared setup for the Follow Me GameTests.
 *
 * Terrain is built in code inside the empty template, so each scenario reads in a diff.
 * Layout (relative to the template): a corridor along x = 1..14 and z = 1..3, closed by
 * barrier walls at x = 0, x = 15, z = 0 and z = 4 up to {@link #WALL_TOP}. Barrier walls keep
 * the search from walking around a scenario through the open land outside the template.
 */
public final class GameTestSupport {

    public static final int CORRIDOR_X_MIN = 1;
    public static final int CORRIDOR_X_MAX = 14;
    public static final int CORRIDOR_Z_MIN = 1;
    public static final int CORRIDOR_Z_MAX = 3;
    public static final int WALL_TOP = 8;

    private GameTestSupport() {
    }

    /** Barrier walls around the corridor, from the template floor to {@link #WALL_TOP}. */
    public static void walls(GameTestHelper helper) {
        for (int y = 0; y <= WALL_TOP; y++) {
            for (int x = 0; x <= CORRIDOR_X_MAX + 1; x++) {
                helper.setBlock(x, y, CORRIDOR_Z_MIN - 1, Blocks.BARRIER);
                helper.setBlock(x, y, CORRIDOR_Z_MAX + 1, Blocks.BARRIER);
            }
            for (int z = CORRIDOR_Z_MIN; z <= CORRIDOR_Z_MAX; z++) {
                helper.setBlock(CORRIDOR_X_MIN - 1, y, z, Blocks.BARRIER);
                helper.setBlock(CORRIDOR_X_MAX + 1, y, z, Blocks.BARRIER);
            }
        }
    }

    /** Solid stone under x = fromX..toX across the corridor, so a mob there stands at standY. */
    public static void ground(GameTestHelper helper, int fromX, int toX, int standY) {
        for (int x = fromX; x <= toX; x++) {
            for (int z = CORRIDOR_Z_MIN; z <= CORRIDOR_Z_MAX; z++) {
                for (int y = 0; y < standY; y++) {
                    helper.setBlock(x, y, z, Blocks.STONE);
                }
            }
        }
    }

    /**
     * An owner the recruit can find. Built as a plain ServerPlayer and added straight to the
     * level: GameTestHelper.makeMockServerPlayerInLevel() logs the player in, and the login path
     * fails on its mock connection (a null network channel). A recruit resolves its owner only
     * through Level.getPlayerByUUID, so being in the level's player list is enough.
     *
     * Recruits sends packets to players (for example when one joins a level), so the owner gets
     * a connection backed by Netty's in-memory EmbeddedChannel: sends succeed and the packets
     * stay in memory. The connection isn't registered with the server, so nothing ticks it and
     * no keep-alive can time it out. The server never simulates this player, so it stays put.
     */
    public static ServerPlayer owner(GameTestHelper helper, double x, double y, double z) {
        ServerLevel level = helper.getLevel();
        // A unique name: scoreboard teams list their members by name.
        UUID id = UUID.randomUUID();
        ServerPlayer owner = new ServerPlayer(level.getServer(), level,
                new GameProfile(id, "kkt" + id.toString().substring(0, 8)));
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        channel.attr(Connection.ATTRIBUTE_PROTOCOL).set(ConnectionProtocol.PLAY);
        owner.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, owner);
        Vec3 at = helper.absoluteVec(new Vec3(x, y, z));
        owner.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        level.addNewPlayer(owner);
        return owner;
    }

    /**
     * A network context that reports {@code sender} as the sender of a client-to-server message,
     * so a test can call a message's executeServerSide as that player. Forge's constructor is
     * package-private; getSender() reads the player back from the connection's listener, which
     * {@link #owner} sets up.
     */
    public static NetworkEvent.Context contextFrom(ServerPlayer sender) {
        try {
            Constructor<NetworkEvent.Context> constructor = NetworkEvent.Context.class
                    .getDeclaredConstructor(Connection.class, NetworkDirection.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(sender.connection.connection, NetworkDirection.PLAY_TO_SERVER, 0);
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("could not build a network context for " + sender.getScoreboardName() + ": " + e);
        }
    }

    /** Keeps the owner at a fixed relative position every tick. */
    public static void pin(GameTestHelper helper, ServerPlayer owner, double x, double y, double z) {
        Vec3 at = helper.absoluteVec(new Vec3(x, y, z));
        helper.onEachTick(() -> owner.setPos(at.x, at.y, at.z));
    }

    /** Removes the owner from the level, so it doesn't outlive its test. */
    public static void removeOwner(GameTestHelper helper, ServerPlayer owner) {
        helper.getLevel().removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
    }

    /** A plain recruit owned by the owner, in Follow Me. */
    public static AbstractRecruitEntity followingRecruit(GameTestHelper helper, ServerPlayer owner, int x, int y, int z) {
        AbstractRecruitEntity recruit = helper.spawn(ModEntityTypes.RECRUIT.get(), x, y, z);
        recruit.setOwnerUUID(Optional.of(owner.getUUID()));
        recruit.setIsOwned(true);
        recruit.setListen(true);
        recruit.setFollowState(1);
        recruit.setTarget(null);
        return recruit;
    }

    /**
     * Puts every fork setting back to its declared default and republishes the snapshot.
     * GameTest batch order isn't fixed, so every settings batch starts and ends with this.
     * The settings are found by reflection, so one added later can't be left out.
     */
    public static void resetForkSettings() {
        for (Field field : KhaosKorpsServerConfig.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !ForgeConfigSpec.ConfigValue.class.isAssignableFrom(field.getType())) continue;
            try {
                resetToDefault((ForgeConfigSpec.ConfigValue<?>) field.get(null));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("could not reset the fork setting " + field.getName(), e);
            }
        }
        KhaosKorpsServerConfig.republish();
    }

    /** Resets every fork setting, applies a batch's own values, then republishes the snapshot. */
    public static void forkSettings(Runnable batchValues) {
        resetForkSettings();
        batchValues.run();
        KhaosKorpsServerConfig.republish();
    }

    private static <T> void resetToDefault(ForgeConfigSpec.ConfigValue<T> value) {
        value.set(value.getDefault());
    }

    /** The batch a @GameTest joins when it names none. */
    public static final String DEFAULT_BATCH = "defaultBatch";

    /**
     * {@code count} copies of a test in the default batch, for a result that depends on timing.
     * Used instead of {@code @GameTest(attempts = ...)}: vanilla starts each further attempt as soon
     * as the previous one ends, outside its batch, so it can run under another batch's settings.
     * Seen on 2026-11-13: an edge test's later attempt ran during the follow-pace batch, at follow
     * speed 0.1, and failed its setup.
     */
    public static List<TestFunction> copies(String testName, int count, int timeoutTicks, boolean required,
                                            Consumer<GameTestHelper> test) {
        List<TestFunction> copies = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            copies.add(new TestFunction(DEFAULT_BATCH, testName + "_" + i,
                    Main.MOD_ID + ":" + HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks, 0L, required, test));
        }
        return copies;
    }

    /** Daytime, so no rest or night behaviour competes with following. */
    public static void daytime(GameTestHelper helper) {
        helper.getLevel().setDayTime(1000L);
    }

    /**
     * Fails with the precondition's name when anything other than the follow goal could be
     * driving the recruit, so a broken setup can't read as a pathfinding result.
     */
    public static void assertFollowPreconditions(AbstractRecruitEntity recruit) {
        require(recruit.getOwner() != null, "the recruit resolves its owner (getOwner)");
        require(recruit.getFollowState() == 1, "follow state is 1 (Follow Me)");
        require(recruit.getShouldFollow(), "getShouldFollow is true");
        require(!recruit.getFleeing(), "the recruit is not fleeing");
        require(!recruit.needsToGetFood(), "the recruit doesn't need to fetch food");
        require(!recruit.getShouldMount(), "the recruit isn't mounting");
        require(!recruit.getShouldMovePos(), "the recruit isn't moving to a position");
        require(recruit.getTarget() == null, "the recruit has no target");
    }

    /**
     * Fails if a path took the wrong route: with UseAsyncPathfinding on, every path must be an
     * AsyncPath, and with it off, none may be. Async is a world-restart setting, and the
     * navigation's path finder is chosen once per JVM (Atticus's K8 note), so on and off are
     * two separate runs of the GameTest server.
     */
    public static void assertPathRoute(AbstractRecruitEntity recruit) {
        Path path = recruit.getNavigation().getPath();
        if (path == null) return;
        boolean asyncOn = RecruitsServerConfig.UseAsyncPathfinding.get();
        boolean isAsync = path instanceof AsyncPath;
        if (isAsync != asyncOn) {
            throw new GameTestAssertException("path route: UseAsyncPathfinding is " + asyncOn
                    + " but the recruit's path is " + path.getClass().getSimpleName());
        }
    }

    /** The recruit's position relative to the template, for messages and position checks. */
    public static Vec3 relative(GameTestHelper helper, Entity entity) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return entity.position().subtract(origin.getX(), origin.getY(), origin.getZ());
    }

    public static String describe(GameTestHelper helper, Entity entity) {
        Vec3 p = relative(helper, entity);
        return String.format("(%.2f, %.2f, %.2f)", p.x, p.y, p.z);
    }

    private static void require(boolean ok, String what) {
        if (!ok) throw new GameTestAssertException("precondition failed: " + what);
    }
}
