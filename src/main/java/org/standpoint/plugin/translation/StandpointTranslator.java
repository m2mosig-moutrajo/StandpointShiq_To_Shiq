package org.standpoint.plugin.translation;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.model.Precisification;
import org.standpoint.plugin.pipeline.data.NormalisedAxiom;
import org.standpoint.plugin.pipeline.data.StandpointKnowledgeBase;
import org.standpoint.plugin.pipeline.precisification.PrecisificationSet;
import org.standpoint.plugin.util.PipelineLogger;

import java.util.*;

/**
 * Implements Trans(K) from Section 4 (KR 2024).
 *
 * Produces a plain SHIQ OWLOntology equisatisfiable with the input KB.
 *
 * Six axiom types:
 * (1) AUX_D_n_π ⊑ trans(π, C)          — for each non-root SP_n, each π ∈ Π_K
 * (2) ⊤ ⊑ trans(π, C)                  — for each root □_s[⊤⊑C], each π ∈ σ(s)
 * (3) S^π ⊑ R^π                         — for each root □_s[S⊑R], each π ∈ σ(s)
 * (4) Tra(R^π)                           — for each root □_s[Tra(R)], each π ∈ σ(s)
 * (5) trans(π,C)(a)                      — for each root □_s[C(a)], each π ∈ σ(s)
 * (6) R^π(a,b)                           — for each root □_s[R(a,b)], each π ∈ σ(s)
 */
public class StandpointTranslator {

    private final StandpointKnowledgeBase kb;
    private final PrecisificationSet precSet;
    private final AuxiliaryNameFactory aux;
    private final ConceptTranslator conceptTranslator;
    private final OWLDataFactory df;
    private final OWLOntologyManager manager;

    public StandpointTranslator(StandpointKnowledgeBase kb,
                                PrecisificationSet precSet,
                                AuxiliaryNameFactory aux,
                                ConceptTranslator conceptTranslator) {
        this.kb                 = kb;
        this.precSet            = precSet;
        this.aux                = aux;
        this.conceptTranslator  = conceptTranslator;
        this.manager            = OWLManager.createOWLOntologyManager();
        this.df                 = manager.getOWLDataFactory();
    }

    /**
     * Runs the full Trans(K) translation.
     * Returns a plain SHIQ OWLOntology.
     */
    public OWLOntology translate() throws OWLOntologyCreationException {
        String outputIRI = kb.sourceOntology.getOntologyID().getOntologyIRI().get() + "/transK";
        OWLOntology output = manager.createOntology(IRI.create(outputIRI));

        Map<String, NormalisedAxiom> owlMap = kb.owlMap;

        // TYPE (1) — AUX_D_n_π ⊑ trans(π, C)
        // for every non-root entry with owlTree and every π ∈ Π_K
        PipelineLogger.log("\n=== TYPE (1) — Auxiliary definitions ===");
        for (Map.Entry<String, NormalisedAxiom> e : owlMap.entrySet()) {
            String key         = e.getKey();
            NormalisedAxiom ax = e.getValue();
            if (ax.owlTree == null) continue;

            Set<Precisification> piSK = precSet.sigma(ax.standpoint);
            for (Precisification pi : piSK) {
                OWLClass lhs = aux.getAuxConcept(key, pi);
                OWLClassExpression rhs = conceptTranslator.trans(pi, ax.owlTree);
                OWLAxiom axiom = df.getOWLSubClassOfAxiom(lhs, rhs);
                manager.addAxiom(output, axiom);
                PipelineLogger.log("  " + lhs.getIRI().getShortForm() + " ⊑ " + rhs);
            }
        }

        // TYPES (2)-(6) — root entries
        for (Map.Entry<String, NormalisedAxiom> e : owlMap.entrySet()) {
            NormalisedAxiom ax = e.getValue();
            if (!ax.isRoot || ax.owlAxiom == null) continue;

            Set<Precisification> piSK = precSet.sigma(ax.standpoint);

            if (ax.owlAxiom instanceof OWLSubClassOfAxiom) {
                translateGCI(e.getKey(), ax, (OWLSubClassOfAxiom) ax.owlAxiom, piSK, output);

            } else if (ax.owlAxiom instanceof OWLClassAssertionAxiom) {
                translateConceptAssertion(e.getKey(), ax, (OWLClassAssertionAxiom) ax.owlAxiom, piSK, output);

            } else if (ax.owlAxiom instanceof OWLSubObjectPropertyOfAxiom) {
                translateRoleInclusion(e.getKey(), ax, (OWLSubObjectPropertyOfAxiom) ax.owlAxiom, piSK, output);

            } else if (ax.owlAxiom instanceof OWLTransitiveObjectPropertyAxiom) {
                translateTransitivity(e.getKey(), ax, (OWLTransitiveObjectPropertyAxiom) ax.owlAxiom, piSK, output);

            } else if (ax.owlAxiom instanceof OWLObjectPropertyAssertionAxiom) {
                translateRoleAssertion(e.getKey(), ax, (OWLObjectPropertyAssertionAxiom) ax.owlAxiom, piSK, output);

            } else {
                PipelineLogger.log("WARNING StandpointTranslator: unhandled axiom type for " + e.getKey() + ": " + ax.owlAxiom.getClass().getSimpleName());
            }
        }

        return output;
    }

