package org.standpoint.plugin.model;

import org.semanticweb.owlapi.model.OWLAxiom;

public class AxiomWithLabel {
    public final OWLAxiom axiom;
    public final String standpointLabel;
    public final StandpointAxiomType axiomType;

    public AxiomWithLabel(OWLAxiom axiom, String standpointLabel, StandpointAxiomType axiomType) {
        this.axiom = axiom;
        this.standpointLabel = standpointLabel;
        this.axiomType = axiomType;
    }
}
