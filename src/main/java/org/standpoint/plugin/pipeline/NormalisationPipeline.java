package org.standpoint.plugin.pipeline;

import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.standpoint.plugin.pipeline.data.StandpointKnowledgeBase;
import org.standpoint.plugin.pipeline.normalisation.AnnotationProcessor;
import org.standpoint.plugin.pipeline.normalisation.PlaceholderDeduplicator;
import org.standpoint.plugin.util.PipelineLogger;

public class NormalisationPipeline {

    private final OWLOntology ontology;

    public NormalisationPipeline(OWLOntology ontology) {
        this.ontology = ontology;
    }

    public StandpointKnowledgeBase run() throws Exception {

        PipelineLogger.log("=== STEP 1 — Normalisation ===");
        StandpointKnowledgeBase kb = new AnnotationProcessor(ontology).run();

        if (kb == null) {
            PipelineLogger.log("Pipeline returned null — no formulas found.");
            return null;
        }

        PipelineLogger.log("\n=== STEP 2 — Deduplication ===");
        OWLDataFactory df = kb.sourceOntology
                .getOWLOntologyManager().getOWLDataFactory();
        kb.canonicalKey = new PlaceholderDeduplicator(kb.owlMap, df).deduplicate();

        boolean hasDuplicates = kb.canonicalKey.entrySet().stream()
                .anyMatch(e -> !e.getKey().equals(e.getValue()));
        if (!hasDuplicates) {
            PipelineLogger.log("  (no duplicates found)");
        } else {
            kb.canonicalKey.forEach((k, v) -> {
                if (!k.equals(v))
                    PipelineLogger.log("  " + k + " → " + v + "  [duplicate]");
                else
                    PipelineLogger.log("  " + k + " → " + v);
            });
        }

        PipelineLogger.log("\nNormalisation pipeline complete.");
        return kb;
    }
}