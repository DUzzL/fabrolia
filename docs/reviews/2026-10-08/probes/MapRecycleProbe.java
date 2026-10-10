import com.axalotl.fabrolia.api.fastutil.Long2IntConcurrentHashMap;
import com.axalotl.fabrolia.api.fastutil.Long2LongConcurrentHashMap;
import com.axalotl.fabrolia.api.fastutil.Long2ByteConcurrentHashMap;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;

public class MapRecycleProbe {
    interface Access {
        long get(long key);
        void put(long key, long value);
        void remove(long key);
    }
    static int hash(long key) {
        key *= 0xC6BC279692B5C323L;
        return (int)(key ^ (key >>> 32));
    }
    public static void main(String[] args) throws Exception {
        Field bits = Long2IntConcurrentHashMap.class.getDeclaredField("NSEG_BITS");
        bits.setAccessible(true);
        int nbits = bits.getInt(null);
        long first = 1;
        long second = 2;
        while ((hash(first) >>> (32 - nbits)) != (hash(second) >>> (32 - nbits))
                || (hash(first) & 15) != (hash(second) & 15)) second++;
        long other = second;
        String kind = args.length == 0 ? "int" : args[0];
        Access map;
        if (kind.equals("long")) {
            var delegate = new Long2LongConcurrentHashMap(8);
            map = new Access() {
                public long get(long key) { return delegate.get(key); }
                public void put(long key, long value) { delegate.put(key, value); }
                public void remove(long key) { delegate.remove(key); }
            };
        } else if (kind.equals("byte")) {
            var delegate = new Long2ByteConcurrentHashMap(8);
            map = new Access() {
                public long get(long key) { return delegate.get(key); }
                public void put(long key, long value) { delegate.put(key, (byte)value); }
                public void remove(long key) { delegate.remove(key); }
            };
        } else {
            var delegate = new Long2IntConcurrentHashMap(8);
            map = new Access() {
                public long get(long key) { return delegate.get(key); }
                public void put(long key, long value) { delegate.put(key, (int)value); }
                public void remove(long key) { delegate.remove(key); }
            };
        }
        map.put(first, 11);
        var stop = new AtomicBoolean();
        var failure = new AtomicReference<String>();
        var threads = new ArrayList<Thread>();
        Thread writer = new Thread(() -> {
            while (!stop.get()) {
                map.remove(first);
                map.put(other, 22);
                map.remove(other);
                map.put(first, 11);
            }
        });
        threads.add(writer);
        for (int i = 0; i < 6; i++) threads.add(new Thread(() -> {
            while (!stop.get()) {
                long got = map.get(first);
                if (got != 0 && got != 11) {
                    failure.compareAndSet(null, kind + ": get(" + first + ") returned foreign value " + got
                            + "; colliding key=" + other);
                    stop.set(true);
                }
            }
        }));
        for (Thread thread : threads) thread.start();
        long until = System.nanoTime() + 8_000_000_000L;
        while (!stop.get() && System.nanoTime() < until) Thread.sleep(20);
        stop.set(true);
        for (Thread thread : threads) thread.join();
        System.out.println(failure.get() == null ? "No wrong-key read observed in 8 seconds." : failure.get());
    }
}
