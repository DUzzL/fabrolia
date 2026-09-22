package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BlockEntityTickerListTest {
    @Test
    void interleavedCellsRestoreTheAuthoritativeRegistrationOrder() {
        Random random = new Random(0xB10C);
        ArrayList<TickingBlockEntity> registered = new ArrayList<>();
        BlockEntityTickerList.Builder[] builders = new BlockEntityTickerList.Builder[37];
        Arrays.setAll(builders, ignored -> new BlockEntityTickerList.Builder(0));
        for (int i = 0; i < 4096; i++) {
            TickingBlockEntity ticker = new Ticker(i);
            registered.add(ticker);
            builders[random.nextInt(builders.length)].add(ticker, i);
        }
        ArrayList<BlockEntityTickerList> cells = new ArrayList<>();
        for (var builder : builders) cells.add(builder.build());
        java.util.Collections.shuffle(cells, random);
        assertEquals(registered, merged(cells, Map.of()));
    }

    @Test
    void capturedCellKeepsItsOrderAfterAppendSweepAndSequenceMetadataRelease() {
        TickingBlockEntity a = new Ticker(0), b = new Ticker(1), c = new Ticker(2), d = new Ticker(3);
        BlockEntityTickerList first = BlockEntityTickerList.singleton(a, 0).append(c, 2);
        BlockEntityTickerList second = BlockEntityTickerList.singleton(b, 1);
        Map<TickingBlockEntity, Long> metadata = new HashMap<>(Map.of(a, 0L, b, 1L, c, 2L));
        BlockEntityTickerList.Merge entered = new BlockEntityTickerList.Merge(List.of(first, second), metadata);
        assertTrue(entered.advance());
        assertSame(a, entered.ticker());
        BlockEntityTickerList appended = second.append(d, 3);
        BlockEntityTickerList swept = first.without(Set.of(a));
        metadata.clear();
        ArrayList<TickingBlockEntity> remainder = new ArrayList<>();
        while (entered.advance()) remainder.add(entered.ticker());
        assertEquals(List.of(b, c), remainder);
        assertEquals(List.of(b, c, d), merged(List.of(swept, appended), metadata));
        assertEquals(List.of(a, b, c), merged(List.of(first, second), metadata));
        assertThrows(UnsupportedOperationException.class, () -> first.set(0, d));
    }

    @Test
    void builderReuseCannotChangeAnAlreadyPublishedCell() {
        TickingBlockEntity a = new Ticker(0), b = new Ticker(1), c = new Ticker(2);
        BlockEntityTickerList.Builder builder = new BlockEntityTickerList.Builder(8);
        builder.add(a, 0);
        BlockEntityTickerList published = builder.build();
        builder.add(c, 2);
        BlockEntityTickerList other = BlockEntityTickerList.singleton(b, 1);
        assertEquals(List.of(a, b), merged(List.of(published, other), Map.of()));
        assertEquals(List.of(a, b, c), merged(List.of(builder.build(), other), Map.of()));
    }

    @Test
    void foreignMissingAndEqualSequencesMatchTheOriginalHeapDiscipline() {
        TickingBlockEntity a = new Ticker(0), b = new Ticker(1), c = new Ticker(2);
        TickingBlockEntity d = new Ticker(3), e = new Ticker(4), f = new Ticker(5);
        List<List<TickingBlockEntity>> cells = Arrays.asList(
                List.of(a, d), null, List.of(b, e), List.of(), List.of(c, f));
        Map<TickingBlockEntity, Long> sequences = Map.of(d, 9L, e, 9L, f, 10L);
        assertEquals(baseline(cells, sequences), merged(cells, sequences));
        BlockEntityTickerList indexed = BlockEntityTickerList.singleton(d, 9).append(f, 10);
        assertEquals(List.of(a, b, d, f), merged(List.of(List.of(a, b), indexed), Map.of()));
    }

    @Test
    void manyEqualHeadsAndExtremeSequencesMatchPriorityQueue() {
        List<List<TickingBlockEntity>> cells = new ArrayList<>();
        Map<TickingBlockEntity, Long> sequences = new HashMap<>();
        for (int cell = 0; cell < 8; cell++) {
            TickingBlockEntity first = new Ticker(cell * 3);
            TickingBlockEntity middle = new Ticker(cell * 3 + 1);
            TickingBlockEntity last = new Ticker(cell * 3 + 2);
            if ((cell & 1) == 0) sequences.put(first, Long.MIN_VALUE);
            sequences.put(middle, 0L);
            sequences.put(last, Long.MAX_VALUE);
            cells.add(List.of(first, middle, last));
        }
        assertEquals(baseline(cells, sequences), merged(cells, sequences));
    }

    @Test
    void genericSuccessorSequenceIsObservedAfterThePreviousTicker() {
        TickingBlockEntity a = new Ticker(0), b = new Ticker(1), c = new Ticker(2);
        Map<TickingBlockEntity, Long> sequences = new HashMap<>(Map.of(a, 0L, b, 1L, c, 2L));
        BlockEntityTickerList.Merge merge = new BlockEntityTickerList.Merge(List.of(List.of(a, b), List.of(c)), sequences);
        assertTrue(merge.advance());
        assertSame(a, merge.ticker());
        sequences.put(b, 3L);
        assertTrue(merge.advance());
        assertSame(c, merge.ticker());
        assertTrue(merge.advance());
        assertSame(b, merge.ticker());
        assertFalse(merge.advance());
    }

    private static List<TickingBlockEntity> merged(List<? extends List<TickingBlockEntity>> cells,
                                                   Map<TickingBlockEntity, Long> sequences) {
        BlockEntityTickerList.Merge merge = new BlockEntityTickerList.Merge(cells, sequences);
        ArrayList<TickingBlockEntity> result = new ArrayList<>();
        while (merge.advance()) result.add(merge.ticker());
        return result;
    }

    private record Head(List<TickingBlockEntity> list, int index, long sequence) {}

    private static List<TickingBlockEntity> baseline(List<? extends List<TickingBlockEntity>> cells,
                                                    Map<TickingBlockEntity, Long> sequences) {
        PriorityQueue<Head> heap = new PriorityQueue<>(Comparator.comparingLong(Head::sequence));
        for (var cell : cells) {
            if (cell != null && !cell.isEmpty()) heap.add(new Head(cell, 0, sequences.getOrDefault(cell.get(0), Long.MIN_VALUE)));
        }
        ArrayList<TickingBlockEntity> result = new ArrayList<>();
        while (!heap.isEmpty()) {
            Head head = heap.poll();
            result.add(head.list.get(head.index));
            int next = head.index + 1;
            if (next < head.list.size()) heap.add(new Head(head.list, next, sequences.getOrDefault(head.list.get(next), Long.MIN_VALUE)));
        }
        return result;
    }

    private record Ticker(int id) implements TickingBlockEntity {
        @Override public void tick() { throw new AssertionError("merge must not execute ticker bodies"); }
        @Override public boolean isRemoved() { return false; }
        @Override public BlockPos getPos() { return BlockPos.ZERO; }
        @Override public String getType() { return "test:merge"; }
    }
}
