package org.standpoint.plugin;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.pipeline.PipelineResult;
import org.standpoint.plugin.pipeline.StandpointPipeline;
import org.standpoint.plugin.util.PipelineLogger;

import java.io.File;

public class Main {

    public static void main(String[] args) throws Exception {
        File owlFile = new File("C:\\Users\\Omar\\Downloads\\OwlTest\\testSimpleNonNegated.rdf");

        // Load ontology from file
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(owlFile);

        // Run pipeline
        StandpointPipeline pipeline = new StandpointPipeline(ontology, PipelineLogger.Level.ON);
        PipelineResult result = pipeline.run();
        if (result == null) return;
    }
}