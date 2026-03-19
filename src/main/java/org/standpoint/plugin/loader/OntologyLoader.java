package org.standpoint.plugin.loader;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxObjectRenderer;
import org.standpoint.plugin.normalisation.StandpointNormaliser;
import org.standpoint.plugin.parser.PlaceholderUtil;

import java.io.File;
import java.io.StringWriter;
import java.util.*;

public class OntologyLoader {

    private static final String STANDPOINT_LABEL_PROP_NAME = "standpointLabel";

    public static class AxiomWithLabel {
        public final OWLSubClassOfAxiom gciAxiom;
        public final List<String> standpointLabels;
        public AxiomWithLabel(OWLSubClassOfAxiom gciAxiom, List<String> standpointLabels) {
            this.gciAxiom = gciAxiom;
            this.standpointLabels = standpointLabels;
        }
    }

    public static List<AxiomWithLabel> loadAndValidateGCIs(File owlFile) throws Exception {

        OWLOntologyManager ontologyManager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = ontologyManager.loadOntologyFromOntologyDocument(owlFile);
        OWLDataFactory df = ontologyManager.getOWLDataFactory();

        // Check no class/property name clashes with placeholder naming scheme
        for (OWLClass cls : ontology.getClassesInSignature()) {
            String shortName = cls.getIRI().getShortForm();
            if (PlaceholderUtil.isPlaceholder(shortName)) {
                System.out.println("✗ Conflict — class name reserved for placeholders: " + shortName);
                return null;
            }
        }
        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
            String shortName = prop.getIRI().getShortForm();
            if (PlaceholderUtil.isPlaceholder(shortName)) {
                System.out.println("✗ Conflict — property name reserved for placeholders: " + shortName);
                return null;
            }
        }
        System.out.println("✓ No placeholder conflicts\n");

        OWLAnnotationProperty standpointLabelProp = findOrCreateStandpointLabelProp(
                ontology, ontologyManager, df);

        Set<OWLSubClassOfAxiom> gciAxioms = ontology.getAxioms(AxiomType.SUBCLASS_OF);
        System.out.println("Found " + gciAxioms.size() + " GCI (SubClassOf) axioms\n");

        boolean addedDefaultLabels = false;
        List<AxiomWithLabel> validatedGCIs = new ArrayList<>();

