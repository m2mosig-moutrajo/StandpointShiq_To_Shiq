package org.standpoint.plugin.loader;

import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.model.StandpointAxiomType;
import org.standpoint.plugin.model.Sharpening;
import org.standpoint.plugin.util.PipelineLogger;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;

public class OntologyLoader {

    private static final String STANDPOINT_AXIOM_PROP_NAME = "standpointAxiom";
    private static final String STANDPOINT_SHARPENING_PROP_NAME = "standpointSharpening";
    private static final String STANDPOINT_FORMULA_PROP_NAME = "standpointFormula";

    public static class AxiomWithLabel {
        public final OWLAxiom axiom;
        public final List<String> standpointLabels;
        public final StandpointAxiomType axiomType;

        public AxiomWithLabel(OWLAxiom axiom, List<String> standpointLabels,
                              StandpointAxiomType axiomType) {
            this.axiom = axiom;
            this.standpointLabels = standpointLabels;
            this.axiomType = axiomType;
        }
    }


    public static List<Sharpening> loadSharpenings(OWLOntology ontology) {
        List<Sharpening> sharpenings = new ArrayList<>();

        OWLAnnotationProperty sharpeningProp = ontology
                .getAnnotationPropertiesInSignature()
                .stream()
                .filter(p -> p.getIRI().getShortForm().equals(STANDPOINT_SHARPENING_PROP_NAME))
                .findFirst()
                .orElse(null);

        if (sharpeningProp == null) return sharpenings;

        for (OWLAnnotation ann : ontology.getAnnotations()) {
            if (ann.getProperty().equals(sharpeningProp)) {
                String val = ann.getValue().asLiteral()
                        .transform(l -> l.getLiteral()).orNull();
                if (val != null) sharpenings.add(Sharpening.parse(val.trim()));
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
                .filter(p -> p.getIRI().getShortForm().equals(STANDPOINT_FORMULA_PROP_NAME))
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

        PipelineLogger.log("Loaded " + formulas.size() + " formula(s)");
        return formulas;
    }
    // Load standpointLabel annotations from axioms — returns id → modal content map
    public static Map<String, AxiomWithLabel> loadAxiomLabels(OWLOntology ontology) {
        Map<String, AxiomWithLabel> axiomMap = new LinkedHashMap<>();

        OWLAnnotationProperty labelProp = ontology
                .getAnnotationPropertiesInSignature()
                .stream()
                .filter(p -> p.getIRI().getShortForm().equals(STANDPOINT_AXIOM_PROP_NAME))
                .findFirst()
                .orElse(null);

        if (labelProp == null) return axiomMap;

        // EquivalentClasses axioms
        for (OWLEquivalentClassesAxiom axiom :
                ontology.getAxioms(AxiomType.EQUIVALENT_CLASSES)) {
            extractAxiomLabel(axiom, labelProp,
                    StandpointAxiomType.CONCEPT_INCLUSION,
                    axiomMap);
        }

        // GCI axioms
        for (OWLSubClassOfAxiom axiom : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
            extractAxiomLabel(axiom, labelProp,
                    StandpointAxiomType.CONCEPT_INCLUSION,
                    axiomMap);
        }

        // Assertion axioms
        for (OWLClassAssertionAxiom axiom : ontology.getAxioms(AxiomType.CLASS_ASSERTION)) {
            extractAxiomLabel(axiom, labelProp,
                    StandpointAxiomType.CONCEPT_ASSERTION,
                    axiomMap);
        }

        // Role inclusion axioms
        for (OWLSubObjectPropertyOfAxiom axiom :
                ontology.getAxioms(AxiomType.SUB_OBJECT_PROPERTY)) {
            extractAxiomLabel(axiom, labelProp,
                    StandpointAxiomType.ROLE_INCLUSION,
                    axiomMap);
        }

        // Role assertion axioms
        for (OWLObjectPropertyAssertionAxiom axiom :
                ontology.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION)) {
            extractAxiomLabel(axiom, labelProp,
                    StandpointAxiomType.ROLE_ASSERTION,
                    axiomMap);
        }

        // Transitivity axioms
        for (OWLTransitiveObjectPropertyAxiom axiom :
                ontology.getAxioms(AxiomType.TRANSITIVE_OBJECT_PROPERTY)) {

            // Approach 1 — annotation directly on axiom (programmatic / test approach)
            extractAxiomLabel(axiom, labelProp,
                    StandpointAxiomType.ROLE_TRANSITIVITY,
                    axiomMap);

            // Approach 2 — annotation on property itself (Protégé UI approach)
            OWLObjectProperty property = axiom.getProperty().asOWLObjectProperty();
            for (OWLAnnotationAssertionAxiom annAxiom :
                    ontology.getAnnotationAssertionAxioms(property.getIRI())) {
                if (annAxiom.getProperty().equals(labelProp)) {
                    String val = annAxiom.getValue().asLiteral()
                            .transform(l -> l.getLiteral()).orNull();
                    if (val != null) {
                        String id = extractId(val.trim());
                        if (id != null && !axiomMap.containsKey(id)) {
                            axiomMap.put(id, new AxiomWithLabel(axiom,
                                    Collections.singletonList(val.trim()),
                                    StandpointAxiomType.ROLE_TRANSITIVITY));
                        }
                    }
                }
            }
        }

        PipelineLogger.log("Loaded " + axiomMap.size() + " axiom label(s)");
        return axiomMap;
    }

    private static void extractAxiomLabel(OWLAxiom axiom,
                                          OWLAnnotationProperty labelProp,
                                          StandpointAxiomType axiomType,
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
            Node axiom = doc.getDocumentElement().getFirstChild();
            if (axiom == null) return null;
            Node idAttr = axiom.getAttributes().getNamedItem("id");
            return idAttr != null ? idAttr.getNodeValue() : null;
        } catch (Exception e) {
            return null;
        }
    }
}