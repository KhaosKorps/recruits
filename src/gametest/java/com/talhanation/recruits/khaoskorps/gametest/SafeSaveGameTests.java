package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.khaoskorps.AtomicSavedData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagType;
import net.minecraft.nbt.TagVisitor;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Recruits' save files are written safely: a save that fails partway through leaves the previous
 * file whole. The failure is simulated by a tag that throws while it is being written, as a crash
 * or a full disk would stop a write.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class SafeSaveGameTests {

    /** A save over an existing file replaces it, and leaves no temporary file. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void aSaveReplacesThePreviousFile(GameTestHelper helper) {
        Path folder = null;
        try {
            folder = Files.createTempDirectory("khaoskorps-safe-save");
            File file = folder.resolve("kktest.dat").toFile();
            for (String value : new String[]{"old", "new"}) {
                Store store = new Store(value, false);
                store.setDirty();
                store.save(file);
            }
            String value = NbtIo.readCompressed(file).getCompound("data").getString("value");
            if (!"new".equals(value)) throw new GameTestAssertException("after saving \"new\" over \"old\" the file holds \"" + value + "\"");
            if (Files.exists(folder.resolve("kktest.dat.tmp"))) throw new GameTestAssertException("a save left its temporary file behind");
        } catch (IOException e) {
            throw new GameTestAssertException("setup: " + e);
        } finally {
            deleteQuietly(folder);
        }
        helper.succeed();
    }

    /** A save on a thread whose interrupt flag is set still writes the file; putting it on disk is best effort. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void aSaveOnAnInterruptedThreadStillSucceeds(GameTestHelper helper) {
        Path folder = null;
        try {
            folder = Files.createTempDirectory("khaoskorps-safe-save");
            File file = folder.resolve("kktest.dat").toFile();
            Store old = new Store("old", false);
            old.setDirty();
            old.save(file);
            Store current = new Store("new", false);
            current.setDirty();
            Thread.currentThread().interrupt();
            try {
                current.save(file);
            } finally {
                Thread.interrupted();
            }
            String value = NbtIo.readCompressed(file).getCompound("data").getString("value");
            if (!"new".equals(value)) throw new GameTestAssertException("a save on an interrupted thread left \"" + value + "\"");
        } catch (IOException e) {
            throw new GameTestAssertException("setup: " + e);
        } finally {
            deleteQuietly(folder);
        }
        helper.succeed();
    }

    /** A save that fails partway leaves the previous file readable, with its data, and no temporary file. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void aSaveThatFailsPartwayLeavesThePreviousFileWhole(GameTestHelper helper) {
        Path folder = null;
        try {
            folder = Files.createTempDirectory("khaoskorps-safe-save");
            File file = folder.resolve("kktest.dat").toFile();
            Store good = new Store("old", false);
            good.setDirty();
            good.save(file);
            Store failing = new Store("new", true);
            failing.setDirty();
            failing.save(file);
            String value;
            try {
                value = NbtIo.readCompressed(file).getCompound("data").getString("value");
            } catch (IOException | RuntimeException e) {
                // The game wraps some read failures, a truncated file among them, in a runtime exception.
                throw new GameTestAssertException("after a save failed partway, the previous file can't be read: " + e);
            }
            if (!"old".equals(value)) {
                throw new GameTestAssertException("after a save failed partway, the file holds \"" + value + "\", not the previous \"old\"");
            }
            if (Files.exists(folder.resolve("kktest.dat.tmp"))) {
                throw new GameTestAssertException("a failed save left its temporary file behind");
            }
        } catch (IOException e) {
            throw new GameTestAssertException("setup: " + e);
        } finally {
            deleteQuietly(folder);
        }
        helper.succeed();
    }

    private static final class Store extends AtomicSavedData {
        private final String value;
        private final boolean failPartway;

        Store(String value, boolean failPartway) {
            this.value = value;
            this.failPartway = failPartway;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putString("value", this.value);
            if (this.failPartway) tag.put("zz_breaks", new FailingTag());
            return tag;
        }
    }

    /** An int tag that writes part of itself and then fails. */
    private static final class FailingTag implements Tag {
        @Override
        public void write(DataOutput output) throws IOException {
            output.writeShort(4);
            throw new IOException("simulated failure partway through a save");
        }

        @Override
        public String toString() {
            return "FailingTag";
        }

        @Override
        public byte getId() {
            return Tag.TAG_INT;
        }

        @Override
        public TagType<?> getType() {
            return IntTag.TYPE;
        }

        @Override
        public Tag copy() {
            return this;
        }

        @Override
        public int sizeInBytes() {
            return 4;
        }

        @Override
        public void accept(TagVisitor visitor) {
        }

        @Override
        public StreamTagVisitor.ValueResult accept(StreamTagVisitor visitor) {
            return StreamTagVisitor.ValueResult.CONTINUE;
        }
    }

    private static void deleteQuietly(Path folder) {
        if (folder == null) return;
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            Main.LOGGER.warn("[KhaosKorps] couldn't remove the test folder {}", folder, e);
        }
    }
}
