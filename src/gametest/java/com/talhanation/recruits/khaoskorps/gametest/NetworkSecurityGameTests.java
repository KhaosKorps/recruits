package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import de.maxhenkel.corelib.net.Message;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.simple.SimpleChannel;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * KhaosKorps fork: gap P1 (AUDT-261113-Recruits_Security_Port_Check_By_Anon).
 *
 * Forge refuses a packet travelling the wrong way before decoding it, but only for messages
 * registered with a direction (IndexedMessageCodec.consume: validatePacketDirection at offset
 * 188, tryDecode at 196, in Forge 47.4.10). Without one, a client can make the server decode a
 * client-bound message, including lists sized by a count the client chose.
 *
 * Reads Forge's registration table by reflection: test code only, never in the release jar.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class NetworkSecurityGameTests {

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void everyRecruitsMessageIsRegisteredWithTheDirectionItTravels(GameTestHelper helper) {
        try {
            Field codecField = SimpleChannel.class.getDeclaredField("indexedCodec");
            codecField.setAccessible(true);
            Object codec = codecField.get(Main.SIMPLE_CHANNEL);
            Field typesField = codec.getClass().getDeclaredField("types");
            typesField.setAccessible(true);
            Map<?, ?> types = (Map<?, ?>) typesField.get(codec);
            Field directionField = Class.forName("net.minecraftforge.network.simple.IndexedMessageCodec$MessageHandler")
                    .getDeclaredField("networkDirection");
            directionField.setAccessible(true);

            List<String> wrong = new ArrayList<>();
            for (Map.Entry<?, ?> entry : types.entrySet()) {
                Class<?> type = (Class<?>) entry.getKey();
                Message<?> message = (Message<?>) type.getDeclaredConstructor().newInstance();
                NetworkDirection expected = message.getExecutingSide() == Dist.CLIENT
                        ? NetworkDirection.PLAY_TO_CLIENT : NetworkDirection.PLAY_TO_SERVER;
                Optional<?> actual = (Optional<?>) directionField.get(entry.getValue());
                if (actual.isEmpty() || actual.get() != expected) {
                    wrong.add(type.getSimpleName() + " (" + actual.map(Object::toString).orElse("no direction") + ", expected " + expected + ")");
                }
            }
            if (types.isEmpty()) {
                helper.fail("no Recruits messages are registered on the channel");
                return;
            }
            if (!wrong.isEmpty()) {
                helper.fail(wrong.size() + " of " + types.size() + " Recruits messages lack their direction, for example "
                        + String.join(", ", wrong.subList(0, Math.min(3, wrong.size()))));
                return;
            }
            helper.succeed();
        } catch (ReflectiveOperationException e) {
            helper.fail("could not read Forge's message registrations: " + e);
        }
    }
}
