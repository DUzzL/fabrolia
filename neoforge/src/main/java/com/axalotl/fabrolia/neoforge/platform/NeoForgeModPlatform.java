package com.axalotl.fabrolia.neoforge.platform;

import com.axalotl.fabrolia.common.platform.ModPlatform;
import com.axalotl.fabrolia.neoforge.config.FabroliaConfig;
import net.neoforged.fml.loading.FMLLoader;

public class NeoForgeModPlatform implements ModPlatform {

    @Override
    public void saveConfig() {
        FabroliaConfig.saveConfig();
    }

    @Override
    public void reloadConfig() {
        FabroliaConfig.loadConfig();
        com.axalotl.fabrolia.common.config.FabroliaConfig.onConfigLoaded();
    }

    @Override
    public boolean isModLoaded(String id) {
        return FMLLoader.getCurrent().getLoadingModList().getModFileById(id) != null;
    }

    @Override
    public boolean platformUsesRefmap() {
        return false;
    }
}