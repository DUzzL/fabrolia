package com.axalotl.fabrolia.common.regions;

/** Native entity section underlying a spatial container-user query. */
public interface CopperSectionAccess {
    void fabrolia$bindCopperSection(long chunkKey);
    void fabrolia$detachCopperSection();
    void fabrolia$seedTrackedCopper(RegionizedServer runtime);
}
