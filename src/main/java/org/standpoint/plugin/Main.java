package org.standpoint.plugin;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.model.Sharpening;
import org.standpoint.plugin.pipeline.PipelineResult;
import org.standpoint.plugin.pipeline.PipelineResultConverter;
import org.standpoint.plugin.pipeline.ResolvedPlaceholder;
import org.standpoint.plugin.pipeline.StandpointPipeline;
import org.standpoint.plugin.util.PipelineLogger;

import java.io.File;
import java.util.Map;

public class Main {

    public static void main(String[] args) throws Exception {
        File owlFile = new File("C:\\Users\\Omar\\Downloads\\OwlTest\\test1.rdf");

        // Load ontology from file
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(owlFile);

        // Run pipeline
        StandpointPipeline pipeline = new StandpointPipeline(ontology, PipelineLogger.Level.ON);
        PipelineResult result = pipeline.run();
        if (result == null) return;

        // Convert to OWL-native representation
        PipelineResultConverter converter = new PipelineResultConverter(result);
        Map<String, ResolvedPlaceholder> resolved = converter.convert();

        // Print resolved map
        System.out.println("\n=== RESOLVED PLACEHOLDER MAP ===\n");
        for (Map.Entry<String, ResolvedPlaceholder> e : resolved.entrySet()) {
            ResolvedPlaceholder rp = e.getValue();
            System.out.println(e.getKey() + " → " + rp);
            System.out.println("   owlAxiom:   " + rp.owlAxiom);
            System.out.println("   owlTree:    " + rp.owlTree);
            System.out.println("   childKeys:  " + rp.childKeys);
            System.out.println("   isRoot:     " + rp.isRoot);
            System.out.println("   type:       " + rp.axiomType);
            System.out.println();
        }

        System.out.println("=== SHARPENINGS ===\n");
        for (Sharpening s : result.sharpenings) {
            System.out.println(s);
        }

    }
}