package org.standpoint.plugin.pipeline;

import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.standpoint.plugin.pipeline.data.NormalisedAxiom;
import org.standpoint.plugin.pipeline.data.StandpointKnowledgeBase;
import org.standpoint.plugin.pipeline.normalisation.PlaceholderDeduplicator;
import org.standpoint.plugin.pipeline.precisification.PrecisificationCollector;
import org.standpoint.plugin.pipeline.precisification.PrecisificationContext;
import org.standpoint.plugin.pipeline.precisification.PrecisificationSet;
import org.standpoint.plugin.pipeline.precisification.SharpeningClosureCalculator;
import org.standpoint.plugin.pipeline.translation.ConceptMap;
import org.standpoint.plugin.pipeline.translation.DiamondExpression;
import org.standpoint.plugin.util.PipelineLogger;

import java.util.*;

/**
 * Pipeline 2 — Build Precisification World Set.
 *
 * Steps covered:
 *   3.  PrecisificationCollector  — collect standpoints and diamond subterms
 *   3b. resolveDiamondConcepts    — canonicalise placeholder IRIs in diamond concepts
 *   4.  ConceptMap                — assign D_n to all canonical non-root entries
 *   5.  SharpeningClosureCalculator         — compute s^K for every standpoint
 *   6.  PrecisificationSet.build()— build Π_K and σ function
 *   7.  spToDiamondId             — flat SP_n → D_n map covering all entries
 *
 * Input:  StandpointKnowledgeBase (from NormalisationPipeline)
 * Output: PrecisificationContext
 */
public class PrecisificationPipeline {

    private final StandpointKnowledgeBase kb;
    private final OWLDataFactory          df;

    public PrecisificationPipeline(StandpointKnowledgeBase kb) {
        this.kb     = kb;
        this.df     = kb.sourceOntology
                .getOWLOntologyManager().getOWLDataFactory();
    }

