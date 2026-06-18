package org.standpoint.plugin.pipeline.precisification;

import org.standpoint.plugin.pipeline.data.StandpointKnowledgeBase;
import org.standpoint.plugin.pipeline.translation.DiamondExpression;

import java.util.Map;
import java.util.Set;

/**
 * Output of PrecisificationPipeline.
 * Bundles everything Translation needs — worlds, closures, and SP_n → D_n map.
 */
public class PrecisificationContext {

    public final Set<String>              standpoints;
    public final Set<DiamondExpression>      diamonds;
    public final Map<String, Set<String>> closures;
    public final PrecisificationSet       precSet;
    public final Map<String, String>      spToDiamondId;
    public final StandpointKnowledgeBase kb;

    public PrecisificationContext(
            Set<String>              standpoints,
            Set<DiamondExpression>      diamonds,
            Map<String, Set<String>> closures,
            PrecisificationSet       precSet,
            Map<String, String>      spToDiamondId, StandpointKnowledgeBase kb) {

        this.standpoints   = standpoints;
        this.diamonds      = diamonds;
        this.closures      = closures;
        this.precSet       = precSet;
        this.spToDiamondId = spToDiamondId;
        this.kb = kb;
    }
}