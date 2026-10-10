import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import sun.misc.Unsafe;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.entity.EntityTickList;
public class EntityOrderProbe {
 static final Unsafe U;
 static { try { Field f=Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); U=(Unsafe)f.get(null); }catch(Exception e){throw new RuntimeException(e);} }
 static Entity e(int id)throws Exception { Entity e=(Entity)U.allocateInstance(PrimedTnt.class); e.setId(id); return e; }
 public static void main(String[]args)throws Exception {
  SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
  Entity a=e(10), b=e(20), fresh=e(30);
  EntityTickList vanilla=new EntityTickList(); vanilla.add(b); vanilla.add(a);
  List<Integer> va=new ArrayList<>(); vanilla.forEach(x->va.add(x.getId()));
  Set<Entity> region=new ConcurrentSkipListSet<>(Comparator.comparingInt(Entity::getId)); region.add(b); region.add(a);
  List<Integer> ro=region.stream().map(Entity::getId).toList();
  System.out.println("READMISSION_ORDER vanilla="+va+" region="+ro);
  vanilla=new EntityTickList(); vanilla.add(a); vanilla.add(b); EntityTickList captured=vanilla;
  List<Integer> visit=new ArrayList<>(); vanilla.forEach(x->{visit.add(x.getId()); if(x==a)captured.add(fresh);});
  List<Integer> next=new ArrayList<>(); vanilla.forEach(x->next.add(x.getId()));
  region.clear();region.add(b);
  // A's already-running region adds fresh to the not-yet-running neighboring B region.
  region.add(fresh);
  List<Integer> live=new ArrayList<>(); for(Entity x:region){live.add(x.getId());}
  System.out.println("NEW_DURING_PASS vanillaCurrent="+visit+" vanillaNext="+next+" regionLive="+live);
  if(!va.equals(List.of(20,10)) || !ro.equals(List.of(10,20)) || !visit.equals(List.of(10,20)) || !next.equals(List.of(10,20,30)) || !live.equals(List.of(20,30)))throw new AssertionError("unexpected mechanics");
 }
}
