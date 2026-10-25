import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.objects.*;
import java.lang.reflect.*;
public class ExactSpawnWaveProbe {
 static class FakeDistanceManager extends DistanceManager {
  private FakeDistanceManager(){super(null,Runnable::run,Runnable::run);}
  protected boolean isChunkToRemove(long k){return false;}
  protected ChunkHolder getChunk(long k){return null;}
  protected ChunkHolder updateChunkScheduling(long k,int l,ChunkHolder h,int i){return null;}
 }
 static class FakeChunkMap extends ChunkMap {
  java.util.function.LongToIntFunction level;
  ServerPlayer player;
  private FakeChunkMap(){super(null,null,null,null,null,null,null,null,null,null,0,false);}
  @Override public java.util.List<ServerPlayer> getPlayersCloseForSpawning(net.minecraft.world.level.ChunkPos pos) {
   return level.applyAsInt(pos.pack())>8 ? java.util.List.of() : java.util.List.of(player);
  }
 }
 public static void main(String[]args)throws Exception {
  net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
  Field uf=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);
  sun.misc.Unsafe u=(sun.misc.Unsafe)uf.get(null);
  FakeDistanceManager dm=(FakeDistanceManager)u.allocateInstance(FakeDistanceManager.class);
  Long2ObjectOpenHashMap<ObjectSet<ServerPlayer>> players=new Long2ObjectOpenHashMap<>();
  Field pf=DistanceManager.class.getDeclaredField("playersPerChunk");pf.setAccessible(true);pf.set(dm,players);
  Class<?> cl=Class.forName("net.minecraft.server.level.DistanceManager$FixedPlayerDistanceChunkTracker");
  Constructor<?> ctor=cl.getDeclaredConstructor(DistanceManager.class,int.class);ctor.setAccessible(true);
  ChunkTracker t=(ChunkTracker)ctor.newInstance(dm,8);
  Method run=net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint.class.getDeclaredMethod("runUpdates",int.class);run.setAccessible(true);
  Method level=cl.getDeclaredMethod("getLevel",long.class);level.setAccessible(true);
  long old=ChunkPos.pack(0,0),next=ChunkPos.pack(1,0);
  ObjectOpenHashSet<ServerPlayer> source=new ObjectOpenHashSet<>();source.add(null);
  players.put(old,source);t.update(old,0,true);run.invoke(t,Integer.MAX_VALUE);
  System.out.println("Exact fixed tracker before: oldChunkLevel="+level.invoke(t,old));
  players.remove(old);t.update(old,Integer.MAX_VALUE,false);
  players.put(next,source);t.update(next,0,true);
  run.invoke(t,1);
  System.out.println("Exact fixed tracker after first wave step: oldChunkLevel="+level.invoke(t,old)+" actualPlayerDistance=1 => hasPlayersNearby FALSE");
  FakeChunkMap cm=(FakeChunkMap)u.allocateInstance(FakeChunkMap.class);
  cm.player=(ServerPlayer)u.allocateInstance(ServerPlayer.class); cm.player.setId(1);
  cm.level=k->{try{return (int)level.invoke(t,k);}catch(Exception ex){throw new RuntimeException(ex);}};
  net.minecraft.world.level.LocalMobCapCalculator broken=new net.minecraft.world.level.LocalMobCapCalculator(cm);
  for(int i=0;i<80;i++)broken.addMob(ChunkPos.unpack(old),net.minecraft.world.entity.MobCategory.MONSTER);
  run.invoke(t,Integer.MAX_VALUE);
  System.out.println("Exact fixed tracker after full wave: oldChunkLevel="+level.invoke(t,old));
  boolean brokenAllows=broken.canSpawn(net.minecraft.world.entity.MobCategory.MONSTER,ChunkPos.unpack(next));
  net.minecraft.world.level.LocalMobCapCalculator control=new net.minecraft.world.level.LocalMobCapCalculator(cm);
  for(int i=0;i<80;i++)control.addMob(ChunkPos.unpack(old),net.minecraft.world.entity.MobCategory.MONSTER);
  boolean controlAllows=control.canSpawn(net.minecraft.world.entity.MobCategory.MONSTER,ChunkPos.unpack(next));
  System.out.println("Real LocalMobCapCalculator seeded with 80 mobs MID-WAVE allows spawn="+brokenAllows);
  System.out.println("Real LocalMobCapCalculator seeded with same 80 mobs AFTER-WAVE allows spawn="+controlAllows);
  if(!brokenAllows || controlAllows)throw new AssertionError("expected seed-time undercount");
 }
}
