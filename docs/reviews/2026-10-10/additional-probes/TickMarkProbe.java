import com.axalotl.fabrolia.common.mixin.entity.EntityMixin;
import net.minecraft.world.level.Level;
public class TickMarkProbe {
 static class Mark extends EntityMixin {
  public boolean isOnPortalCooldown(){return false;}
  public int getPortalCooldown(){return 0;}
  public Level level(){return null;}
  public double getX(){return 0;}
  public double getY(){return 0;}
  public double getZ(){return 0;}
 }
 public static void main(String[]args) {
  Mark mark=new Mark();int fuse=80,ticks=0;
  long prefix=1L<<48;
  for(int epoch=1;epoch<=80;epoch++){
   long token=prefix|epoch;
   boolean first=mark.fabrolia$tryMarkRegionTick(token);
   if(first){fuse--;ticks++;}
   // A cross-cell migration within one island keeps the SAME domain token.
   boolean later=mark.fabrolia$tryMarkRegionTick(token);
   if(later){fuse--;ticks++;}
   if(!first||later)throw new AssertionError("migration incorrectly counted epoch="+epoch);
  }
  System.out.println("ACTUAL_ENTITYMIXIN_SAME_ISLAND_MIGRATION epochs=80 ticks="+ticks+" remainingFuse="+fuse);
  Mark carry=new Mark();long from=(1L<<48)|10,to=(2L<<48)|10;
  boolean first=carry.fabrolia$tryMarkRegionTick(from),carried=carry.fabrolia$carryRegionTickMark(from,to),second=carry.fabrolia$tryMarkRegionTick(to),next=carry.fabrolia$tryMarkRegionTick(to+1);
  System.out.println("ACTUAL_DOMAIN_CARRY first="+first+" carried="+carried+" destinationSecond="+second+" next="+next);
  if(!first||!carried||second||!next)throw new AssertionError("carry failed");
 }
}
