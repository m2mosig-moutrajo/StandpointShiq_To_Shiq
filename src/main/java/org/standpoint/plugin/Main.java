package org.standpoint.plugin;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.pipeline.NormalisedKnowledgeBase;
import org.standpoint.plugin.pipeline.ManchesterToOWLConverter;
import org.standpoint.plugin.pipeline.StandpointPipeline;
import org.standpoint.plugin.translation.DiamondSubterm;
import org.standpoint.plugin.translation.PrecisificationCollector;
import org.standpoint.plugin.translation.PrecisificationSet;
import org.standpoint.plugin.translation.SharpeningClosure;
import org.standpoint.plugin.util.PipelineLogger;

import java.io.File;
import java.util.Map;
import java.util.Set;

public class Main {

    public static void main(String[] args) throws Exception {
        File owlFile = new File("C:\\Users\\Omar\\Downloads\\OwlTest\\test2.rdf");

        // Load ontology from file
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(owlFile);

        // Step 1 — Run pipeline
        NormalisedKnowledgeBase kb = new StandpointPipeline(ontology, PipelineLogger.Level.ON).run();
        if (kb == null) return;

        // Step 2 — Convert to OWL-native representation
        ManchesterToOWLConverter converter = new ManchesterToOWLConverter(kb);
        converter.convert();

        // Step 3 — Collect standpoints and diamonds
        PrecisificationCollector collector = new PrecisificationCollector(kb);
        Set<String> standpoints            = collector.collectStandpoints();
        Set<DiamondSubterm> diamonds       = collector.collectDiamondSubterms();

        // Step 4 — Compute standpoint closures
        SharpeningClosure closureCalc         = new SharpeningClosure(kb.sharpenings, standpoints);
        Map<String, Set<String>> closures     = closureCalc.computeAllClosures();

        // Step 5 — Build precisification set
        Set<OWLNamedIndividual> individuals   = kb.sourceOntology.getIndividualsInSignature();
        PrecisificationSet precSet            = PrecisificationSet.build(standpoints, diamonds, individuals, closures);

        // Print
        System.out.println("\n=== NS(K) — Standpoints ===");
        standpoints.forEach(System.out::println);

        System.out.println("\n=== ◇_sC — Diamond Subterms ===");
        diamonds.forEach(System.out::println);

        System.out.println("\n=== t^K — Standpoint Closures ===");
        closures.forEach((t, c) -> System.out.println(t + "^K = " + c));

        System.out.println("\n=== Π_K — All Precisifications (" + precSet.size() + ") ===");
        precSet.getAllPrecisifications().forEach(System.out::println);

        System.out.println("\n=== σ per standpoint ===");
        for (String s : standpoints) {
            System.out.println("σ(" + s + ") = " + precSet.sigma(s));
        }
    }
}