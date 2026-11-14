package com.talhanation.recruits.khaoskorps.gametest;

import com.mojang.authlib.GameProfile;
import com.talhanation.recruits.Main;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import com.talhanation.recruits.pathfinding.AsyncPath;
import com.electronwill.nightconfig.core.file.FileWatcher;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;
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
 * Shared setup for the fork's GameTests: terrain, a test owner, recruits in Follow Me, the fork's
 * settings per batch, and checks that the setup holds.
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

    /**
     * A walkable staircase down the corridor, one block a step: stand height 7 at x = 1..2, then one
     * lower each block to stand height 1 at x = 8, and flat at 1 to x = 14.
     */
    public static void stairsDown(GameTestHelper helper) {
        walls(helper);
        for (int x = CORRIDOR_X_MIN; x <= CORRIDOR_X_MAX; x++) {
            int stand = x <= 2 ? 7 : Math.max(1, 9 - x);
            ground(helper, x, x, stand);
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

    /**
     * A plain recruit owned by the owner, in Follow Me, and passive. A recruit in any other stance
     * looks for monsters up to 40 blocks away, through walls, and one in another test's area or the
     * world around would pull it off the behaviour under test.
     */
    public static AbstractRecruitEntity followingRecruit(GameTestHelper helper, ServerPlayer owner, int x, int y, int z) {
        AbstractRecruitEntity recruit = helper.spawn(ModEntityTypes.RECRUIT.get(), x, y, z);
        recruit.setOwnerUUID(Optional.of(owner.getUUID()));
        recruit.setIsOwned(true);
        recruit.setListen(true);
        recruit.setFollowState(1);
        recruit.setAggroState(3);
        return recruit;
    }

    /**
     * Puts every fork setting back to its declared default and republishes the snapshot.
     * GameTest batch order isn't fixed, so every settings batch starts and ends with this.
     * The settings are found by reflection, so one added later can't be left out.
     */
    public static void resetForkSettings() {
        stopReloadingForkSettingsFile();
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

    private static boolean forkSettingsFileUnwatched;

    /**
     * Stops Forge reloading the fork's settings file while GameTests run. Settings batches write
     * the file many times, and Forge reloads it from disk on its own thread whenever it notices a
     * change. A reload that reads an earlier state brings back values a batch had just replaced:
     * seen as a 1.15.2 baseline test running at another batch's fall distance.
     */
    private static void stopReloadingForkSettingsFile() {
        if (forkSettingsFileUnwatched) return;
        ModConfig config = ConfigTracker.INSTANCE.fileMap().get(KhaosKorpsServerConfig.FILE_NAME);
        if (config == null) {
            throw new IllegalStateException("the fork's settings file " + KhaosKorpsServerConfig.FILE_NAME + " isn't loaded");
        }
        FileWatcher.defaultInstance().removeWatch(config.getFullPath());
        forkSettingsFileUnwatched = true;
    }

    private static <T> void resetToDefault(ForgeConfigSpec.ConfigValue<T> value) {
        value.set(value.getDefault());
    }

    /** The batch a @GameTest joins when it names none. */
    public static final String DEFAULT_BATCH = "defaultBatch";

    /** A batch with EdgeMargin and WaitWhenOwnerUnreachable off, as in 1.15.2. Its batch functions are in EdgeCostGameTests. */
    public static final String EDGE_CARE_OFF_BATCH = "khaoskorps_edge_care_off";

    /**
     * {@code count} copies of a test in the default batch, for a result that depends on timing.
     * Used instead of {@code @GameTest(attempts = ...)}: vanilla starts each further attempt as soon
     * as the previous one ends, outside its batch, so it can run under another batch's settings
     * (an edge test's attempt run at another batch's follow speed of 0.1 never reaches the ledge).
     */
    public static List<TestFunction> copies(String testName, int count, int timeoutTicks, boolean required,
                                            Consumer<GameTestHelper> test) {
        return copies(DEFAULT_BATCH, testName, count, timeoutTicks, required, test);
    }

    /** {@link #copies(String, int, int, boolean, Consumer)} in a named batch. */
    public static List<TestFunction> copies(String batch, String testName, int count, int timeoutTicks, boolean required,
                                            Consumer<GameTestHelper> test) {
        List<TestFunction> copies = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            copies.add(new TestFunction(batch, testName + "_" + i,
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
        require(recruit.getTarget() == null, "the recruit has no target (it has " + targetOf(recruit) + ")");
    }

    /**
     * Fails if a path took the wrong route: with UseAsyncPathfinding on, every path must be an
     * AsyncPath, and with it off, none may be. Async is a world-restart setting, and the
     * navigation's path finder is chosen once per JVM (RecruitPathNavigation keeps it in a static
     * field), so on and off are
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

    /** The recruit's current path once its search has finished, or null while it is still being searched. */
    public static Path finishedPath(AbstractRecruitEntity recruit) {
        Path path = recruit.getNavigation().getPath();
        if (path instanceof AsyncPath async && !async.isProcessed()) return null;
        return path;
    }

    /** A path's points relative to the template, for failure messages. */
    public static String pathPoints(GameTestHelper helper, Path path) {
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < path.getNodeCount(); i++) {
            points.append(i == 0 ? "" : " ").append(relativeBlock(helper, path.getNode(i).asBlockPos()));
        }
        return points.toString();
    }

    /**
     * What a recruit's navigation is doing, for failure messages: its path's target and progress,
     * whether it stands on the ground, its motion, and any other entity within two blocks.
     */
    public static String navigationState(GameTestHelper helper, AbstractRecruitEntity recruit) {
        Path path = recruit.getNavigation().getPath();
        String route = path == null ? "no path"
                : "path to " + relativeBlock(helper, path.getTarget()) + ", node " + path.getNextNodeIndex() + " of "
                + path.getNodeCount() + (path.isDone() ? ", done" : "") + (path.canReach() ? "" : ", partial");
        String others = helper.getLevel().getEntities(recruit, recruit.getBoundingBox().inflate(2.0)).stream()
                .map(e -> e.getClass().getSimpleName() + " at " + describe(helper, e)).toList().toString();
        BlockPos under = recruit.getOnPos();
        return route + "; standing on " + helper.getLevel().getBlockState(under).getBlock().getName().getString()
                + " at " + relativeBlock(helper, under) + "; on ground " + recruit.onGround() + ", motion " + String.format("(%.3f, %.3f, %.3f)",
                recruit.getDeltaMovement().x, recruit.getDeltaMovement().y, recruit.getDeltaMovement().z)
                + "; target " + targetOf(recruit) + "; nearby " + others + "; " + fluidsInArena(helper) + "; arena at "
                + helper.absolutePos(BlockPos.ZERO).toShortString();
    }

    /** Fluid blocks anywhere in the template's 18 x 9 x 9 area, for failure messages; a test arena is built dry. */
    private static String fluidsInArena(GameTestHelper helper) {
        List<String> found = new ArrayList<>();
        int count = 0;
        for (BlockPos rel : BlockPos.betweenClosed(0, 0, 0, 17, 8, 8)) {
            if (helper.getLevel().getFluidState(helper.absolutePos(rel)).isEmpty()) continue;
            if (count++ < 6) found.add(relativeBlock(helper, helper.absolutePos(rel)));
        }
        return count == 0 ? "no fluid in the arena" : count + " fluid blocks in the arena, such as " + found;
    }

    /** The recruit's attack target and where it is, for failure messages. */
    private static String targetOf(AbstractRecruitEntity recruit) {
        Entity target = recruit.getTarget();
        if (target == null) return "none";
        return target.getClass().getSimpleName() + " " + String.format("%.1f", Math.sqrt(recruit.distanceToSqr(target)))
                + " blocks away at " + target.blockPosition();
    }

    private static String relativeBlock(GameTestHelper helper, BlockPos pos) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return "(" + (pos.getX() - origin.getX()) + ", " + (pos.getY() - origin.getY()) + ", " + (pos.getZ() - origin.getZ()) + ")";
    }

    public static String describe(GameTestHelper helper, Entity entity) {
        Vec3 p = relative(helper, entity);
        return String.format("(%.2f, %.2f, %.2f)", p.x, p.y, p.z);
    }

    private static void require(boolean ok, String what) {
        if (!ok) throw new GameTestAssertException("precondition failed: " + what);
    }
}
