package com.axalotl.fabrolia.common.regions;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicLong;
import sun.misc.Unsafe;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.ChunkPos;
public class ColdTargetAdmissionProbe {
 static final Unsafe U;
 static { try {Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);U=(Unsafe)f.get(null);}catch(Exception e){throw new RuntimeException(e);} }
 public static class TestTnt extends PrimedTnt implements RegionKeyed {
  long key; AtomicLong mark;
  TestTnt(){super(EntityTypes.TNT,null);}
  public long fabrolia$getRegionKey(){return key;}
  public void fabrolia$setRegionKey(long k){key=k;}
  public boolean fabrolia$tryMarkRegionTick(long token){return mark.getAndSet(token)!=token;}
  public boolean fabrolia$carryRegionTickMark(long from,long to){return mark.compareAndSet(from,to);}
 }
 public static void main(String[]args)throws Exception {
  SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
  RegionizedServer rs=(RegionizedServer)U.allocateInstance(RegionizedServer.class);
  ThreadedRegionizer regions=new ThreadedRegionizer(null,new RegionGrid(3));
  Field f=RegionizedServer.class.getDeclaredField("regionizer");f.setAccessible(true);f.set(rs,regions);
  EpochDomain d=new EpochDomain();d.epoch=10;
  Region target=regions.regionForGridKey(1);target.domain=d;target.groupedEpoch=d.epoch;
  TestTnt t=(TestTnt)U.allocateInstance(TestTnt.class);t.setId(30);t.mark=new AtomicLong(Long.MIN_VALUE);
  Field chunk=Entity.class.getDeclaredField("chunkPosition");U.putObject(t,U.objectFieldOffset(chunk),new ChunkPos(8,0));
  rs.indexAdd(t);
  boolean eligible=t.fabrolia$tryMarkRegionTick(d.tickToken());
  System.out.println("ACTUAL_INDEXADD existingTarget=true grouped=true targetCurrentWorker="+target.currentWorker()+" admitted="+target.entities.contains(t)+" sameEpochTickEligible="+eligible);
  if(!eligible || target.currentWorker()!=null || !target.entities.contains(t))throw new AssertionError("unexpected admission");
 }
}
