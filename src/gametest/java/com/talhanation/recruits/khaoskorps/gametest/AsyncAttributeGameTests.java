package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import com.talhanation.recruits.khaoskorps.DetourSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * A path search handed to the path worker finds every attribute entry of its mob already made, so the worker
 * only reads the mob's attribute map. An entry is made the first time the attribute is read; made on the worker,
 * it changed the map while the server thread used it, a ConcurrentModificationException that crashed the server.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class AsyncAttributeGameTests {

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void aSearchHandedToThePathWorkerFindsEveryAttributeEntryMade(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        AbstractRecruitEntity recruit = helper.spawn(ModEntityTypes.RECRUIT.get(), 3, 3, 2);
        // Before the recruit's first tick: the search is the first thing to read its attributes.
        DetourSearch.find(helper.getLevel(), recruit, helper.absolutePos(new BlockPos(3, 3, 2)), helper.absolutePos(new BlockPos(10, 3, 2)), 16, 1);
        List<String> missing = new ArrayList<>();
        Map<Attribute, ?> made = entries(recruit.getAttributes());
        AttributeSupplier supplier = DefaultAttributes.getSupplier(ModEntityTypes.RECRUIT.get());
        for (Attribute attribute : ForgeRegistries.ATTRIBUTES.getValues()) {
            if (supplier.hasAttribute(attribute) && !made.containsKey(attribute)) missing.add(String.valueOf(ForgeRegistries.ATTRIBUTES.getKey(attribute)));
        }
        recruit.discard();
        Main.LOGGER.info("[KhaosKorps measure] attribute entries not made before the worker's search: {}", missing);
        if (!missing.isEmpty()) {
            throw new GameTestAssertException("attribute entries the path worker would make itself: " + missing);
        }
        helper.succeed();
    }

    /** The attribute map's entries, a private field. */
    @SuppressWarnings("unchecked")
    private static Map<Attribute, ?> entries(AttributeMap attributes) {
        try {
            java.lang.reflect.Field field = AttributeMap.class.getDeclaredField("attributes");
            field.setAccessible(true);
            return (Map<Attribute, ?>) field.get(attributes);
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("could not read the attribute map: " + e);
        }
    }
}
