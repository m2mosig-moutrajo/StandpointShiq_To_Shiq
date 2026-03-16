package org.standpoint.plugin.normalisation;

import org.semanticweb.owlapi.model.*;

import java.util.HashSet;
import java.util.Set;

public class Normaliser {

    private final OWLDataFactory df;

    public Normaliser(OWLDataFactory df) {
        this.df = df;
    }

    public OWLSubClassOfAxiom normalise(OWLSubClassOfAxiom axiom) {
        OWLClassExpression C = axiom.getSubClass();
        OWLClassExpression D = axiom.getSuperClass();

        if (C.isOWLThing()) {
            return df.getOWLSubClassOfAxiom(df.getOWLThing(), D.getNNF());
        }

        OWLClassExpression notC_or_D = df.getOWLObjectUnionOf(df.getOWLObjectComplementOf(C), D);

        return df.getOWLSubClassOfAxiom(df.getOWLThing(), notC_or_D.getNNF());
    }

    public Set<OWLSubClassOfAxiom> normaliseAll(Set<OWLSubClassOfAxiom> axioms) {
        Set<OWLSubClassOfAxiom> result = new HashSet<>();
        for (OWLSubClassOfAxiom axiom : axioms) {
            result.add(normalise(axiom));
        }
        return result;
    }
}