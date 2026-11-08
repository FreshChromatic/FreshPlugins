package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;

import java.util.List;

record BiomeSelection(String biomeId, List<MarkedChunk> chunks, boolean truncated) {

    BiomeSelection {
        chunks = List.copyOf(chunks);
    }
}