        Set<OWLSubClassOfAxiom> gciAxiomsSnapshot = new HashSet<>(ontology.getAxioms(AxiomType.SUBCLASS_OF));
        for (OWLSubClassOfAxiom originalGciAxiom : gciAxiomsSnapshot) {
            OWLSubClassOfAxiom gciAxiom = findCurrentAxiom(ontology, originalGciAxiom);

            String gciManchester = renderGCIToManchester(gciAxiom);
            System.out.println("=== GCI AXIOM ===");
            System.out.println(gciManchester);

            List<String> existingLabels = readAllStandpointLabels(gciAxiom, standpointLabelProp);
            List<String> validatedLabels = new ArrayList<>();

            if (existingLabels.isEmpty()) {
                // No standpointLabel — wrap entire GCI in default box[*]
                String defaultLabel = "<modal op=\"box\" standpoint=\"*\">"
                        + gciManchester + "</modal>";
                replaceAllStandpointLabels(gciAxiom,
                        Collections.singletonList(defaultLabel),
                        standpointLabelProp, ontologyManager, ontology, df);
                addedDefaultLabels = true;
                validatedLabels.add(defaultLabel);

            } else {
                // Check if any labels need wrapping — process all in one shot
                List<String> processedLabels = new ArrayList<>();
                boolean needsUpdate = false;

                for (String label : existingLabels) {
                    if (!isFullyWrappedInModal(label)) {
                        processedLabels.add("<modal op=\"box\" standpoint=\"*\">"
                                + label + "</modal>");
                        needsUpdate = true;
                    } else {
                        processedLabels.add(label);
                    }
                }

                // Replace all annotations in one atomic operation
                if (needsUpdate) {
                    replaceAllStandpointLabels(gciAxiom, processedLabels,
                            standpointLabelProp, ontologyManager, ontology, df);
                    addedDefaultLabels = true;
                    gciAxiom = findCurrentAxiom(ontology, gciAxiom);
                    existingLabels = processedLabels;
                }

                // Validate each label
                for (String label : existingLabels) {

                    // Layer 1: replace inner modal tags with parens, validate syntax with OWL API
                    String labelWithParens = label
                            .replaceFirst("<modal[^>]*>", "")
                            .trim();
                    if (labelWithParens.endsWith("</modal>")) {
                        labelWithParens = labelWithParens
                                .substring(0, labelWithParens.lastIndexOf("</modal>")).trim();
                    }
                    labelWithParens = labelWithParens
                            .replaceAll("<modal[^>]*>", "(")
                            .replaceAll("</modal>", ")");

                    int splitIdx = labelWithParens.indexOf("SubClassOf");
                    if (splitIdx == -1) {
                        System.out.println("✗ INVALID — no SubClassOf found in standpointLabel: " + label);
                        return null;
                    }

                    try {
                        StandpointNormaliser syntaxChecker = new StandpointNormaliser(
                                df, ontologyManager, ontology);
                        String checkLHS = labelWithParens.substring(0, splitIdx).trim();
                        String checkRHS = labelWithParens.substring(
                                splitIdx + "SubClassOf".length()).trim();
                        syntaxChecker.parseManchesterExpression(checkLHS);
                        syntaxChecker.parseManchesterExpression(checkRHS);
                        System.out.println("✓ Valid Manchester structure");
                    } catch (Exception e) {
                        System.out.println("✗ INVALID Manchester structure: " + e.getMessage());
                        return null;
                    }

                    // Layer 2: strip all tags, compare structurally with GCI axiom
                    String labelWithoutModals = label.replaceAll("<[^>]+>", "").trim();

                    int subClassOfIdx = labelWithoutModals.indexOf("SubClassOf");
                    if (subClassOfIdx == -1) {
                        System.out.println("✗ MISMATCH — no SubClassOf found after stripping tags");
                        return null;
                    }

                    String labelSubClassStr   = labelWithoutModals.substring(0, subClassOfIdx).trim();
                    String labelSuperClassStr = labelWithoutModals.substring(
                            subClassOfIdx + "SubClassOf".length()).trim();

                    try {
                        StandpointNormaliser validator = new StandpointNormaliser(
                                df, ontologyManager, ontology);

                        OWLClassExpression gciSubClass    = gciAxiom.getSubClass();
                        OWLClassExpression gciSuperClass  = gciAxiom.getSuperClass();
                        OWLClassExpression labelSubClass   = validator.parseManchesterExpression(labelSubClassStr);
                        OWLClassExpression labelSuperClass = validator.parseManchesterExpression(labelSuperClassStr);

                        if (!gciSubClass.equals(labelSubClass) || !gciSuperClass.equals(labelSuperClass)) {
                            System.out.println("✗ MISMATCH — standpointLabel does not match GCI axiom");
                            System.out.println("  Label sub:   " + labelSubClassStr);
                            System.out.println("  Label super: " + labelSuperClassStr);
                            return null;
                        }
                    } catch (Exception e) {
                        System.out.println("✗ PARSE ERROR — " + e.getMessage());
                        return null;
                    }

                    validatedLabels.add(label);
                }
            }

            System.out.println("✓ MATCH (" + validatedLabels.size() + " label(s))");
            System.out.println();
            validatedGCIs.add(new AxiomWithLabel(gciAxiom, validatedLabels));
        }

        // Write back to file only if default labels were added
        if (addedDefaultLabels) {
            ontologyManager.saveOntology(ontology, IRI.create(owlFile.toURI()));
            System.out.println("Ontology saved with default standpoint labels.");
        }

