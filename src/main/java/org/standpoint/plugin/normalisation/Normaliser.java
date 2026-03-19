package org.standpoint.plugin.normalisation;

import org.semanticweb.owlapi.model.*;

import java.util.HashSet;
import java.util.Set;

public class Normaliser {

    private final OWLDataFactory df;

    public Normaliser(OWLDataFactory df) {
        this.df = df;
    }

    // Converts C ⊑ D → ⊤ ⊑ NNF(¬C ⊔ D)
    public OWLSubClassOfAxiom normaliseGCI(OWLSubClassOfAxiom gciAxiom) {
        OWLClassExpression subClass   = gciAxiom.getSubClass();
        OWLClassExpression superClass = gciAxiom.getSuperClass();

        if (subClass.isOWLThing()) {
            return df.getOWLSubClassOfAxiom(df.getOWLThing(), superClass.getNNF());
        }

        OWLClassExpression negSubClass_or_superClass = df.getOWLObjectUnionOf(
                df.getOWLObjectComplementOf(subClass), superClass);

        return df.getOWLSubClassOfAxiom(
                df.getOWLThing(),
                negSubClass_or_superClass.getNNF());
    }

    public Set<OWLSubClassOfAxiom> normaliseAllGCIs(Set<OWLSubClassOfAxiom> gciAxioms) {
        Set<OWLSubClassOfAxiom> normalisedAxioms = new HashSet<>();
        for (OWLSubClassOfAxiom gciAxiom : gciAxioms) {
            normalisedAxioms.add(normaliseGCI(gciAxiom));
        }
        return normalisedAxioms;
    }
}