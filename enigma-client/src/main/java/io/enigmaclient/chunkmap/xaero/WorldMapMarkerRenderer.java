package io.enigmaclient.chunkmap.xaero;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.enigmaclient.chunkmap.player.EnigmaPlayerMarker;
import net.minecraft.client.Minecraft;
import xaero.map.element.MapElementGraphics;

/**
 * Draws a server reported player as a generic diamond marker with a name label on Xaero's World
 * Map - deliberately not a skin head, so the marker works even when the client has no player
 * entity or skin for that uuid. The handler has already translated the pose to the element's
 * screen position.
 */
public final class WorldMapMarkerRenderer {

    private WorldMapMarkerRenderer() {
    }

    public static boolean draw(final MapElementGraphics graphics, final EnigmaPlayerMarker marker) {
        final Minecraft minecraft = Minecraft.getInstance();
        final PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0.0, 0.0, 0.01);
        pose.pushPose();
        pose.mulPose(Axis.ZP.rotationDegrees(45.0F));
        graphics.fill(-4, -4, 4, 4, EnigmaPlayerTrackerSupport.BORDER_COLOR);
        graphics.fill(-3, -3, 3, 3, EnigmaPlayerTrackerSupport.colorOf(marker.id()));
        pose.popPose();
        graphics.drawString(minecraft.font, marker.name(), 5, -4,
                EnigmaPlayerTrackerSupport.NAME_COLOR, false);
        pose.popPose();
        return true;
    }
}
