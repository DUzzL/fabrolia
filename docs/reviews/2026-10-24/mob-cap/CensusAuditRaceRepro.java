package com.axalotl.fabrolia.common.parallelised.utils;

import java.lang.reflect.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType; import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.core.BlockPos;

public final class CensusAuditRaceRepro {
  static final class LedgerMob extends Cow implements SpawnCensus.Ledger {
    Object lock; boolean tracked; long chunk; int state;
    LedgerMob() { super(EntityTypes.COW, null); }
    public EntityType<?> getType() { return EntityTypes.ZOMBIE; }
    public BlockPos blockPosition() { return BlockPos.ZERO; }
    public boolean isPersistenceRequired() { return false; }
    public boolean requiresCustomPersistence() { return false; }
    public boolean isDeadOrDying() { return false; }
    public Object fabrolia$censusLock() { return lock; }
    public boolean fabrolia$censusTracked() { return tracked; }
    public void fabrolia$setCensusTracked(boolean x) { tracked=x; }
    public long fabrolia$censusChunk() { return chunk; }
    public void fabrolia$setCensusChunk(long x) { chunk=x; }
    public int fabrolia$censusState() { return state; }
    public void fabrolia$setCensusState(int x) { state=x; }
  }
  static int counted(ServerLevel level) {
    var vec=SpawnCensus.census(level).live.get(0L);
    return vec == null ? 0 : vec.get(MobCategory.MONSTER.ordinal());
  }
  static void snapshot(String stage, ServerLevel level, LedgerMob mob) {
    System.out.println(stage+": indexPresent="+SpawnIndex.getEntities(level).contains(mob)
      +" tracked="+mob.tracked+" state="+mob.state+" monsterCount="+counted(level));
  }
  public static void main(String[] ignored) throws Exception {
    SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
    Field uf=sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); uf.setAccessible(true);
    sun.misc.Unsafe u=(sun.misc.Unsafe)uf.get(null);
    ServerLevel level=(ServerLevel)u.allocateInstance(ServerLevel.class);
    Object data=Proxy.newProxyInstance(CensusAuditRaceRepro.class.getClassLoader(),new Class[]{WritableLevelData.class},
      (o,m,a)-> m.getName().equals("getGameTime") ? 0L : null);
    Field ld=Level.class.getDeclaredField("levelData"); ld.setAccessible(true); ld.set(level,data);
    SpawnCensus.LevelCensus census=SpawnCensus.census(level);
    census.costTypes=t->false; census.costTypesBuiltAt=0L;
    LedgerMob mob=(LedgerMob)u.allocateInstance(LedgerMob.class); mob.lock=new Object(); mob.setId(9942);
    try {
      SpawnIndex.onTrackingStart(level,mob);
      snapshot("Initial",level,mob);
      // Exact source operations from SpawnCensus.audit second-strike branch,
      // paused after remove but before update(false), interleaved with worker's EntitySection.add hook.
      // 3.1.13 removed the unused precompute version counter. Its old bump
      // had no effect on this index/ledger interleaving, so omit it here.
      SpawnIndex.getEntities(level).remove(mob);
      snapshot("Main audit after remove",level,mob);
      SpawnIndex.ensurePresent(level,mob);
      snapshot("Worker ensurePresent",level,mob);
      SpawnCensus.update(level,mob,false);
      snapshot("Main audit update(false)",level,mob);
      if (!SpawnIndex.getEntities(level).contains(mob) || mob.tracked || counted(level)!=0)
        throw new AssertionError("race not reproduced");
      SpawnIndex.ensurePresent(level,mob); SpawnCensus.update(level,mob,null);
      snapshot("Subsequent add/move",level,mob);
      if (mob.tracked || counted(level)!=0) throw new AssertionError("unexpected self-heal");
      SpawnCensus.update(level,mob,true); // next audit's healthy-membership branch
      snapshot("Next healthy audit",level,mob);
      if (!mob.tracked || counted(level)!=1) throw new AssertionError("audit did not restore");
      System.out.println("REPRODUCED: live index member is missing from incremental mob cap until explicit tracking refresh.");
    } finally { SpawnIndex.clearLevel(level); SpawnCensus.clearLevel(level); }
  }
}
