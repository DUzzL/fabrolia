import com.axalotl.fabrolia.api.fastutil.*;
import it.unimi.dsi.fastutil.longs.*;
import java.util.Map;

public class CollectionContractProbe {
    static int reproduced;
    static void mismatch(String label, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            reproduced++;
            System.out.println(label + ": expected=" + expected + ", actual=" + actual);
        }
    }
    public static void main(String[] args) {
        var ints = new Long2IntConcurrentHashMap();
        var vanillaInts = new Long2IntOpenHashMap();
        ints.put(1L, 0); vanillaInts.put(1L, 0);
        mismatch("Long2Int remove existing zero", vanillaInts.remove(1L, 0), ints.remove(1L, 0));
        System.out.println("Long2Int mapping was nevertheless deleted: " + !ints.containsKey(1L));
        var longs = new Long2LongConcurrentHashMap();
        var vanillaLongs = new Long2LongOpenHashMap();
        longs.defaultReturnValue(7L); vanillaLongs.defaultReturnValue(7L);
        longs.put(1L, 7L); vanillaLongs.put(1L, 7L);
        mismatch("Long2Long remove existing default value", vanillaLongs.remove(1L, 7L), longs.remove(1L, 7L));
        var bytes = new Long2ByteConcurrentHashMap();
        var vanillaBytes = new Long2ByteOpenHashMap();
        bytes.put(1L, (byte)0); vanillaBytes.put(1L, (byte)0);
        mismatch("Long2Byte remove existing zero", vanillaBytes.remove(1L, (byte)0), bytes.remove(1L, (byte)0));
        longs.put(2L, 12L); vanillaLongs.put(2L, 12L);
        mismatch("Long2Long keySet.remove result", vanillaLongs.keySet().remove(2L), longs.keySet().remove(2L));
        mismatch("Long2Long keySet.remove updates map", vanillaLongs.containsKey(2L), longs.containsKey(2L));
        ints.put(2L, 12); vanillaInts.put(2L, 12);
        ints.values().clear(); vanillaInts.values().clear();
        mismatch("Long2Int values.clear updates map", vanillaInts.size(), ints.size());
        bytes.put(2L, (byte)12); vanillaBytes.put(2L, (byte)12);
        bytes.long2ByteEntrySet().iterator().next().setValue((byte)19);
        vanillaBytes.long2ByteEntrySet().iterator().next().setValue((byte)19);
        mismatch("Long2Byte Entry.setValue updates map", vanillaBytes.get(2L), bytes.get(2L));
        ((Map<Long,Byte>)bytes).replaceAll((key, value) -> (byte)21);
        ((Map<Long,Byte>)vanillaBytes).replaceAll((key, value) -> (byte)21);
        mismatch("Long2Byte replaceAll updates map", vanillaBytes.get(2L), bytes.get(2L));
        System.out.println("Reproduced differences: " + reproduced);
        if (reproduced != 0) throw new AssertionError("Map contract violations: " + reproduced);
    }
}
