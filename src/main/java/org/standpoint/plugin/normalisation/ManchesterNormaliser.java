package org.standpoint.plugin.normalisation;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.OntologyAxiomPair;
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;
import org.standpoint.plugin.util.PipelineLogger;

import java.util.Collections;
import java.util.Set;

public class ManchesterNormaliser {

    private final OWLDataFactory df;
    private final OWLOntologyManager helperManager;
    private final OWLOntology helperOntology;

    public ManchesterNormaliser(OWLDataFactory df,
                                OWLOntologyManager helperManager,
                                OWLOntology helperOntology) {
        this.df = df;
        this.helperManager = helperManager;
        this.helperOntology = helperOntology;
    }

    // Converts "C SubClassOf: D" → "owl:Thing SubClassOf: NNF(¬C ⊔ D)"
    public String normaliseSubClassOf(String gciManchesterExpr) {
        gciManchesterExpr = gciManchesterExpr.trim();

        int subClassOfIdx = gciManchesterExpr.indexOf("SubClassOf:");
        if (subClassOfIdx == -1) {
            // No SubClassOf: found — already a concept expression, skip
            return gciManchesterExpr;
        }

        String subClassExpr  = gciManchesterExpr.substring(0, subClassOfIdx).trim();
        String superClassExpr = gciManchesterExpr.substring(subClassOfIdx + "SubClassOf:".length()).trim();

        OWLClassExpression subClass  = parseManchesterExpression(subClassExpr);
        OWLClassExpression superClass = parseManchesterExpression(superClassExpr);

        // Build ⊤ ⊑ NNF(¬subClass ⊔ superClass)
        OWLClassExpression normalisedRHS;
        if (subClass.isOWLThing()) {
            normalisedRHS = superClass.getNNF();
        } else {
            normalisedRHS = df.getOWLObjectUnionOf(
                    df.getOWLObjectComplementOf(subClass), superClass
            ).getNNF();
        }

        return "Thing SubClassOf: " + renderToManchester(normalisedRHS);
    }

    // Applies NNF to a pure concept expression (no SubClassOf:)
    // Returns expression unchanged if parsing fails (e.g. contains placeholders)
    public String applyNNFToConceptExpression(String manchesterExpr) {
        manchesterExpr = manchesterExpr.trim();
        try {
            OWLClassExpression owlExpr = parseManchesterExpression(manchesterExpr);
            return renderToManchester(owlExpr.getNNF());
        } catch (Exception e) {
            PipelineLogger.log("WARNING: could not apply NNF to expression '" + manchesterExpr + "' — " + e.getMessage());
            return manchesterExpr;
        }
    }

    public OWLClassExpression parseManchesterExpression(String manchesterExpr) {
        manchesterExpr = manchesterExpr
                .replace("owl:Nothing", "Nothing")
                .replace("owl:Thing", "Thing");

        ManchesterOWLSyntaxParser parser = OWLManager.createManchesterParser();
        parser.setStringToParse(manchesterExpr);
        parser.setOWLEntityChecker(
                new org.semanticweb.owlapi.expression.ShortFormEntityChecker(
                        new org.semanticweb.owlapi.util.BidirectionalShortFormProviderAdapter(
                                helperManager,
                                Collections.singleton(helperOntology),
                                new org.semanticweb.owlapi.util.SimpleShortFormProvider()
                        )
                )
        );
        return parser.parseClassExpression();
    }

    private String renderToManchester(OWLClassExpression owlExpr) {
        java.io.StringWriter writer = new java.io.StringWriter();
        org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxObjectRenderer renderer =
                new org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxObjectRenderer(
                        writer,
                        new org.semanticweb.owlapi.util.SimpleShortFormProvider()
                );
        owlExpr.accept(renderer);
        return writer.toString().replaceAll("\\s+", " ").trim();
    }

    // Parses a full Manchester axiom string into an OWLAxiom.
    // Handles all axiom types:
    //   "Thing SubClassOf: FR_12 some FC_11"   → OWLSubClassOfAxiom
    //   "john Type: Cat"                       → OWLClassAssertionAxiom
    //   "hasPet SubPropertyOf: hasAnimal"      → OWLSubObjectPropertyOfAxiom
    //   "john hasAnimal fido"                 → OWLObjectPropertyAssertionAxiom
    //   "Transitive hasAncestor"              → OWLTransitiveObjectPropertyAxiom
    // Returns null if parsing fails — caller should log a warning.
    public OWLAxiom parseAxiom(String manchesterAxiomExpr) {
        manchesterAxiomExpr = manchesterAxiomExpr.trim()
                .replace("owl:Nothing", "Nothing")
                .replace("owl:Thing", "Thing");
        try {
            ManchesterOWLSyntaxParser parser = OWLManager.createManchesterParser();
            parser.setStringToParse(manchesterAxiomExpr);
            parser.setOWLEntityChecker(buildEntityChecker());
            parser.setDefaultOntology(helperOntology);

            if (manchesterAxiomExpr.startsWith("Individual:")) {
                // Role assertion — requires frame parsing
                // parseFrames() returns Set<OntologyAxiomPair> in OWL API 4
                Set<OntologyAxiomPair> pairs = parser.parseFrames();
                if (pairs != null && !pairs.isEmpty()) {
                    return pairs.iterator().next().getAxiom();
                }
                return null;
            }
            return parser.parseAxiom();
        } catch (Exception e) {
            return null;
        }
    }
    // Builds the entity checker using the helper ontology for name resolution
    private org.semanticweb.owlapi.expression.OWLEntityChecker buildEntityChecker() {
        return new org.semanticweb.owlapi.expression.ShortFormEntityChecker(
                new org.semanticweb.owlapi.util.BidirectionalShortFormProviderAdapter(
                        helperManager,
                        Collections.singleton(helperOntology),
                        new org.semanticweb.owlapi.util.SimpleShortFormProvider()
                )
        );
    }
}