        return validatedGCIs;
    }

    private static OWLAnnotationProperty findOrCreateStandpointLabelProp(
            OWLOntology ontology,
            OWLOntologyManager ontologyManager,
            OWLDataFactory df) throws Exception {

        Optional<OWLAnnotationProperty> existing = ontology
                .getAnnotationPropertiesInSignature()
                .stream()
                .filter(p -> p.getIRI().getShortForm().equals(STANDPOINT_LABEL_PROP_NAME))
                .findFirst();

        if (existing.isPresent()) return existing.get();

        IRI propIRI = IRI.create(
                ontology.getOntologyID().getOntologyIRI().get() + "#standpointLabel");
        OWLAnnotationProperty newProp = df.getOWLAnnotationProperty(propIRI);
        ontologyManager.addAxiom(ontology, df.getOWLDeclarationAxiom(newProp));
        return newProp;
    }

    private static List<String> readAllStandpointLabels(OWLSubClassOfAxiom gciAxiom,
                                                        OWLAnnotationProperty standpointLabelProp) {
        List<String> labels = new ArrayList<>();
        for (OWLAnnotation ann : gciAxiom.getAnnotations()) {
            if (ann.getProperty().equals(standpointLabelProp)) {
                String val = ann.getValue().asLiteral()
                        .transform(l -> l.getLiteral()).orNull();
                if (val != null) labels.add(val);
            }
        }
        return labels;
    }

    private static void replaceAllStandpointLabels(OWLSubClassOfAxiom gciAxiom,
                                                   List<String> newLabels,
                                                   OWLAnnotationProperty standpointLabelProp,
                                                   OWLOntologyManager ontologyManager,
                                                   OWLOntology ontology,
                                                   OWLDataFactory df) {
        // Keep all non-standpointLabel annotations
        Set<OWLAnnotation> annotationsToKeep = new HashSet<>();
        for (OWLAnnotation ann : gciAxiom.getAnnotations()) {
            if (!ann.getProperty().equals(standpointLabelProp)) {
                annotationsToKeep.add(ann);
            }
        }

        // Add all new standpointLabel annotations
        for (String label : newLabels) {
            annotationsToKeep.add(df.getOWLAnnotation(
                    standpointLabelProp, df.getOWLLiteral(label)));
        }

        OWLAxiom annotatedAxiom = gciAxiom.getAnnotatedAxiom(annotationsToKeep);
        ontologyManager.removeAxiom(ontology, gciAxiom);
        ontologyManager.addAxiom(ontology, annotatedAxiom);
    }

    private static OWLSubClassOfAxiom findCurrentAxiom(OWLOntology ontology,
                                                       OWLSubClassOfAxiom originalAxiom) {
        for (OWLSubClassOfAxiom ax : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
            if (ax.getSubClass().equals(originalAxiom.getSubClass())
                    && ax.getSuperClass().equals(originalAxiom.getSuperClass())) {
                return ax;
            }
        }
        return originalAxiom;
    }

    private static String renderGCIToManchester(OWLSubClassOfAxiom gciAxiom) {
        StringWriter sw = new StringWriter();
        ManchesterOWLSyntaxObjectRenderer renderer =
                new ManchesterOWLSyntaxObjectRenderer(sw, new SimpleShortFormProvider());
        gciAxiom.accept(renderer);
        return sw.toString();
    }

    private static boolean isFullyWrappedInModal(String label) {
        label = label.trim();
        if (!label.startsWith("<modal")) return false;
        int depth = 0;
        int i = 0;
        while (i < label.length()) {
            if (label.startsWith("<modal", i)) {
                depth++;
                i += 6;
            } else if (label.startsWith("</modal>", i)) {
                depth--;
                if (depth == 0) {
                    return i + "</modal>".length() == label.length();
                }
                i += 8;
            } else {
                i++;
            }
        }
        return false;
    }

    public static List<AxiomWithLabel> loadGCIs(File owlFile) throws Exception {

        OWLOntologyManager ontologyManager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = ontologyManager.loadOntologyFromOntologyDocument(owlFile);

        OWLAnnotationProperty standpointLabelProp = ontology
                .getAnnotationPropertiesInSignature()
                .stream()
                .filter(p -> p.getIRI().getShortForm().equals(STANDPOINT_LABEL_PROP_NAME))
                .findFirst()
                .orElse(null);

        List<AxiomWithLabel> result = new ArrayList<>();

        for (OWLSubClassOfAxiom gciAxiom : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
            List<String> labels = readAllStandpointLabels(gciAxiom, standpointLabelProp);
            if (!labels.isEmpty()) {
                result.add(new AxiomWithLabel(gciAxiom, labels));
            }
        }

        System.out.println("Loaded " + result.size() + " GCI axiom(s) with standpointLabel");
        return result;
    }
}