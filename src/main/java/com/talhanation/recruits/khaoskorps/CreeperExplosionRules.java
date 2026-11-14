package com.talhanation.recruits.khaoskorps;

import net.minecraft.world.entity.monster.Creeper;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Applies PreventCreeperBlockDamage and PreventCreeperDamage to creeper explosions.
 *
 * Forge fires ExplosionEvent.Detonate inside Explosion.explode, after it has listed the blocks
 * and entities the blast reaches and before it breaks or hurts any of them. Emptying those lists
 * for a creeper's blast stops that part of the damage. The sound and particles still play, and
 * other explosions (TNT, beds, withers) are untouched.
 */
public final class CreeperExplosionRules {

    @SubscribeEvent
    public void onDetonate(ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getExplosion().getDirectSourceEntity() instanceof Creeper)) return;

        if (KhaosKorpsServerConfig.PreventCreeperBlockDamage.get()) event.getAffectedBlocks().clear();
        if (KhaosKorpsServerConfig.PreventCreeperDamage.get()) event.getAffectedEntities().clear();
    }
}
