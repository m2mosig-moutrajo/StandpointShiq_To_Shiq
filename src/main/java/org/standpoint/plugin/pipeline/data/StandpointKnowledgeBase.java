package org.standpoint.plugin.pipeline.data;

import org.semanticweb.owlapi.model.OWLOntology;
import org.standpoint.plugin.model.ModalPlaceholder;
import org.standpoint.plugin.model.Sharpening;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StandpointKnowledgeBase {

    public final Map<String, ModalPlaceholder> manchesterMap;
    public final List<Sharpening> sharpenings;
    public final OWLOntology sourceOntology;
    public Map<String, NormalisedAxiom> owlMap;
    public Map<String, String> canonicalKey;

    public StandpointKnowledgeBase(OWLOntology sourceOntology, List<Sharpening> sharpenings) {
        this.manchesterMap  = new LinkedHashMap<>();
        this.sharpenings    = sharpenings;
        this.sourceOntology = sourceOntology;
        this.owlMap         = null;
    }

    public boolean isEmpty() {
        return manchesterMap.isEmpty() && sharpenings.isEmpty();
    }
}