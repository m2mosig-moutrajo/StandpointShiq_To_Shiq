package org.standpoint.plugin.parser;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.pipeline.PipelineResult;
import org.standpoint.plugin.pipeline.StandpointPipeline;
import org.standpoint.plugin.translation.SharpeningStatement;
import org.standpoint.plugin.util.PipelineLogger;

import java.io.File;
import java.util.Map;

public class Main {

    public static void main(String[] args) throws Exception {
        File owlFile = new File("C:\\Users\\Omar\\Downloads\\testNewFormula.rdf");

        // Load ontology from file
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(owlFile);

        // Run pipeline
        PipelineResult result = new StandpointPipeline(ontology, PipelineLogger.Level.ON).run();
        if (result == null) return;
    }
}