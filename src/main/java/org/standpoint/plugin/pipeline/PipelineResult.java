package org.standpoint.plugin.pipeline;

import org.standpoint.plugin.parser.PlaceholderSubstituter;
import org.standpoint.plugin.translation.SharpeningStatement;

import java.util.List;
import java.util.Map;

public class PipelineResult {
    public final Map<String, PlaceholderSubstituter.PlaceholderEntry> normalisedPlaceholderMap;
    public final List<SharpeningStatement> sharpenings;

    public PipelineResult(Map<String, PlaceholderSubstituter.PlaceholderEntry> normalisedPlaceholderMap,
                          List<SharpeningStatement> sharpenings) {
        this.normalisedPlaceholderMap = normalisedPlaceholderMap;
        this.sharpenings = sharpenings;
    }

    public boolean isEmpty() {
        return normalisedPlaceholderMap.isEmpty();
    }
}
