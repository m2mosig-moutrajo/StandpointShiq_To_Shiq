package org.standpoint.plugin;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.model.Operator;
import org.standpoint.plugin.pipeline.*;
import org.standpoint.plugin.pipeline.data.StandpointKnowledgeBase;
import org.standpoint.plugin.pipeline.precisification.PrecisificationContext;
import org.standpoint.plugin.util.PipelineLogger;

import java.io.File;

public class Main {

    public static void main(String[] args) throws Exception {

        File inputFile  = new File("C:\\Users\\Omar\\Downloads\\OwlTest\\test99.rdf");
        File outputFile = new File("C:\\Users\\Omar\\Downloads\\OwlTest\\test99Translated.rdf");

        PipelineLogger.setLevel(PipelineLogger.Level.ON);

        OWLOntologyManager manager  = OWLManager.createOWLOntologyManager();
        OWLOntology        ontology = manager.loadOntologyFromOntologyDocument(inputFile);

        // Pipeline 1 — Normalise
        StandpointKnowledgeBase kb = new NormalisationPipeline(ontology).run();
        if (kb == null) return;

//        // Pipeline 2 — Build worlds
//        PrecisificationContext ctx = new PrecisificationPipeline(kb).run();
//
//        // Pipeline 3 — Translate and save
//        new TranslationPipeline(kb, ctx, outputFile).run();

    }
}
