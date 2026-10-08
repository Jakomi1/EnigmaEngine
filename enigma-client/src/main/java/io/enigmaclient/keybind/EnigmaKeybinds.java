package io.enigmaclient.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import io.enigmaclient.EnigmaClient;
import io.enigmaclient.host.ComputeHostService;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class EnigmaKeybinds {

    private static KeyMapping toggleKey;
    private static boolean wasDown;

    private EnigmaKeybinds() {
    }

    public static void register() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.enigmaclient.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                KeyMapping.Category.MULTIPLAYER));

        ClientTickEvents.END_CLIENT_TICK.register(EnigmaKeybinds::tick);
    }

    private static void tick(Minecraft mc) {
        if (toggleKey == null || mc.getWindow() == null) {
            return;
        }
        InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(toggleKey);
        boolean down = bound != null && InputConstants.isKeyDown(mc.getWindow(), bound.getValue());
        if (down && !wasDown) {
            toggle();
        }
        wasDown = down;
    }

    private static void toggle() {
        ComputeHostService service = EnigmaClient.computeHost;
        if (service == null) {
            return;
        }
        service.toggle();
    }
}
