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

        File inputFile  = new File("C:\\Users\\Omar\\Downloads\\OwlTest\\test44.rdf");
        File outputFile = new File("C:\\Users\\Omar\\Downloads\\OwlTest\\test44Translated.rdf");

        PipelineLogger.setLevel(PipelineLogger.Level.ON);

        OWLOntologyManager manager  = OWLManager.createOWLOntologyManager();
        OWLOntology        ontology = manager.loadOntologyFromOntologyDocument(inputFile);

        // ── Pipeline 1 only ────────────────────────────────
        StandpointKnowledgeBase kb = new NormalisationPipeline(ontology).run();

        if (kb == null) {
            System.out.println("No formulas found.");
            return;
        }

//        // ── Print owlMap and STOP ───────────────────────────
//        System.out.println("\n=== owlMap after normalisation ===");
//        System.out.println("owlMap size: " + kb.owlMap.size());
//
//        ManchesterOWLSyntaxOWLObjectRendererImpl renderer =
//                new ManchesterOWLSyntaxOWLObjectRendererImpl();
//
//        kb.owlMap.forEach((key, ax) -> {
//            String op = ax.operator == Operator.BOX ? "□" : "◇";
//
//            String manchester;
//            String owlRaw;
//            String owlType;
//
//            if (ax.isRoot) {
//                manchester = renderer.render(ax.owlAxiom);
//                owlRaw     = ax.owlAxiom.toString();
//                owlType    = ax.owlAxiom.getAxiomType().getName();
//            } else {
//                manchester = renderer.render(ax.owlTree);
//                owlRaw     = ax.owlTree.toString();
//                owlType    = ax.owlTree.getClass().getSimpleName();
//            }
//
//            System.out.println("  " + key + " → " + op + "_" + ax.standpoint
//                    + (ax.isRoot ? " [ROOT]" : ""));
//            System.out.println("    Manchester : " + manchester);
//            System.out.println("    OWL type   : " + owlType);
//            System.out.println("    OWL raw    : " + owlRaw);
//            System.out.println();
//        });
//
//        System.out.println("\n✅ Step 2 complete — stopping here.");
        // Pipelines 2 and 3 NOT called
    }
}
