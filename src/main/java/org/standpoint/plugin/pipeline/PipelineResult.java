package org.standpoint.plugin.pipeline;

import org.standpoint.plugin.model.ModalPlaceholder;
import org.standpoint.plugin.model.Sharpening;

import java.util.List;
import java.util.Map;

public class PipelineResult {
    public final Map<String, ModalPlaceholder> normalisedPlaceholderMap;
    public final List<Sharpening> sharpenings;

    public PipelineResult(Map<String, ModalPlaceholder> normalisedPlaceholderMap,
                          List<Sharpening> sharpenings) {
        this.normalisedPlaceholderMap = normalisedPlaceholderMap;
        this.sharpenings = sharpenings;
    }

    public boolean isEmpty() {
        return (normalisedPlaceholderMap.isEmpty() && sharpenings.isEmpty());
    }
}
