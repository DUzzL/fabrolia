package com.axalotl.fabrolia.fabric.platform;

import com.axalotl.fabrolia.common.platform.ModPlatform;
import com.axalotl.fabrolia.fabric.config.FabroliaConfig;
import net.fabricmc.loader.api.FabricLoader;

public class FabricModPlatform implements ModPlatform {

    @Override
    public void saveConfig() {
        FabroliaConfig.saveConfig();
    }

    @Override
    public void reloadConfig() {
        FabroliaConfig.init();
    }

    @Override
    public boolean isModLoaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }
}