    public PrecisificationContext run() {

        PlaceholderDeduplicator deduplicator =
                new PlaceholderDeduplicator(kb.owlMap, df);
        // canonicalKey already set — pass it in for resolveTree calls
        deduplicator.setCanonicalKey(kb.canonicalKey);

        // Step 3 — Collect standpoints and diamonds
        PipelineLogger.log("\n=== STEP 3 — Collect standpoints and diamonds ===");
        PrecisificationCollector collector =
                new PrecisificationCollector(kb);
        Set<String>         standpoints = collector.collectStandpoints();
        Set<DiamondExpression> diamonds    = collector.collectDiamondSubterms();

        PipelineLogger.log("Standpoints: " + standpoints);
        PipelineLogger.log("Diamond subterms: " + diamonds.size());
        diamonds.forEach(d ->
                PipelineLogger.log("  " + d.placeholderKey
                        + " → ◇_" + d.standpoint
                        + "[" + d.concept + "]"));

        // Step 3b — Canonicalise placeholder IRIs inside diamond concepts
        PipelineLogger.log("\n=== STEP 3b — Resolve diamond concepts ===");
        deduplicator.resolveDiamondConcepts(diamonds);
        PipelineLogger.log("  Diamond concepts resolved.");

        // Step 4 — Build concept map for all canonical non-root entries
        PipelineLogger.log("\n=== STEP 4 — Concept map (D_n assignment) ===");
        ConceptMap conceptMap = new ConceptMap();

        for (Map.Entry<String, NormalisedAxiom> e : kb.owlMap.entrySet()) {
            String key         = e.getKey();
            NormalisedAxiom ax = e.getValue();

            if (ax.isRoot || ax.owlTree == null) continue;

            String canonical = kb.canonicalKey.getOrDefault(key, key);
            if (!canonical.equals(key)) continue;

            OWLClassExpression resolved =
                    deduplicator.resolveTree(ax.owlTree, kb.canonicalKey);
            conceptMap.addEntry(key, resolved);
        }

        // assign diamondId on each DiamondExpression
        for (DiamondExpression d : diamonds) {
            String dn = conceptMap.getIdForSp(d.placeholderKey);
            if (dn != null) d.diamondId = dn;
        }

        PipelineLogger.log("  D_n   op       standpoint   concept");
        PipelineLogger.log("  ────────────────────────────────────────────");
        kb.owlMap.forEach((key, ax) -> {
            if (ax.isRoot || ax.owlTree == null) return;
            String canonical = kb.canonicalKey.getOrDefault(key, key);
            if (!canonical.equals(key)) return;
            String dn = conceptMap.getIdForSp(key);
            String op = ax.operator == org.standpoint.plugin.model.Operator.BOX
                    ? "□" : "◇";
            PipelineLogger.log(String.format("  %-6s %-8s %-12s %s",
                    dn, op, ax.standpoint, ax.owlTree));
        });

        // Step 5 — Compute standpoint closures
        PipelineLogger.log("\n=== STEP 5 — Standpoint closures t^K ===");
        SharpeningClosureCalculator closureCalc =
                new SharpeningClosureCalculator(kb.sharpening, standpoints);
        Map<String, Set<String>> closures = closureCalc.computeAllClosures();
        closures.forEach((s, c) ->
                PipelineLogger.log("  " + s + "^K = " + c));

        // Step 5b — Collect only individuals actually used in normalised axioms
        Set<OWLNamedIndividual> usedIndividuals = collectUsedIndividuals(kb);
        PipelineLogger.log("\n=== STEP 5b — Used individuals ===");
        PipelineLogger.log("  All individuals: " + kb.sourceOntology.getIndividualsInSignature().size());
        PipelineLogger.log("  Used individuals: " + usedIndividuals.size());
        usedIndividuals.forEach(i -> PipelineLogger.log("    " + i.getIRI().getShortForm()));

        // Step 6 — Build precisification set
        PipelineLogger.log("\n=== STEP 6 — Build Π_K ===");

        PrecisificationSet precSet = PrecisificationSet.build(standpoints, diamonds, usedIndividuals, closures);

        PipelineLogger.log("  Total precisifications: " + precSet.size());
        PipelineLogger.log("  STANDPOINT worlds:");
        precSet.getAllPrecisifications().stream()
                .filter(p -> p.type ==
                        org.standpoint.plugin.model.PrecisificationType.STANDPOINT)
                .forEach(p -> PipelineLogger.log("    " + p));
        PipelineLogger.log("  ANONYMOUS worlds:");
        precSet.getAllPrecisifications().stream()
                .filter(p -> p.type ==
                        org.standpoint.plugin.model.PrecisificationType.ANONYMOUS_0
                        || p.type ==
                        org.standpoint.plugin.model.PrecisificationType.ANONYMOUS_1)
                .forEach(p -> PipelineLogger.log("    " + p));
        PipelineLogger.log("  NAMED worlds:");
        precSet.getAllPrecisifications().stream()
                .filter(p -> p.type ==
                        org.standpoint.plugin.model.PrecisificationType.NAMED)
                .forEach(p -> PipelineLogger.log("    " + p));

        PipelineLogger.log("  σ per standpoint:");
        standpoints.forEach(s ->
                PipelineLogger.log("    σ(" + s + ") = " + precSet.sigma(s)));

        // Step 7 — Build SP_n → D_n flat map
        PipelineLogger.log("\n=== STEP 7 — SP_n → D_n resolution map ===");
        Map<String, String> spToDiamondId = new LinkedHashMap<>();

        // canonical entries
        kb.owlMap.forEach((key, ax) -> {
            if (ax.isRoot || ax.owlTree == null) return;
            String canonical = kb.canonicalKey.getOrDefault(key, key);
            if (!canonical.equals(key)) return;
            String dn = conceptMap.getIdForSp(key);
            if (dn != null) spToDiamondId.put(key, dn);
        });

        // duplicate entries inherit D_n from canonical
        kb.canonicalKey.forEach((spn, canonical) -> {
            if (!spn.equals(canonical)
                    && spToDiamondId.containsKey(canonical)) {
                spToDiamondId.put(spn, spToDiamondId.get(canonical));
            }
        });

        spToDiamondId.forEach((sp, dn) ->
                PipelineLogger.log("  " + sp + " → " + dn));

        PipelineLogger.log("\n✅ Precisification pipeline complete.");
        return new PrecisificationContext(
                standpoints, diamonds, closures,
                precSet, spToDiamondId, kb);
    }
    /**
     * Collects all individuals that actually appear in at least one
     * normalised axiom entry in owlMap — either as concept assertions
     * or role assertions.
     * Individuals that exist in the ontology signature but are never
     * referenced in any standpoint axiom are excluded — they would
     * otherwise create unnecessary named precisification worlds.
     */
    private Set<OWLNamedIndividual> collectUsedIndividuals(
            StandpointKnowledgeBase kb) {

        Set<OWLNamedIndividual> used = new LinkedHashSet<>();

        for (NormalisedAxiom ax : kb.owlMap.values()) {
            if (ax.owlAxiom != null) {
                used.addAll(ax.owlAxiom.getIndividualsInSignature());
            }
            if (ax.owlTree != null) {
                used.addAll(ax.owlTree.getIndividualsInSignature());
            }
        }

        return used;
    }
}