package org.standpoint.plugin.pipeline;

import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.pipeline.data.NormalisedAxiom;
import org.standpoint.plugin.pipeline.data.StandpointKnowledgeBase;
import org.standpoint.plugin.pipeline.precisification.PrecisificationContext;
import org.standpoint.plugin.translation.*;
import org.standpoint.plugin.util.PipelineLogger;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
    private final OWLDataFactory           df;

    public TranslationPipeline(StandpointKnowledgeBase kb,
                               PrecisificationContext ctx,
                               File outputFile) {
        this.kb         = kb;
        this.ctx        = ctx;
        this.outputFile = outputFile;
        this.df         = kb.sourceOntology
                .getOWLOntologyManager().getOWLDataFactory();
    }

    public OWLOntology run() throws Exception {

        // Step 8 — Run Trans(K)
        PipelineLogger.log("\n=== STEP 8 — Trans(K) translation ===");
        AuxiliaryNameFactory aux = new AuxiliaryNameFactory(
                kb, ctx.spToDiamondId, df);
        ConceptTranslator conceptTranslator = new ConceptTranslator(
                kb, aux, ctx.precSet);
        StandpointTranslator transK = new StandpointTranslator(
                kb, ctx.precSet, aux, conceptTranslator);

        OWLOntology translated = transK.translate();
        addDnLegend(translated, ctx, kb);

        PipelineLogger.log("  Total axioms: " + translated.getAxiomCount());
        PipelineLogger.log("\n  -- Type (1): Auxiliary definitions --");
        translated.getAxioms().stream()
                .filter(a -> a instanceof OWLSubClassOfAxiom)
                .map(a -> (OWLSubClassOfAxiom) a)
                .filter(a -> a.getSubClass() instanceof OWLClass
                        && a.getSubClass().asOWLClass()
                        .getIRI().getShortForm().startsWith(AuxiliaryNameFactory.AUX_PREFIX))
                .forEach(a -> PipelineLogger.log("    " + a));

        PipelineLogger.log("\n  -- Type (2)-(6): Root axiom translations --");
        translated.getAxioms().stream()
                .filter(a -> a instanceof OWLSubClassOfAxiom)
                .map(a -> (OWLSubClassOfAxiom) a)
                .filter(a -> a.getSubClass().isOWLThing())
                .forEach(a -> PipelineLogger.log("    " + a));
        translated.getAxioms().stream()
                .filter(a -> !(a instanceof OWLSubClassOfAxiom))
                .forEach(a -> PipelineLogger.log("    " + a));

        // Step 9 — Save to file
        if (outputFile != null) {
            PipelineLogger.log("\n=== STEP 9 — Save translated ontology ===");
            try {
                translated.getOWLOntologyManager().saveOntology(
                        translated,
                        new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat(),
                        IRI.create(outputFile.toURI()));
                PipelineLogger.log("  Saved to: " + outputFile.getAbsolutePath());
                PipelineLogger.log("  Axiom count: " + translated.getAxiomCount());
            } catch (OWLOntologyStorageException e) {
                System.err.println("Failed to save: " + e.getMessage());
                e.printStackTrace();
            }
        }

        PipelineLogger.log("\n✅ Translation pipeline complete.");
        return translated;
    }
    /**
     * Adds a rdfs:comment annotation to the translated ontology header
     * summarising what each D_n identifier represents.
     * Format:
     *   === D_n Legend ===
     *   D1 = ◇_s1 [ Person ]
     *   D3 = ◇_s3 [ r some SP_2 ]
     *
     *   === SP References ===
     *   SP_2 = □_s3 [ G ]
     */
    private void addDnLegend(OWLOntology translated,
                             PrecisificationContext ctx,
                             StandpointKnowledgeBase kb) {

        OWLOntologyManager manager = translated.getOWLOntologyManager();
        OWLDataFactory df           = manager.getOWLDataFactory();

        StringBuilder legend = new StringBuilder();
        legend.append("=== D_n Legend ===\n");

        Map<String, String> dnToDescription = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : ctx.spToDiamondId.entrySet()) {
            String spKey = e.getKey();
            String dn    = e.getValue();

            if (dnToDescription.containsKey(dn)) continue;

            NormalisedAxiom ax = kb.owlMap.get(spKey);
            if (ax == null || ax.owlTree == null) continue;

            String op      = ax.operator == org.standpoint.plugin.model.Operator.BOX
                    ? "□" : "◇";
            String concept = renderToManchester(ax.owlTree);

            dnToDescription.put(dn, dn + " = " + op + "_" + ax.standpoint
                    + " [ " + concept + " ]");
        }

        dnToDescription.values().forEach(line ->
                legend.append(line).append("\n"));

        // Collect all SP_n references found in D_n descriptions
        Set<String> spReferenced = new LinkedHashSet<>();
        for (String desc : dnToDescription.values()) {
            collectSpReferences(desc, spReferenced);
        }

        // Expand recursively until no new SP_n remain
        Set<String> alreadyExpanded = new LinkedHashSet<>();
        boolean firstSp = true;

        while (!spReferenced.isEmpty()) {
            Set<String> nextLevel = new LinkedHashSet<>();

            for (String spKey : spReferenced) {
                if (alreadyExpanded.contains(spKey)) continue;
                alreadyExpanded.add(spKey);

                NormalisedAxiom ax = kb.owlMap.get(spKey);
                if (ax == null || ax.owlTree == null) continue;

                if (firstSp) {
                    legend.append("\n=== SP References ===\n");
                    firstSp = false;
                }

                String op      = ax.operator == org.standpoint.plugin.model.Operator.BOX
                        ? "□" : "◇";
                String concept = renderToManchester(ax.owlTree);

                legend.append(spKey).append(" = ")
                        .append(op).append("_").append(ax.standpoint)
                        .append(" [ ").append(concept).append(" ]\n");

                // Check if this SP also references other SPs — recurse
                collectSpReferences(concept, nextLevel);
            }

            spReferenced = nextLevel;
            spReferenced.removeAll(alreadyExpanded);
        }

        OWLAnnotation comment = df.getOWLAnnotation(
                df.getRDFSComment(),
                df.getOWLLiteral(legend.toString()));

        manager.applyChange(new AddOntologyAnnotation(translated, comment));
        PipelineLogger.log("  D_n legend added to ontology header.");
    }

    /**
     * Collects all SP_n tokens found in a rendered string.
     */
    private void collectSpReferences(String rendered, Set<String> result) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("SP_\\d+")
                .matcher(rendered);
        while (m.find()) {
            result.add(m.group());
        }
    }

    /**
     * Renders an OWLClassExpression to Manchester syntax string.
     */
    private String renderToManchester(OWLClassExpression expr) {
        try {
            ManchesterOWLSyntaxOWLObjectRendererImpl renderer =
                    new ManchesterOWLSyntaxOWLObjectRendererImpl();
            return renderer.render(expr);
        } catch (Exception e) {
            return expr.toString();
        }
    }
}