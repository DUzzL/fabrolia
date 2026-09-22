package com.axalotl.fabrolia.common;

import com.axalotl.fabrolia.common.parallelised.utils.VectorOpsBootstrap;
import com.axalotl.fabrolia.common.platform.PlatformUtils;

public abstract class FabroliaCommon {
    public static final String MODID = "fabrolia";

    public final void initialize() {
        PlatformUtils.initialize();
        VectorOpsBootstrap.logStatus();
    }
}
