package org.standpoint.plugin.pipeline;

import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.pipeline.data.StandpointKnowledgeBase;
import org.standpoint.plugin.pipeline.precisification.PrecisificationContext;
import org.standpoint.plugin.translation.*;
import org.standpoint.plugin.util.PipelineLogger;

import java.io.File;

/**
 * Pipeline 3 — Translation.
 *
 * Steps covered:
 *   8. StandpointTranslator.translate() — emit Type (1)-(6) axioms into plain SHIQ ontology
 *   9. Save to RDF/XML file
 *
 * Input:  StandpointKnowledgeBase + PrecisificationContext + output file path
 * Output: OWLOntology (translated plain SHIQ)
 */
public class TranslationPipeline {

    private final StandpointKnowledgeBase kb;
    private final PrecisificationContext ctx;
    private final File                     outputFile;
    private final PipelineLogger           logger;
    private final OWLDataFactory           df;

    public TranslationPipeline(StandpointKnowledgeBase kb,
                               PrecisificationContext ctx,
                               File outputFile,
                               PipelineLogger.Level level) {
        this.kb         = kb;
        this.ctx        = ctx;
        this.outputFile = outputFile;
        this.logger     = new PipelineLogger(level);
        this.df         = kb.sourceOntology
                .getOWLOntologyManager().getOWLDataFactory();
    }

    public OWLOntology run() throws Exception {

        // Step 8 — Run Trans(K)
        logger.log("\n=== STEP 8 — Trans(K) translation ===");
        AuxiliaryNameFactory aux = new AuxiliaryNameFactory(
                kb, ctx.spToDiamondId, df);
        ConceptTranslator conceptTranslator = new ConceptTranslator(
                kb, aux, ctx.precSet);
        StandpointTranslator transK = new StandpointTranslator(
                kb, ctx.precSet, aux, conceptTranslator);

        OWLOntology translated = transK.translate();

        logger.log("  Total axioms: " + translated.getAxiomCount());
        logger.log("\n  -- Type (1): Auxiliary definitions --");
        translated.getAxioms().stream()
                .filter(a -> a instanceof OWLSubClassOfAxiom)
                .map(a -> (OWLSubClassOfAxiom) a)
                .filter(a -> a.getSubClass() instanceof OWLClass
                        && a.getSubClass().asOWLClass()
                        .getIRI().getShortForm().startsWith("AUX_"))
                .forEach(a -> logger.log("    " + a));

        logger.log("\n  -- Type (2)-(6): Root axiom translations --");
        translated.getAxioms().stream()
                .filter(a -> a instanceof OWLSubClassOfAxiom)
                .map(a -> (OWLSubClassOfAxiom) a)
                .filter(a -> a.getSubClass().isOWLThing())
                .forEach(a -> logger.log("    " + a));
        translated.getAxioms().stream()
                .filter(a -> !(a instanceof OWLSubClassOfAxiom))
                .forEach(a -> logger.log("    " + a));

        // Step 9 — Save to file
        if (outputFile != null) {
            logger.log("\n=== STEP 9 — Save translated ontology ===");
            try {
                translated.getOWLOntologyManager().saveOntology(
                        translated,
                        new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat(),
                        IRI.create(outputFile.toURI()));
                logger.log("  Saved to: " + outputFile.getAbsolutePath());
                logger.log("  Axiom count: " + translated.getAxiomCount());
            } catch (OWLOntologyStorageException e) {
                System.err.println("Failed to save: " + e.getMessage());
                e.printStackTrace();
            }
        }

        logger.log("\n✅ Translation pipeline complete.");
        return translated;
    }
}