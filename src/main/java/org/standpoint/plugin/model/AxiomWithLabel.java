package org.standpoint.plugin.model;

import org.semanticweb.owlapi.model.OWLAxiom;

public class AxiomWithLabel {
    public final OWLAxiom axiom;
    public final String standpointLabel;
    public final StandpointAxiomType standpointAxiomType;

    public AxiomWithLabel(OWLAxiom axiom, String standpointLabel, StandpointAxiomType standpointAxiomType) {
        this.axiom = axiom;
        this.standpointLabel = standpointLabel;
        this.standpointAxiomType = standpointAxiomType;
    }
}
