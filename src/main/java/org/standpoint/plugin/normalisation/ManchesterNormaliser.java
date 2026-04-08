package org.standpoint.plugin.normalisation;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;

import java.util.Collections;

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

    // Converts "C SubClassOf D" → "owl:Thing SubClassOf NNF(¬C ⊔ D)"
    public String normaliseSubClassOf(String gciManchesterExpr) {
        gciManchesterExpr = gciManchesterExpr.trim();

        int subClassOfIdx = gciManchesterExpr.indexOf("SubClassOf");
        if (subClassOfIdx == -1) {
            // No SubClassOf found — already a concept expression, skip
            return gciManchesterExpr;
        }

        String subClassExpr  = gciManchesterExpr.substring(0, subClassOfIdx).trim();
        String superClassExpr = gciManchesterExpr.substring(subClassOfIdx + "SubClassOf".length()).trim();

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

        return "Thing SubClassOf " + renderToManchester(normalisedRHS);
    }

    // Applies NNF to a pure concept expression (no SubClassOf)
    // Returns expression unchanged if parsing fails (e.g. contains placeholders)
    public String applyNNFToConceptExpression(String manchesterExpr) {
        manchesterExpr = manchesterExpr.trim();
        try {
            OWLClassExpression owlExpr = parseManchesterExpression(manchesterExpr);
            return renderToManchester(owlExpr.getNNF());
        } catch (Exception e) {
            return manchesterExpr;
        }
    }

    // Finds the index of SubClassOf at the top level (not inside parentheses)
    public int findTopLevelSubClassOf(String manchesterExpr) {
        int parenDepth = 0;
        int i = 0;
        while (i < manchesterExpr.length()) {
            char c = manchesterExpr.charAt(i);
            if (c == '(') parenDepth++;
            else if (c == ')') parenDepth--;
            else if (parenDepth == 0 && manchesterExpr.startsWith("SubClassOf", i)) {
                return i;
            }
            i++;
        }
        return -1;
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
}