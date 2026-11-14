package com.talhanation.recruits.khaoskorps;

import com.talhanation.recruits.Main;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.saveddata.SavedData;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Saved data written safely. The game writes a saved-data file in place, truncating it first, so a
 * crash, a kill or a full disk partway through a write leaves half a file, which the next load
 * discards whole. This writes the same content to {@code <name>.dat.tmp} first and then moves it over
 * the file in one step, so a failed write leaves the previous file as it was. Otherwise it behaves as
 * the game's: only dirty data is written, a failure is logged, and the data counts as saved afterwards.
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
        try {
            NbtIo.writeCompressed(tag, temporary.toFile());
            moveOver(temporary, target);
        } catch (IOException e) {
            Main.LOGGER.error("[KhaosKorps] Could not save {} to {}; the previous file is kept", this.getClass().getSimpleName(), target, e);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                Main.LOGGER.warn("[KhaosKorps] Could not remove the unfinished save {}", temporary, cleanup);
            }
        }
        this.setDirty(false);
    }

    private static void moveOver(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
