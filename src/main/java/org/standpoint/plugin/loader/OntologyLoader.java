package org.standpoint.plugin.loader;

import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.parser.PlaceholderUtil;

import java.io.File;
import java.util.*;

public class OntologyLoader {

    private static final String STANDPOINT_LABEL_PROP_NAME = "standpointLabel";

    public static class AxiomWithLabel {
        public final OWLAxiom axiom;
        public final List<String> standpointLabels;

        public AxiomWithLabel(OWLAxiom axiom, List<String> standpointLabels) {
            this.axiom = axiom;
            this.standpointLabels = standpointLabels;
        }
    }

    public static List<AxiomWithLabel> loadAxiomsWithLabels(OWLOntology ontology) {
        OWLAnnotationProperty standpointLabelProp = ontology
                .getAnnotationPropertiesInSignature()
                .stream()
                .filter(p -> p.getIRI().getShortForm().equals(STANDPOINT_LABEL_PROP_NAME))
                .findFirst()
                .orElse(null);

        List<AxiomWithLabel> result = new ArrayList<>();

        // GCI axioms
        for (OWLSubClassOfAxiom axiom : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
            List<String> labels = readAllStandpointLabels(axiom, standpointLabelProp);
            if (!labels.isEmpty()) result.add(new AxiomWithLabel(axiom, labels));
        }

        // Assertion axioms
        for (OWLClassAssertionAxiom axiom : ontology.getAxioms(AxiomType.CLASS_ASSERTION)) {
            List<String> labels = readAllStandpointLabels(axiom, standpointLabelProp);
            if (!labels.isEmpty()) result.add(new AxiomWithLabel(axiom, labels));
        }

        System.out.println("Loaded " + result.size() + " axiom(s) with standpointLabel");
        return result;
    }

    public static List<AxiomWithLabel> loadAxiomsWithLabels(File owlFile) throws Exception {
        org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(owlFile);
        return loadAxiomsWithLabels(ontology);
    }

    private static List<String> readAllStandpointLabels(OWLAxiom axiom,
                                                        OWLAnnotationProperty standpointLabelProp) {
        List<String> labels = new ArrayList<>();
        if (standpointLabelProp == null) return labels;
        for (OWLAnnotation ann : axiom.getAnnotations()) {
            if (ann.getProperty().equals(standpointLabelProp)) {
                String val = ann.getValue().asLiteral()
                        .transform(l -> l.getLiteral()).orNull();
                if (val != null) labels.add(val);
            }
        }
        return labels;
    }
}