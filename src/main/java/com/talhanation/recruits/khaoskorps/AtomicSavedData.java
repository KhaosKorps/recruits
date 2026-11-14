package com.talhanation.recruits.khaoskorps;

import com.talhanation.recruits.Main;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.saveddata.SavedData;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Saved data written safely. The game writes a saved-data file in place, truncating it first, so a
 * crash, a kill or a full disk partway through a write leaves half a file, which the next load
 * discards whole. This writes the same content to {@code <name>.dat.tmp} first and then moves it over
 * the file in one step, so a failed write leaves the previous file as it was. The new file is put on
 * disk before the move where the system allows (best effort), so a power cut just after it can't leave
 * an empty file under the old name, and
 * a move refused for a moment (a backup tool or virus scanner holding the file) is tried again a few
 * times. A new file that can't be opened for that (access denied, as a scanner holding it can cause)
 * still goes in, written and closed; any other error putting it on disk (a failing or full disk) fails
 * the save, as a failed write does. The thread's interrupt flag is set aside during the save, so an
 * interrupted server thread can still save, and restored after. Otherwise it behaves as the game's:
 * only dirty data is written, a failure is logged, and the data counts as saved afterwards; the stores
 * mark themselves dirty again at the next autosave.
 */
public abstract class AtomicSavedData extends SavedData {

    @Override
    public void save(File file) {
        if (!this.isDirty()) return;
        CompoundTag tag = new CompoundTag();
        tag.put("data", this.save(new CompoundTag()));
        NbtUtils.addCurrentDataVersion(tag);
        Path target = file.toPath();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        boolean interrupted = Thread.interrupted();
        try {
            NbtIo.writeCompressed(tag, temporary.toFile());
            try {
                this.forceToDisk(temporary);
            } catch (java.nio.file.AccessDeniedException notOpened) {
                Main.LOGGER.debug("[KhaosKorps] Could not open {} to force it to disk; moving it as written", temporary, notOpened);
            }
            moveOver(temporary, target);
        } catch (IOException e) {
            Main.LOGGER.error("[KhaosKorps] Could not save {} to {}; the previous file is kept", this.getClass().getSimpleName(), target, e);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                Main.LOGGER.warn("[KhaosKorps] Could not remove the unfinished save {}", temporary, cleanup);
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        this.setDirty(false);
    }

    /** Puts the written file on disk before it replaces the old one. A seam for tests to make it fail. */
    protected void forceToDisk(Path file) throws IOException {
        try (FileChannel written = FileChannel.open(file, StandardOpenOption.WRITE)) {
            written.force(true);
        }
    }

    static final int MOVE_ATTEMPTS = 5;
    static final long MOVE_RETRY_MILLIS = 10;

    /** Moves the new file over the old one; a refused move is tried again, up to 40 ms in all. */
    private static void moveOver(Path from, Path to) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                try {
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException refused) {
                if (attempt >= MOVE_ATTEMPTS) throw refused;
                try {
                    Thread.sleep(MOVE_RETRY_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw refused;
                }
            }
        }
    }
}
