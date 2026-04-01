package org.standpoint.plugin.loader;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.standpoint.plugin.parser.FormulaParser;
import org.standpoint.plugin.parser.PlaceholderSubstituter;
import org.standpoint.plugin.translation.SharpeningStatement;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.StringReader;
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
    // Load standpointFormula annotations from ontology level
    public static List<FormulaParser.ParsedFormula> loadFormulas(OWLOntology ontology) {
        List<FormulaParser.ParsedFormula> formulas = new ArrayList<>();

        OWLAnnotationProperty formulaProp = ontology
                .getAnnotationPropertiesInSignature()
                .stream()
                .filter(p -> p.getIRI().getShortForm().equals("standpointFormula"))
                .findFirst()
                .orElse(null);

        if (formulaProp == null) return formulas;

        for (OWLAnnotation ann : ontology.getAnnotations()) {
            if (ann.getProperty().equals(formulaProp)) {
                String val = ann.getValue().asLiteral()
                        .transform(l -> l.getLiteral()).orNull();
                if (val != null) formulas.add(FormulaParser.parse(val.trim()));
            }
        }

        System.out.println("Loaded " + formulas.size() + " formula(s)");
        return formulas;
    }
    // Load standpointLabel annotations from axioms — returns id → modal content map
    public static Map<String, AxiomWithLabel> loadAxiomLabels(OWLOntology ontology) {
        Map<String, AxiomWithLabel> axiomMap = new LinkedHashMap<>();

        OWLAnnotationProperty labelProp = ontology
                .getAnnotationPropertiesInSignature()
                .stream()
                .filter(p -> p.getIRI().getShortForm().equals("standpointLabel"))
                .findFirst()
                .orElse(null);

        if (labelProp == null) return axiomMap;

        // GCI axioms
        for (OWLSubClassOfAxiom axiom : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
            extractAxiomLabel(axiom, labelProp,
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION,
                    axiomMap);
        }

        // Assertion axioms
        for (OWLClassAssertionAxiom axiom : ontology.getAxioms(AxiomType.CLASS_ASSERTION)) {
            extractAxiomLabel(axiom, labelProp,
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_ASSERTION,
                    axiomMap);
        }

        // Role inclusion axioms
        for (OWLSubObjectPropertyOfAxiom axiom :
                ontology.getAxioms(AxiomType.SUB_OBJECT_PROPERTY)) {
            extractAxiomLabel(axiom, labelProp,
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.ROLE_INCLUSION,
                    axiomMap);
        }

        // Role assertion axioms
        for (OWLObjectPropertyAssertionAxiom axiom :
                ontology.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION)) {
            extractAxiomLabel(axiom, labelProp,
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.ROLE_ASSERTION,
                    axiomMap);
        }

        // Transitivity axioms
        for (OWLTransitiveObjectPropertyAxiom axiom :
                ontology.getAxioms(AxiomType.TRANSITIVE_OBJECT_PROPERTY)) {
            OWLObjectProperty property = axiom.getProperty().asOWLObjectProperty();
            List<String> labels = new ArrayList<>();
            for (OWLAnnotationAssertionAxiom annAxiom :
                    ontology.getAnnotationAssertionAxioms(property.getIRI())) {
                if (annAxiom.getProperty().getIRI().getShortForm()
                        .equals("standpointLabel")) {
                    String val = annAxiom.getValue().asLiteral()
                            .transform(l -> l.getLiteral()).orNull();
                    if (val != null) labels.add(val);
                }
            }
            for (String label : labels) {
                String id = extractId(label);
                if (id != null) axiomMap.put(id, new AxiomWithLabel(axiom,
                        Collections.singletonList(label),
                        PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.ROLE_TRANSITIVITY));
            }
        }

        System.out.println("Loaded " + axiomMap.size() + " axiom label(s)");
        return axiomMap;
    }

    private static void extractAxiomLabel(OWLAxiom axiom,
                                          OWLAnnotationProperty labelProp,
                                          PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType axiomType,
                                          Map<String, AxiomWithLabel> axiomMap) {
        for (OWLAnnotation ann : axiom.getAnnotations()) {
            if (ann.getProperty().equals(labelProp)) {
                String val = ann.getValue().asLiteral()
                        .transform(l -> l.getLiteral()).orNull();
                if (val != null) {
                    String id = extractId(val.trim());
                    if (id != null) axiomMap.put(id,
                            new AxiomWithLabel(axiom,
                                    Collections.singletonList(val.trim()), axiomType));
                }
            }
        }
    }

    // Extracts id from <modal id="F1">...</modal>
    private static String extractId(String xml) {
        try {
            String wrapped = "<root>" + xml.trim() + "</root>";
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(wrapped)));
            Node modal = doc.getDocumentElement().getFirstChild();
            if (modal == null) return null;
            Node idAttr = modal.getAttributes().getNamedItem("id");
            return idAttr != null ? idAttr.getNodeValue() : null;
        } catch (Exception e) {
            return null;
        }
    }
}