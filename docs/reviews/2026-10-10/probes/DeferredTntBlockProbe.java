import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Field;
import java.util.*;

public class DeferredTntBlockProbe {
 static final BlockPos TARGET = new BlockPos(128,64,16);
 static final class TestLevel extends ServerLevel {
  boolean defer;
  BlockState block;
  List<Runnable> pending;
  List<Entity> entities;
  RandomSource rng;
  GameRules rules;
  int ids;
  private TestLevel() { super(null,null,null,null,null,null,false,0,null,false); }
  static TestLevel create(boolean defer) throws Exception {
   Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);
   TestLevel l=(TestLevel)((sun.misc.Unsafe)f.get(null)).allocateInstance(TestLevel.class);
   l.defer=defer;l.block=Blocks.TNT.defaultBlockState();l.pending=new ArrayList<>();l.entities=new ArrayList<>();l.rng=RandomSource.create(1);l.rules=new GameRules(FeatureFlags.DEFAULT_FLAGS);
   return l;
  }
  @Override public GameRules getGameRules(){return rules;}
  @Override public RandomSource getRandom(){return rng;}
  @Override public int getNextEntityId(){return ++ids;}
  @Override public BlockState getBlockState(BlockPos p){return block;}
  @Override public boolean setBlock(BlockPos p,BlockState s,int flags,int recursion){
   if(defer)pending.add(()->block=s); else block=s;
   return true;
  }
  @Override public boolean addFreshEntity(Entity e){entities.add(e);return true;}
 }
 static final class Hit implements Explosion {
  final TestLevel level;
  Hit(TestLevel level){this.level=level;}
  public ServerLevel level(){return level;}
  public BlockInteraction getBlockInteraction(){return BlockInteraction.DESTROY;}
  public LivingEntity getIndirectSourceEntity(){return null;}
  public Entity getDirectSourceEntity(){return null;}
  public float radius(){return 4;}
  public Vec3 center(){return new Vec3(127.5,64,16.5);}
  public boolean canTriggerBlocks(){return false;}
  public boolean shouldAffectBlocklikeEntities(){return true;}
 }
 static void run(boolean defer) throws Exception {
  TestLevel l=TestLevel.create(defer);Hit hit=new Hit(l);
  for(int i=0;i<2;i++)l.getBlockState(TARGET).onExplosionHit(l,TARGET,hit,(a,b)->{});
  int before=l.entities.size();boolean tntBefore=l.block.is(Blocks.TNT);
  for(Runnable r:l.pending)r.run();
  System.out.println("defer="+defer+" entities="+before+" blockTntBeforeDrain="+tntBefore+" blockAirAfterDrain="+l.block.isAir()+" uniqueUUIDs="+l.entities.stream().map(Entity::getUUID).distinct().count());
  if(before!=(defer?2:1)||!l.block.isAir())throw new AssertionError();
 }
 public static void main(String[]args)throws Exception{
  net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
  run(false);run(true);System.out.println("PASS actual26.3 onExplosionHit + TntBlock.wasExploded; setBlock-only deferral duplicates TNT");
 }
}
