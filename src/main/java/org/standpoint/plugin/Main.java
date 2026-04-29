package org.standpoint.plugin;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.pipeline.*;
import org.standpoint.plugin.pipeline.data.StandpointKnowledgeBase;
import org.standpoint.plugin.pipeline.precisification.PrecisificationContext;
import org.standpoint.plugin.util.PipelineLogger;

import java.io.File;

public class Main {

    public static void main(String[] args) throws Exception {

        File inputFile  = new File("C:\\Users\\Omar\\Downloads\\OwlTest\\test2.rdf");
        File outputFile = new File("C:\\Users\\Omar\\Downloads\\OwlTest\\test2Translated.rdf");

        OWLOntologyManager manager  = OWLManager.createOWLOntologyManager();
        OWLOntology        ontology = manager.loadOntologyFromOntologyDocument(inputFile);

        // Pipeline 1 — Normalise
        StandpointKnowledgeBase kb = new NormalisationPipeline(ontology, PipelineLogger.Level.OFF).run();
        if (kb == null) return;

        // Pipeline 2 — Build worlds
        PrecisificationContext ctx = new PrecisificationPipeline(kb, PipelineLogger.Level.OFF).run();

        // Pipeline 3 — Translate and save
        new TranslationPipeline(kb, ctx, outputFile, PipelineLogger.Level.OFF).run();
    }
}