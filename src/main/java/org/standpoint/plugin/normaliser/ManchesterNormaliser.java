package org.standpoint.plugin.normaliser;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.OntologyAxiomPair;
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;
import org.standpoint.plugin.model.StandpointAxiomType;

import java.util.Collections;
import java.util.Set;

import static org.standpoint.plugin.model.StandpointAxiomType.*;

public class ManchesterNormaliser {

    private final OWLOntologyManager helperManager;
    private final OWLOntology helperOntology;

    public ManchesterNormaliser(OWLDataFactory df,
                                OWLOntologyManager helperManager,
                                OWLOntology helperOntology) {
        this.helperManager = helperManager;
        this.helperOntology = helperOntology;
    }

    public OWLClassExpression parseManchesterExpression(String manchesterExpr) {
//        manchesterExpr = manchesterExpr
//                .replace("owl:Nothing", "Nothing")
//                .replace("owl:Thing", "Thing");

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

    // Parses a full Manchester axiom string into an OWLAxiom.
    // Handles all axiom types:
    //   "Thing SubClassOf: FR_12 some FC_11"   → OWLSubClassOfAxiom
    //   "john Type: Cat"                       → OWLClassAssertionAxiom
    //   "hasPet SubPropertyOf: hasAnimal"      → OWLSubObjectPropertyOfAxiom
    //   "john hasAnimal fido"                 → OWLObjectPropertyAssertionAxiom
    //   "Transitive hasAncestor"              → OWLTransitiveObjectPropertyAxiom
    // Returns null if parsing fails — caller should log a warning.
    public OWLAxiom parseAxiom(String manchesterAxiomExpr, StandpointAxiomType standpointAxiomType) {
        manchesterAxiomExpr = manchesterAxiomExpr.trim();
        //     .replace("owl:Nothing", "Nothing")
        //     .replace("owl:Thing", "Thing");
        try {
            ManchesterOWLSyntaxParser parser = OWLManager.createManchesterParser();
            parser.setStringToParse(manchesterAxiomExpr);
            parser.setOWLEntityChecker(buildEntityChecker());
            parser.setDefaultOntology(helperOntology);

            // ------------------------------------------------------------
            // TODO: FRAME HANDLING LIMITATION
            // ------------------------------------------------------------
            // "Individual: ..." is NOT a single axiom in OWL semantics.
            // It is a FRAME that can generate multiple axioms such as:
            //   - ClassAssertion
            //   - ObjectPropertyAssertion
            //   - DataPropertyAssertion
            //
            // IMPORTANT:
            // This method returns ONLY ONE OWLAxiom, so we lose information
            // when multiple axioms are produced.
            //
            // Example:
            //   Individual: alice
            //     Types: Student
            //     Facts: knows bob
            //
            // produces:
            //   ClassAssertion(Student alice)
            //   ObjectPropertyAssertion(knows alice bob)
            //
            // Current implementation returns only the FIRST axiom.
            // This works for simple Protégé-like cases but is NOT safe
            // for full OWL parsing or reasoning pipelines.
            //
            // TODO (IMPORTANT):
            // Consider changing return type to:
            //   Set<OWLAxiom> or List<OWLAxiom>
            // OR explicitly document that frames are truncated.
            // ------------------------------------------------------------
            if (standpointAxiomType == ROLE_ASSERTION || standpointAxiomType == CONCEPT_DISJOINT_UNION || standpointAxiomType == CONCEPT_DISJOINT) {
                if (standpointAxiomType == CONCEPT_DISJOINT) {
                    try {
                        return parser.parseAxiom();
                    } catch (Exception ignored) {
                        manchesterAxiomExpr = "Class: owl:Thing " + manchesterAxiomExpr;
                        parser.setStringToParse(manchesterAxiomExpr);
                    }
                }
                // Role assertion — requires frame parsing
                // parseFrames() returns Set<OntologyAxiomPair> in OWL API 4
                Set<OntologyAxiomPair> pairs = parser.parseFrames();
                for (OntologyAxiomPair p : pairs) {
                    OWLAxiom ax = p.getAxiom();

                    if (!(ax instanceof OWLDeclarationAxiom)) {
                        return ax;
                    }
                }
                return pairs.iterator().next().getAxiom();
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