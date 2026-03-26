package org.standpoint.plugin.loader;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.standpoint.plugin.parser.PlaceholderSubstituter;
import org.standpoint.plugin.translation.SharpeningStatement;

import java.io.File;
import java.util.*;

public class OntologyLoader {

    private static final String STANDPOINT_LABEL_PROP_NAME = "standpointLabel";

    public static class AxiomWithLabel {
        public final OWLAxiom axiom;
        public final List<String> standpointLabels;
        public final PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType axiomType;

        public AxiomWithLabel(OWLAxiom axiom, List<String> standpointLabels,
                              PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType axiomType) {
            this.axiom = axiom;
            this.standpointLabels = standpointLabels;
            this.axiomType = axiomType;
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
            if (!labels.isEmpty()) result.add(new AxiomWithLabel(axiom, labels,
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION));
        }

        // Assertion axioms
        for (OWLClassAssertionAxiom axiom : ontology.getAxioms(AxiomType.CLASS_ASSERTION)) {
            List<String> labels = readAllStandpointLabels(axiom, standpointLabelProp);
            if (!labels.isEmpty()) result.add(new AxiomWithLabel(axiom, labels,
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_ASSERTION));
        }

        // Role inclusion axioms
        for (OWLSubObjectPropertyOfAxiom axiom : ontology.getAxioms(AxiomType.SUB_OBJECT_PROPERTY)) {
            List<String> labels = readAllStandpointLabels(axiom, standpointLabelProp);
            if (!labels.isEmpty()) result.add(new AxiomWithLabel(axiom, labels,
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.ROLE_INCLUSION));
        }

        // Role assertion axioms
        for (OWLObjectPropertyAssertionAxiom axiom : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION)) {
            List<String> labels = readAllStandpointLabels(axiom, standpointLabelProp);
            if (!labels.isEmpty()) result.add(new AxiomWithLabel(axiom, labels,
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.ROLE_ASSERTION));
        }

        // Transitivity axioms
        for (OWLTransitiveObjectPropertyAxiom axiom :
                ontology.getAxioms(AxiomType.TRANSITIVE_OBJECT_PROPERTY)) {
            // Get the property from the axiom
            OWLObjectProperty property = axiom.getProperty().asOWLObjectProperty();

            // Read standpointLabel from the property entity annotations
            List<String> labels = new ArrayList<>();
            for (OWLAnnotation ann : EntitySearcher.getAnnotations(property, ontology, standpointLabelProp)) {
                String val = ann.getValue().asLiteral()
                        .transform(l -> l.getLiteral()).orNull();
                if (val != null) labels.add(val);
            }

            if (!labels.isEmpty()) result.add(new AxiomWithLabel(axiom, labels,
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.ROLE_TRANSITIVITY));
        }

        System.out.println("Loaded " + result.size() + " axiom(s) with standpointLabel");
        return result;
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

    public static List<SharpeningStatement> loadSharpenings(OWLOntology ontology) {
        List<SharpeningStatement> sharpenings = new ArrayList<>();

        OWLAnnotationProperty sharpeningProp = ontology
                .getAnnotationPropertiesInSignature()
                .stream()
                .filter(p -> p.getIRI().getShortForm().equals("standpointSharpening"))
                .findFirst()
                .orElse(null);

        if (sharpeningProp == null) return sharpenings;

        for (OWLAnnotation ann : ontology.getAnnotations()) {
            if (ann.getProperty().equals(sharpeningProp)) {
                String val = ann.getValue().asLiteral()
                        .transform(l -> l.getLiteral()).orNull();
                if (val != null) sharpenings.add(SharpeningStatement.parse(val.trim()));
            }
        }

        return sharpenings;
    }
}