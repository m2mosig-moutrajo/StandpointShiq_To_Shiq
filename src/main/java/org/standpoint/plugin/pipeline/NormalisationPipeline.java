package org.standpoint.plugin.pipeline;

import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.standpoint.plugin.pipeline.data.StandpointKnowledgeBase;
import org.standpoint.plugin.pipeline.normalisation.AnnotationProcessor;
import org.standpoint.plugin.pipeline.normalisation.ManchesterToOWLConverter;
import org.standpoint.plugin.pipeline.normalisation.PlaceholderDeduplicator;
import org.standpoint.plugin.util.PipelineLogger;

/**
 * Pipeline 1 — Normalisation.
 *
 * Steps covered:
 *   1. AnnotationProcessor.run()         — parse annotations, normalise, build manchesterMap
 *   2. ManchesterToOWLConverter.convert()— convert to OWL-native owlMap
 *   2b. PlaceholderDeduplicator          — deduplicate, build canonicalKey
 *
 * Input:  OWLOntology (source)
 * Output: StandpointKnowledgeBase with owlMap and canonicalKey populated
 */
public class NormalisationPipeline {

    private final OWLOntology     ontology;

    public NormalisationPipeline(OWLOntology ontology) {
        this.ontology = ontology;
    }

    public StandpointKnowledgeBase run() throws Exception {

        // Step 1 — Parse annotations and normalise
        PipelineLogger.log("=== STEP 1 — Normalisation ===");
        AnnotationProcessor standpointPipeline =
                new AnnotationProcessor(ontology);
        StandpointKnowledgeBase kb = standpointPipeline.run();

        if (kb == null) {
            PipelineLogger.log("Pipeline returned null — no formulas found.");
            return null;
        }

        PipelineLogger.log("Placeholder map size: " + kb.manchesterMap.size());
        kb.manchesterMap.forEach((key, mp) ->
                PipelineLogger.log("  " + key + " → " + mp.manchester
                        + (mp.isRoot ? " [ROOT]" : "")));

        // Step 2 — Convert Manchester strings to OWL objects
        PipelineLogger.log("\n=== STEP 2 — Manchester → OWL conversion ===");
        ManchesterToOWLConverter converter = new ManchesterToOWLConverter(kb);
        converter.convert();

        PipelineLogger.log("owlMap size: " + kb.owlMap.size());
        kb.owlMap.forEach((key, ax) -> {
            String repr = ax.isRoot
                    ? ax.owlAxiom.toString()
                    : ax.owlTree.toString();
            PipelineLogger.log("  Converted: " + key + " → "
                    + (ax.operator == org.standpoint.plugin.model.Operator.BOX ? "□" : "◇")
                    + "_" + ax.standpoint + "[" + repr + "]"
                    + (ax.isRoot ? " [ROOT]" : "")
                    + (!ax.childKeys.isEmpty() ? " children=" + ax.childKeys : ""));
        });

        // Step 2b — Deduplicate placeholder map
        PipelineLogger.log("\n=== STEP 2b — Deduplication ===");
        OWLDataFactory df = kb.sourceOntology
                .getOWLOntologyManager().getOWLDataFactory();
        PlaceholderDeduplicator deduplicator =
                new PlaceholderDeduplicator(kb.owlMap, df);
        kb.canonicalKey = deduplicator.deduplicate();

        boolean hasDuplicates = kb.canonicalKey.entrySet().stream()
                .anyMatch(e -> !e.getKey().equals(e.getValue()));
        if (!hasDuplicates) {
            PipelineLogger.log("  (no duplicates found)");
        } else {
            kb.canonicalKey.forEach((k, v) -> {
                if (!k.equals(v))
                    PipelineLogger.log("  " + k + " → " + v + "  [duplicate]");
            });
        }

        PipelineLogger.log("\n✅ Normalisation pipeline complete.");
        return kb;
    }
}