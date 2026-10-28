package com.axalotl.fabrolia.common.mixin.utils;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.*;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = {
        Int2ObjectLinkedOpenHashMap.class,
        LongLinkedOpenHashSet.class,
        ObjectOpenCustomHashSet.class,
        Long2LongOpenHashMap.class,
        Long2ObjectLinkedOpenHashMap.class,
        ReferenceOpenHashSet.class,
        Reference2ReferenceArrayMap.class,
        Object2LongOpenHashMap.class,
        IntArrayList.class,
        Reference2IntOpenHashMap.class,
        ReferenceArrayList.class,
        Object2ReferenceOpenCustomHashMap.class,
        Reference2LongOpenHashMap.class,
}, priority = 50000)
public class FastUtilSynchronizeMixin {
}