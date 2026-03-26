package org.standpoint.plugin.parser;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.pipeline.PipelineResult;
import org.standpoint.plugin.pipeline.StandpointPipeline;
import org.standpoint.plugin.translation.SharpeningStatement;

import java.io.File;
import java.util.Map;

public class Main {

    public static void main(String[] args) throws Exception {
        File owlFile = new File("C:\\Users\\Omar\\Downloads\\testSharp.rdf");

        // Load ontology from file
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(owlFile);

        // Run pipeline
        PipelineResult result = new StandpointPipeline(ontology).run();
        if (result == null) return;

        // Print results
        System.out.println("\n=== FULL NORMALISED PLACEHOLDER MAP ===\n");
        for (Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry> e :
                result.normalisedPlaceholderMap.entrySet()) {
            System.out.println(e.getKey() + " → " + e.getValue());
        }

        System.out.println("\n=== SHARPENINGS ===\n");
        if (result.sharpenings.isEmpty()) {
            System.out.println("(none)");
        } else {
            for (SharpeningStatement s : result.sharpenings) {
                System.out.println(s);
            }
        }
    }
}