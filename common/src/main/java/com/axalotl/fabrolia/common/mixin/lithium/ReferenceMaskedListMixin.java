package com.axalotl.fabrolia.common.mixin.lithium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.caffeinemc.mods.lithium.common.util.collections.ReferenceMaskedList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Mixin(value = ReferenceMaskedList.class, priority = 1500, remap = false)
public class ReferenceMaskedListMixin<E> {

    @Final
    @Shadow
    private Reference2IntOpenHashMap<E> element2Index;

    @Unique
    private final Object fabrolia$lock = new Object();

    @WrapMethod(method = "add(Ljava/lang/Object;)Z")
    private boolean fabrolia$add(E element, Operation<Boolean> original) {
        synchronized (fabrolia$lock) {
            if (element2Index.containsKey(element)) return false;
            return original.call(element);
        }
    }

    @WrapMethod(method = "remove")
    private boolean fabrolia$remove(Object element, Operation<Boolean> original) {
        synchronized (fabrolia$lock) {
            return original.call(element);
        }
    }

    @WrapMethod(method = "addOrSet")
    private void fabrolia$addOrSet(E element, boolean visible, Operation<Void> original) {
        synchronized (fabrolia$lock) {
            original.call(element, visible);
        }
    }

    @WrapMethod(method = "setVisible")
    private void fabrolia$setVisible(E element, boolean visible, Operation<Void> original) {
        synchronized (fabrolia$lock) {
            original.call(element, visible);
        }
    }

    @WrapMethod(method = "iterator")
    private Iterator<E> fabrolia$iterator(Operation<Iterator<E>> original) {
        synchronized (fabrolia$lock) {
            List<E> snapshot = new ArrayList<>();
            Iterator<E> it = original.call();
            while (it.hasNext()) {
                snapshot.add(it.next());
            }
            return snapshot.iterator();
        }
    }

    @WrapMethod(method = "totalSize")
    private int fabrolia$totalSize(Operation<Integer> original) {
        synchronized (fabrolia$lock) {
            return original.call();
        }
    }
}