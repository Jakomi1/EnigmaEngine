package io.enigmaclient.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Skips the Xaero integration mixins when Xaero's Minimap and/or World Map are not installed, so
 * EnigmaClient keeps working as a plain companion mod without them.
 */
public class EnigmaMixinPlugin implements IMixinConfigPlugin {

    private static final String XAERO_MINIMAP = "xaerominimap";
    private static final String XAERO_WORLDMAP = "xaeroworldmap";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith("io.enigmaclient.mixin.xaero")) {
            FabricLoader loader = FabricLoader.getInstance();
            if (mixinClassName.contains("Minimap")) {
                return loader.isModLoaded(XAERO_MINIMAP);
            }
            if (mixinClassName.contains("WorldMap")) {
                return loader.isModLoaded(XAERO_WORLDMAP);
            }
            return loader.isModLoaded(XAERO_MINIMAP) && loader.isModLoaded(XAERO_WORLDMAP);
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
