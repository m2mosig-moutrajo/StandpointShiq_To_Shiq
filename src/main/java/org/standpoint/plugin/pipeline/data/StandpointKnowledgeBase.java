package org.standpoint.plugin.pipeline.data;

import org.semanticweb.owlapi.model.OWLOntology;
import org.standpoint.plugin.model.ModalPlaceholder;
import org.standpoint.plugin.model.Sharpening;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StandpointKnowledgeBase {

    public final List<Sharpening> sharpening;
    public final OWLOntology sourceOntology;
    public Map<String, NormalisedAxiom> owlMap;
    public Map<String, String> canonicalKey;

    public StandpointKnowledgeBase(OWLOntology sourceOntology, List<Sharpening> sharpening) {
        this.sharpening = sharpening;
        this.sourceOntology = sourceOntology;
        this.owlMap         = null;
    }
}