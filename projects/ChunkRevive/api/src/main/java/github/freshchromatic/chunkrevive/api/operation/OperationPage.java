package github.freshchromatic.chunkrevive.api.operation;

import java.util.List;
public record OperationPage(int total, List<OperationSnapshot> operations) { public OperationPage { operations = List.copyOf(operations); } }
