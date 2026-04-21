package org.standpoint.plugin.pipeline;

import org.semanticweb.owlapi.model.OWLOntology;
import org.standpoint.plugin.model.ModalPlaceholder;
import org.standpoint.plugin.model.Sharpening;

import java.util.List;
import java.util.Map;

public class NormalisedKnowledgeBase {

    public final Map<String, ModalPlaceholder> manchesterMap;
    public final List<Sharpening> sharpenings;
    public final OWLOntology sourceOntology;
    // Set after ManchesterToOWLConverter runs — null until then
    public Map<String, NormalisedAxiom> owlMap;

    public NormalisedKnowledgeBase(Map<String, ModalPlaceholder> manchesterMap,
                                   List<Sharpening> sharpenings,
                                   OWLOntology sourceOntology) {
        this.manchesterMap = manchesterMap;
        this.sharpenings   = sharpenings;
        this.sourceOntology = sourceOntology;
        this.owlMap        = null;
    }

    public boolean isEmpty() {
        return manchesterMap.isEmpty() && sharpenings.isEmpty();
    }
}