    // TYPE (2) — ⊤ ⊑ trans(π, C)
    private void translateGCI(String key, NormalisedAxiom ax,
                              OWLSubClassOfAxiom gci,
                              Set<Precisification> piSK,
                              OWLOntology output) {
        PipelineLogger.log("\n=== TYPE (2) — GCI " + key + " □_" + ax.standpoint + " ===");
        OWLClassExpression rhs = gci.getSuperClass();
        for (Precisification pi : piSK) {
            OWLClassExpression translated = conceptTranslator.trans(pi, rhs);
            OWLAxiom axiom = df.getOWLSubClassOfAxiom(df.getOWLThing(), translated);
            manager.addAxiom(output, axiom);
            PipelineLogger.log("  [" + pi.id + "] ⊤ ⊑ " + translated);
        }
    }

    // TYPE (5) — trans(π, C)(a)
    private void translateConceptAssertion(String key, NormalisedAxiom ax,
                                           OWLClassAssertionAxiom assertion,
                                           Set<Precisification> piSK,
                                           OWLOntology output) {
        PipelineLogger.log("\n=== TYPE (5) — Concept assertion " + key + " □_" + ax.standpoint + " ===");
        OWLClassExpression concept    = assertion.getClassExpression();
        OWLNamedIndividual individual = (OWLNamedIndividual) assertion.getIndividual();
        for (Precisification pi : piSK) {
            OWLClassExpression translated = conceptTranslator.trans(pi, concept);
            OWLAxiom axiom = df.getOWLClassAssertionAxiom(translated, individual);
            manager.addAxiom(output, axiom);
            PipelineLogger.log("  [" + pi.id + "] " + translated + "(" + individual.getIRI().getShortForm() + ")");
        }
    }

    // TYPE (3) — S^π ⊑ R^π
    private void translateRoleInclusion(String key, NormalisedAxiom ax,
                                        OWLSubObjectPropertyOfAxiom ri,
                                        Set<Precisification> piSK,
                                        OWLOntology output) {
        PipelineLogger.log("\n=== TYPE (3) — Role inclusion " + key + " □_" + ax.standpoint + " ===");
        OWLObjectProperty sub = (OWLObjectProperty) ri.getSubProperty();
        OWLObjectProperty sup = (OWLObjectProperty) ri.getSuperProperty();
        for (Precisification pi : piSK) {
            OWLObjectProperty subPi = aux.getCopiedRole(sub, pi);
            OWLObjectProperty supPi = aux.getCopiedRole(sup, pi);
            OWLAxiom axiom = df.getOWLSubObjectPropertyOfAxiom(subPi, supPi);
            manager.addAxiom(output, axiom);
            PipelineLogger.log("  [" + pi.id + "] " + subPi.getIRI().getShortForm() + " ⊑ " + supPi.getIRI().getShortForm());
        }
    }

    // TYPE (4) — Tra(R^π)
    private void translateTransitivity(String key, NormalisedAxiom ax,
                                       OWLTransitiveObjectPropertyAxiom tra,
                                       Set<Precisification> piSK,
                                       OWLOntology output) {
        PipelineLogger.log("\n=== TYPE (4) — Transitivity " + key + " □_" + ax.standpoint + " ===");
        OWLObjectProperty role = (OWLObjectProperty) tra.getProperty();
        for (Precisification pi : piSK) {
            OWLObjectProperty rolePi = aux.getCopiedRole(role, pi);
            OWLAxiom axiom = df.getOWLTransitiveObjectPropertyAxiom(rolePi);
            manager.addAxiom(output, axiom);
            PipelineLogger.log("  [" + pi.id + "] Tra(" + rolePi.getIRI().getShortForm() + ")");
        }
    }

    // TYPE (6) — R^π(a, b)
    private void translateRoleAssertion(String key, NormalisedAxiom ax,
                                        OWLObjectPropertyAssertionAxiom ra,
                                        Set<Precisification> piSK,
                                        OWLOntology output) {
        PipelineLogger.log("\n=== TYPE (6) — Role assertion " + key + " □_" + ax.standpoint + " ===");
        OWLObjectProperty role = (OWLObjectProperty) ra.getProperty();
        OWLNamedIndividual subject = (OWLNamedIndividual) ra.getSubject();
        OWLNamedIndividual object = (OWLNamedIndividual) ra.getObject();
        for (Precisification pi : piSK) {
            OWLObjectProperty rolePi = aux.getCopiedRole(role, pi);
            OWLAxiom axiom = df.getOWLObjectPropertyAssertionAxiom(rolePi, subject, object);
            manager.addAxiom(output, axiom);
            PipelineLogger.log("  [" + pi.id + "] " + rolePi.getIRI().getShortForm() + "(" + subject.getIRI().getShortForm() + ", " + object.getIRI().getShortForm() + ")");
        }
    }
}