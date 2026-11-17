package net.minecraft.commands;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.lang.reflect.Method;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.misc.UnknownCommandMessage;
import org.junit.jupiter.api.Test;
import org.spigotmc.SpigotConfig;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
public class UnknownCommandMessageTest {
    @Test
    void defaultHonorsSpigotSuppressionAndCustomLeafMessageStillOverridesIt() throws Exception {
        String previousLeaf = UnknownCommandMessage.unknownCommandMessage;
        String previousSpigot = SpigotConfig.unknownCommandMessage;
        try {
            Method method = Commands.class.getDeclaredMethod("getUnknownCommandMessage", TextComponent.Builder.class, CommandSyntaxException.class, String.class);
            method.setAccessible(true);
            CommandSyntaxException exception = CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().create();
            UnknownCommandMessage.unknownCommandMessage = "default";
            SpigotConfig.unknownCommandMessage = "";
            assertNull(method.invoke(null, Component.text(), exception, "bad"));

            SpigotConfig.unknownCommandMessage = "enabled";
            assertNotNull(method.invoke(null, Component.text(), exception, "bad"));

            SpigotConfig.unknownCommandMessage = "";
            UnknownCommandMessage.unknownCommandMessage = "Custom error";
            Component custom = (Component) method.invoke(null, Component.text(), exception, "bad");
            assertEquals("Custom error", PlainTextComponentSerializer.plainText().serialize(custom));

            UnknownCommandMessage.unknownCommandMessage = "";
            SpigotConfig.unknownCommandMessage = "enabled";
            assertNull(method.invoke(null, Component.text(), exception, "bad"));
        } finally {
            UnknownCommandMessage.unknownCommandMessage = previousLeaf;
            SpigotConfig.unknownCommandMessage = previousSpigot;
        }
    }
}
