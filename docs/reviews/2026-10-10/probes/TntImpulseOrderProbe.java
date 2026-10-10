import java.lang.reflect.*;
import java.util.*;
import sun.misc.Unsafe;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.phys.Vec3;
public class TntImpulseOrderProbe {
 static final Unsafe U;
 static { try { Field f=Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); U=(Unsafe)f.get(null); }catch(Exception e){throw new RuntimeException(e);} }
 public static class FreeAirTnt extends PrimedTnt {
  int fuse; Vec3 simulatedPosition;
  FreeAirTnt(){super(EntityTypes.TNT,null);}
  @Override protected void handlePortal(){}
  @Override protected void applyGravity(){setDeltaMovement(getDeltaMovement().add(0,-getDefaultGravity(),0));}
  @Override public void move(MoverType type, Vec3 velocity){simulatedPosition=simulatedPosition.add(velocity);}
  @Override protected void applyEffectsFromBlocks(){}
  @Override protected boolean updateFluidInteraction(){return false;}
  @Override public int getFuse(){return fuse;}
  @Override public void setFuse(int f){fuse=f;}
 }
 static FreeAirTnt tnt(int id)throws Exception {
  FreeAirTnt t=(FreeAirTnt)U.allocateInstance(FreeAirTnt.class);t.setId(id);t.fuse=80;t.simulatedPosition=new Vec3(127.5,64,1);t.setDeltaMovement(Vec3.ZERO);
  Field level=Entity.class.getDeclaredField("level");U.putObject(t,U.objectFieldOffset(level),U.allocateInstance(ServerLevel.class));return t;
 }
 static String describe(FreeAirTnt t){return String.format(Locale.ROOT,"x=%.9f vx=%.9f fuse=%d",t.simulatedPosition.x,t.getDeltaMovement().x,t.fuse);}
 public static void main(String[]args)throws Exception {
  SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
  Vec3 identicalExplosionImpulse=new Vec3(-0.875,0,0);
  FreeAirTnt vanilla=tnt(20),region=tnt(20);
  // older A at x=128.5, region color1; younger B at127.5, region color0.
  // actual TNT body and actual Entity.pushFromExplosion; FREE-AIR movement/portal/fluid hooks stubbed.
  vanilla.pushFromExplosion(identicalExplosionImpulse);vanilla.tick();
  region.tick();region.pushFromExplosion(identicalExplosionImpulse);
  System.out.println("ACTUAL_PRIMEDTNT_TICK_AND_ENTITY_PUSH_FREE_AIR_HOOKS");
  System.out.println("VANILLA_A_EXPLODE_THEN_B_TICK "+describe(vanilla));
  System.out.println("COLOR_GATE_B_TICK_THEN_A_EXPLODE "+describe(region));
  System.out.printf(Locale.ROOT,"FIRST_TICK_X_DIFFERENCE=%.9f\n",region.simulatedPosition.x-vanilla.simulatedPosition.x);
  for(int i=1;i<40;i++){vanilla.tick();region.tick();}
  System.out.println("AFTER_40_ENTITY_TICKS vanilla="+describe(vanilla)+" colorGate="+describe(region));
  System.out.printf(Locale.ROOT,"X_DIFFERENCE_AFTER_40=%.9f\n",region.simulatedPosition.x-vanilla.simulatedPosition.x);
  if(vanilla.fuse!=40||region.fuse!=40)throw new AssertionError("fuse changed");
  if(Math.abs((region.simulatedPosition.x-vanilla.simulatedPosition.x)-0.397947091)>1.0e-8)
   throw new AssertionError("unexpected trajectory difference");
  System.out.println("PASS same fuse, different TNT trajectory from impulse/tick order");
 }
}
