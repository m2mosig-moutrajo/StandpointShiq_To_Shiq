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
import org.standpoint.plugin.translation.*;
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
    private final PipelineLogger          logger;
    private final OWLDataFactory          df;

    public PrecisificationPipeline(StandpointKnowledgeBase kb,
                                   PipelineLogger.Level level) {
        this.kb     = kb;
        this.logger = new PipelineLogger(level);
        this.df     = kb.sourceOntology
                .getOWLOntologyManager().getOWLDataFactory();
    }

    public PrecisificationContext run() {

        PlaceholderDeduplicator deduplicator =
                new PlaceholderDeduplicator(kb.owlMap, df);
        // canonicalKey already set — pass it in for resolveTree calls
        deduplicator.setCanonicalKey(kb.canonicalKey);

        // Step 3 — Collect standpoints and diamonds
        logger.log("\n=== STEP 3 — Collect standpoints and diamonds ===");
        PrecisificationCollector collector =
                new PrecisificationCollector(kb);
        Set<String>         standpoints = collector.collectStandpoints();
        Set<DiamondExpression> diamonds    = collector.collectDiamondSubterms();

        logger.log("Standpoints: " + standpoints);
        logger.log("Diamond subterms: " + diamonds.size());
        diamonds.forEach(d ->
                logger.log("  " + d.placeholderKey
                        + " → ◇_" + d.standpoint
                        + "[" + d.concept + "]"));

        // Step 3b — Canonicalise placeholder IRIs inside diamond concepts
        logger.log("\n=== STEP 3b — Resolve diamond concepts ===");
        deduplicator.resolveDiamondConcepts(diamonds);
        logger.log("  Diamond concepts resolved.");

        // Step 4 — Build concept map for all canonical non-root entries
        logger.log("\n=== STEP 4 — Concept map (D_n assignment) ===");
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

        logger.log("  D_n   op       standpoint   concept");
        logger.log("  ────────────────────────────────────────────");
        kb.owlMap.forEach((key, ax) -> {
            if (ax.isRoot || ax.owlTree == null) return;
            String canonical = kb.canonicalKey.getOrDefault(key, key);
            if (!canonical.equals(key)) return;
            String dn = conceptMap.getIdForSp(key);
            String op = ax.operator == org.standpoint.plugin.model.Operator.BOX
                    ? "□" : "◇";
            logger.log(String.format("  %-6s %-8s %-12s %s",
                    dn, op, ax.standpoint, ax.owlTree));
        });

        // Step 5 — Compute standpoint closures
        logger.log("\n=== STEP 5 — Standpoint closures t^K ===");
        SharpeningClosureCalculator closureCalc =
                new SharpeningClosureCalculator(kb.sharpenings, standpoints);
        Map<String, Set<String>> closures = closureCalc.computeAllClosures();
        closures.forEach((s, c) ->
                logger.log("  " + s + "^K = " + c));

        // Step 6 — Build precisification set
        logger.log("\n=== STEP 6 — Build Π_K ===");
        Set<OWLNamedIndividual> individuals =
                kb.sourceOntology.getIndividualsInSignature();
        PrecisificationSet precSet = PrecisificationSet.build(
                standpoints, diamonds, individuals, closures);

        logger.log("  Total precisifications: " + precSet.size());
        logger.log("  STANDPOINT worlds:");
        precSet.getAllPrecisifications().stream()
                .filter(p -> p.type ==
                        org.standpoint.plugin.model.PrecisificationType.STANDPOINT)
                .forEach(p -> logger.log("    " + p));
        logger.log("  ANONYMOUS worlds:");
        precSet.getAllPrecisifications().stream()
                .filter(p -> p.type ==
                        org.standpoint.plugin.model.PrecisificationType.ANONYMOUS_0
                        || p.type ==
                        org.standpoint.plugin.model.PrecisificationType.ANONYMOUS_1)
                .forEach(p -> logger.log("    " + p));
        logger.log("  NAMED worlds:");
        precSet.getAllPrecisifications().stream()
                .filter(p -> p.type ==
                        org.standpoint.plugin.model.PrecisificationType.NAMED)
                .forEach(p -> logger.log("    " + p));

        logger.log("  σ per standpoint:");
        standpoints.forEach(s ->
                logger.log("    σ(" + s + ") = " + precSet.sigma(s)));

        // Step 7 — Build SP_n → D_n flat map
        logger.log("\n=== STEP 7 — SP_n → D_n resolution map ===");
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
                logger.log("  " + sp + " → " + dn));

        logger.log("\n✅ Precisification pipeline complete.");
        return new PrecisificationContext(
                standpoints, diamonds, closures,
                precSet, spToDiamondId, conceptMap);
    }
}