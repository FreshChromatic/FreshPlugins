package github.freshchromatic.chunkrevive.feature.status;

import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarkedChunkClaimCounterTest {

    @Test
    void checksClaimsInTickBoundedBatches() {
        var protection = mock(LandProtection.class);
        var world = mock(World.class);
        var scheduled = new ArrayDeque<Runnable>();
        Executor nextTick = scheduled::add;
        when(protection.hasClaim(eq(world), anyInt(), anyInt())).thenAnswer(
            invocation -> invocation.<Integer>getArgument(1) % 2 == 0);
        var chunks = java.util.stream.IntStream.range(0, 65)
            .mapToObj(index -> new MarkedChunk("world", index, 0,
            UUID.randomUUID(), index))
            .toList();
        var counter = new MarkedChunkClaimCounter(protection, nextTick, ignored -> world);

        var result = counter.count(chunks);

        assertFalse(result.isDone());
        verify(protection, org.mockito.Mockito.never()).hasClaim(eq(world), anyInt(), anyInt());
        scheduled.remove().run();
        assertFalse(result.isDone());
        verify(protection, org.mockito.Mockito.times(64)).hasClaim(eq(world), anyInt(), anyInt());
        scheduled.remove().run();

        assertEquals(33L, result.join());
        verify(protection, org.mockito.Mockito.times(65)).hasClaim(eq(world), anyInt(), anyInt());
    }
}
