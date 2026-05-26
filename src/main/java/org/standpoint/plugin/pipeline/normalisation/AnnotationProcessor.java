package org.standpoint.plugin.pipeline.normalisation;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.loader.OntologyLoader;
import org.standpoint.plugin.model.*;
import org.standpoint.plugin.normaliser.ManchesterNormaliser;
import org.standpoint.plugin.normaliser.ModalExpressionDecomposer;
import org.standpoint.plugin.pipeline.data.NormalisedAxiom;
import org.standpoint.plugin.pipeline.data.StandpointKnowledgeBase;
import org.standpoint.plugin.util.PipelineLogger;
import org.standpoint.plugin.util.PlaceholderCounter;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;
import java.util.stream.Collectors;

import static org.standpoint.plugin.model.StandpointAxiomType.CONCEPT_ASSERTION;

public class AnnotationProcessor {

    private final OWLOntology ontology;
    private final PlaceholderCounter placeholderCounter;

    private OWLOntologyManager helperManager;
    private OWLDataFactory helperDf;
    private OWLOntology helperOntology;
    private ManchesterNormaliser normaliser;
    private String sourceBase;

    public AnnotationProcessor(OWLOntology ontology) {
        this.ontology = ontology;
        this.placeholderCounter = new PlaceholderCounter();
    }

    public StandpointKnowledgeBase run() throws Exception {

        OntologyLoader ontologyLoader = new OntologyLoader();
        Map<String, AxiomWithLabel> axiomLabelMap = ontologyLoader.loadAxiomLabels(ontology);
        List<ParsedFormula> formulas = ontologyLoader.loadFormulas(ontology);
        List<Sharpening> loadedSharpening = ontologyLoader.loadSharpening(ontology);

        logLoadedSharpening(loadedSharpening);
        logOriginalFormulas(formulas, axiomLabelMap);

        // TODO: we continue even without these
        if (formulas.isEmpty() && loadedSharpening.isEmpty()) return null;

        List<Sharpening> sharpening = new ArrayList<>();

        List<AxiomWithLabel> expandedAxioms = expandFormulas(formulas, axiomLabelMap, sharpening);
        injectUnreferencedAxioms(expandedAxioms, axiomLabelMap, formulas);
        logExpandedFormulas(expandedAxioms);

        buildHelperOntology();

        Map<String, ModalPlaceholder> placeholderMap = substitute(expandedAxioms);
        Map<String, NormalisedAxiom> owlMap = convertToOwl(placeholderMap);

        injectUnlabelledAxioms(owlMap, axiomLabelMap);

        logOwlMap(owlMap, "owlMap before expand to SubClassOf");

        expandToSubClassOf(owlMap);

        logOwlMap(owlMap, "owlMap after expand to SubClassOf");

        applyGCINormalisation(owlMap);
        applyNegatedGCI(owlMap);
        applyConceptAssertions(owlMap);
        applyNegatedRoleInclusion(owlMap);
        applyNegatedRoleAssertion(owlMap);
        applyNegatedTransitivity(owlMap);
        applyNegatedSharpenings(loadedSharpening, sharpening, owlMap);
        applyZeroSharpenings(sharpening, loadedSharpening, owlMap);
        collectNormalSharpenings(loadedSharpening, sharpening);

        sharpening.removeIf(s -> s.lhsStandpoints.contains("0"));
        PipelineLogger.log("Sharpening after trivial removal: " + sharpening);

        StandpointKnowledgeBase kb = new StandpointKnowledgeBase(ontology, sharpening);
        kb.owlMap = owlMap;
        return kb;
    }

    private void applyGCINormalisation(Map<String, NormalisedAxiom> owlMap) {

        PipelineLogger.log("\n=== RULE (11): GCI NORMALISATION ===\n");

        List<String> toUpdate = new ArrayList<>();

        for (Map.Entry<String, NormalisedAxiom> e : owlMap.entrySet()) {
            NormalisedAxiom na = e.getValue();

            // Rule (11) fires on:
            // - root entries only
            // - not negated
            // - CONCEPT_INCLUSION type
            // - owlAxiom is OWLSubClassOfAxiom
            if (!na.isRoot) continue;
            if (na.isNegatedInner) continue;
            if (na.axiomType != StandpointAxiomType.CONCEPT_INCLUSION) continue;
            if (!(na.owlAxiom instanceof OWLSubClassOfAxiom)) continue;

            toUpdate.add(e.getKey());
        }

        for (String key : toUpdate) {
            NormalisedAxiom na = owlMap.get(key);
            OWLSubClassOfAxiom gci = (OWLSubClassOfAxiom) na.owlAxiom;

            // Rule (11): □_s[C ⊑ D] → □_s[⊤ ⊑ NNF(¬C ⊔ D)]
            OWLClassExpression notC = helperDf.getOWLObjectComplementOf(gci.getSubClass());
            OWLClassExpression union = helperDf.getOWLObjectUnionOf(notC, gci.getSuperClass());
            OWLAxiom normalised = helperDf.getOWLSubClassOfAxiom(
                    helperDf.getOWLThing(), union.getNNF());

            PipelineLogger.log("  Rule (11) [" + key + "]: "
                    + gci + "\n    → " + normalised);

            owlMap.put(key, new NormalisedAxiom(
                    na.operator, na.standpoint, na.axiomType,
                    na.isRoot, na.isNegatedInner,
                    normalised, null,
                    na.manchester,
                    extractChildKeysFromAxiom(normalised)));
        }

        PipelineLogger.log("\n  Rule (11) applied to " + toUpdate.size() + " entries.");
    }

    // ─── Rule (3): negated GCI ────────────────────────────────────────────────

    private void applyNegatedGCI(Map<String, NormalisedAxiom> owlMap) {
        PipelineLogger.log("\n=== RULE (3): NEGATED GCI ===\n");

        List<String> toRemove = new ArrayList<>();
        Map<String, NormalisedAxiom> toAdd = new LinkedHashMap<>();

        for (Map.Entry<String, NormalisedAxiom> e : owlMap.entrySet()) {
            NormalisedAxiom na = e.getValue();
            if (!na.isRoot) continue;
            if (!na.isNegatedInner) continue;
            if (na.axiomType != StandpointAxiomType.CONCEPT_INCLUSION) continue;
            if (!(na.owlAxiom instanceof OWLSubClassOfAxiom)) continue;

            OWLSubClassOfAxiom gci = (OWLSubClassOfAxiom) na.owlAxiom;
            OWLClassExpression C = gci.getSubClass();
            OWLClassExpression D = gci.getSuperClass();

            String fcName = placeholderCounter.generate(PlaceholderType.FRESH_CONCEPT);
            String frName = placeholderCounter.generate(PlaceholderType.FRESH_ROLE);
            OWLClass freshC = freshClass(fcName);
            OWLObjectProperty freshR = freshRole(frName);
            registerFreshClass(fcName);
            registerFreshRole(frName);

            // FC ⊑ C  →  Thing ⊑ NNF(¬FC ⊔ C)
            OWLAxiom ax1 = applyRule11(freshC, C);
            // (FC ⊓ D) ⊑ ⊥  →  Thing ⊑ NNF(¬FC ⊔ ¬D)
            OWLAxiom ax2 = applyRule11(
                    helperDf.getOWLObjectIntersectionOf(freshC, D), helperDf.getOWLNothing());
            // Thing ⊑ ∃FR.FC
            OWLAxiom ax3 = helperDf.getOWLSubClassOfAxiom(
                    helperDf.getOWLThing(),
                    helperDf.getOWLObjectSomeValuesFrom(freshR, freshC));

            String k1 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
            String k2 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
            String k3 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
            registerSPn(k1);
            registerSPn(k2);
            registerSPn(k3);
            toAdd.put(k1, owlRootEntry(na.operator, na.standpoint, StandpointAxiomType.CONCEPT_INCLUSION, ax1));
            toAdd.put(k2, owlRootEntry(na.operator, na.standpoint, StandpointAxiomType.CONCEPT_INCLUSION, ax2));
            toAdd.put(k3, owlRootEntry(na.operator, na.standpoint, StandpointAxiomType.CONCEPT_INCLUSION, ax3));

            toRemove.add(e.getKey());

            PipelineLogger.log("Rule (3) on " + e.getKey() + ":");
            PipelineLogger.log("  → " + k1 + ": " + ax1);
            PipelineLogger.log("  → " + k2 + ": " + ax2);
            PipelineLogger.log("  → " + k3 + ": " + ax3);
        }

        toRemove.forEach(owlMap::remove);
        owlMap.putAll(toAdd);

        PipelineLogger.log("\n  Rule (3) applied to " + toRemove.size() + " entries.");
        PipelineLogger.log("\n=== owlMap after applyNegatedGCI ===");
        PipelineLogger.log("owlMap size: " + owlMap.size());
        owlMap.forEach((key, na) -> PipelineLogger.log("  " + key + " → " + na));
    }

    // ─── Rules (4)+(10): concept assertions ──────────────────────────────────

    private void applyConceptAssertions(Map<String, NormalisedAxiom> owlMap) {
        PipelineLogger.log("\n=== RULES (4)+(10): CONCEPT ASSERTIONS ===\n");

        List<String> toUpdate = new ArrayList<>();

        for (Map.Entry<String, NormalisedAxiom> e : owlMap.entrySet()) {
            NormalisedAxiom na = e.getValue();
            if (!na.isRoot) continue;
            if (na.axiomType != StandpointAxiomType.CONCEPT_ASSERTION) continue;
            if (!(na.owlAxiom instanceof OWLClassAssertionAxiom)) continue;
            toUpdate.add(e.getKey());
        }

        for (String key : toUpdate) {
            NormalisedAxiom na = owlMap.get(key);
            OWLClassAssertionAxiom ca = (OWLClassAssertionAxiom) na.owlAxiom;
            OWLClassExpression concept = ca.getClassExpression();
            OWLNamedIndividual ind = (OWLNamedIndividual) ca.getIndividual();

            if (na.isNegatedInner)
                concept = helperDf.getOWLObjectComplementOf(concept);

            OWLClassExpression nnf = concept.getNNF();
            OWLAxiom newAxiom = helperDf.getOWLClassAssertionAxiom(nnf, ind);

            owlMap.put(key, new NormalisedAxiom(
                    na.operator, na.standpoint,
                    StandpointAxiomType.CONCEPT_ASSERTION, true, false,
                    newAxiom, null, newAxiom.toString(),
                    extractChildKeysFromAxiom(newAxiom)));

            PipelineLogger.log("Rule (4)+(10) on " + key + ": " + ca + " → " + newAxiom);
        }

        PipelineLogger.log("\n  Rules (4)+(10) applied to " + toUpdate.size() + " entries.");
        PipelineLogger.log("\n=== owlMap after applyConceptAssertions ===");
        PipelineLogger.log("owlMap size: " + owlMap.size());
        owlMap.forEach((key, na) -> PipelineLogger.log("  " + key + " → " + na));
    }

    // ─── Rule (6): negated role inclusion ────────────────────────────────────

    private void applyNegatedRoleInclusion(Map<String, NormalisedAxiom> owlMap) {
        PipelineLogger.log("\n=== RULE (6): NEGATED ROLE INCLUSION ===\n");

        List<String> toRemove = new ArrayList<>();
        Map<String, NormalisedAxiom> toAdd = new LinkedHashMap<>();

        for (Map.Entry<String, NormalisedAxiom> e : owlMap.entrySet()) {
            NormalisedAxiom na = e.getValue();
            if (!na.isRoot) continue;
            if (!na.isNegatedInner) continue;
            if (na.axiomType != StandpointAxiomType.ROLE_INCLUSION) continue;
            if (!(na.owlAxiom instanceof OWLSubObjectPropertyOfAxiom)) continue;

            OWLSubObjectPropertyOfAxiom ri = (OWLSubObjectPropertyOfAxiom) na.owlAxiom;
            OWLObjectProperty S = (OWLObjectProperty) ri.getSubProperty();
            OWLObjectProperty R = (OWLObjectProperty) ri.getSuperProperty();

            String fcaName = placeholderCounter.generate(PlaceholderType.FRESH_CONCEPT);
            String fcbName = placeholderCounter.generate(PlaceholderType.FRESH_CONCEPT);
            String frName = placeholderCounter.generate(PlaceholderType.FRESH_ROLE);
            OWLClass freshCa = freshClass(fcaName);
            OWLClass freshCb = freshClass(fcbName);
            OWLObjectProperty freshRProp = freshRole(frName);
            registerFreshClass(fcaName);
            registerFreshClass(fcbName);
            registerFreshRole(frName);

            // Thing ⊑ ∃FR.FCa
            OWLAxiom ax1 = helperDf.getOWLSubClassOfAxiom(
                    helperDf.getOWLThing(),
                    helperDf.getOWLObjectSomeValuesFrom(freshRProp, freshCa));
            // (FCa ⊓ ∃R.FCb) ⊑ ⊥  →  Rule (11)
            OWLAxiom ax2 = applyRule11(
                    helperDf.getOWLObjectIntersectionOf(
                            freshCa, helperDf.getOWLObjectSomeValuesFrom(R, freshCb)),
                    helperDf.getOWLNothing());
            // FCa ⊑ ∃S.FCb  →  Rule (11)
            OWLAxiom ax3 = applyRule11(
                    freshCa, helperDf.getOWLObjectSomeValuesFrom(S, freshCb));

            String k1 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
            String k2 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
            String k3 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
            registerSPn(k1);
            registerSPn(k2);
            registerSPn(k3);
            toAdd.put(k1, owlRootEntry(na.operator, na.standpoint, StandpointAxiomType.CONCEPT_INCLUSION, ax1));
            toAdd.put(k2, owlRootEntry(na.operator, na.standpoint, StandpointAxiomType.CONCEPT_INCLUSION, ax2));
            toAdd.put(k3, owlRootEntry(na.operator, na.standpoint, StandpointAxiomType.CONCEPT_INCLUSION, ax3));

            toRemove.add(e.getKey());

            PipelineLogger.log("Rule (6) on " + e.getKey() + " [" + S + " ⊑ " + R + "]:");
            PipelineLogger.log("  → " + k1 + ": " + ax1);
            PipelineLogger.log("  → " + k2 + ": " + ax2);
            PipelineLogger.log("  → " + k3 + ": " + ax3);
        }

        toRemove.forEach(owlMap::remove);
        owlMap.putAll(toAdd);

        PipelineLogger.log("\n  Rule (6) applied to " + toRemove.size() + " entries.");
        PipelineLogger.log("\n=== owlMap after applyNegatedRoleInclusion ===");
        PipelineLogger.log("owlMap size: " + owlMap.size());
        owlMap.forEach((key, na) -> PipelineLogger.log("  " + key + " → " + na));
    }

    // ─── Rule (5): negated role assertion ────────────────────────────────────

    private void applyNegatedRoleAssertion(Map<String, NormalisedAxiom> owlMap) {
        PipelineLogger.log("\n=== RULE (5): NEGATED ROLE ASSERTION ===\n");

        List<String> toRemove = new ArrayList<>();
        Map<String, NormalisedAxiom> toAdd = new LinkedHashMap<>();

        for (Map.Entry<String, NormalisedAxiom> e : owlMap.entrySet()) {
            NormalisedAxiom na = e.getValue();
            if (!na.isRoot) continue;
            if (!na.isNegatedInner) continue;
            if (na.axiomType != StandpointAxiomType.ROLE_ASSERTION) continue;
            if (!(na.owlAxiom instanceof OWLObjectPropertyAssertionAxiom)) continue;

            OWLObjectPropertyAssertionAxiom ra = (OWLObjectPropertyAssertionAxiom) na.owlAxiom;
            OWLObjectProperty role = (OWLObjectProperty) ra.getProperty();
            OWLNamedIndividual a = (OWLNamedIndividual) ra.getSubject();
            OWLNamedIndividual b = (OWLNamedIndividual) ra.getObject();

            String fcaName = placeholderCounter.generate(PlaceholderType.FRESH_CONCEPT);
            String fcbName = placeholderCounter.generate(PlaceholderType.FRESH_CONCEPT);
            OWLClass freshCa = freshClass(fcaName);
            OWLClass freshCb = freshClass(fcbName);
            registerFreshClass(fcaName);
            registerFreshClass(fcbName);

            OWLAxiom ax1 = helperDf.getOWLClassAssertionAxiom(freshCa, a);
            OWLAxiom ax2 = helperDf.getOWLClassAssertionAxiom(freshCb, b);
            OWLAxiom ax3 = applyRule11(
                    helperDf.getOWLObjectIntersectionOf(
                            freshCa, helperDf.getOWLObjectSomeValuesFrom(role, freshCb)),
                    helperDf.getOWLNothing());

            String k1 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
            String k2 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
            String k3 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
            registerSPn(k1);
            registerSPn(k2);
            registerSPn(k3);
            toAdd.put(k1, owlRootEntry(na.operator, na.standpoint, StandpointAxiomType.CONCEPT_ASSERTION, ax1));
            toAdd.put(k2, owlRootEntry(na.operator, na.standpoint, StandpointAxiomType.CONCEPT_ASSERTION, ax2));
            toAdd.put(k3, owlRootEntry(na.operator, na.standpoint, StandpointAxiomType.CONCEPT_INCLUSION, ax3));

            toRemove.add(e.getKey());

            PipelineLogger.log("Rule (5) on " + e.getKey()
                    + " [" + a + " " + role + " " + b + "]:");
            PipelineLogger.log("  → " + k1 + ": " + ax1);
            PipelineLogger.log("  → " + k2 + ": " + ax2);
            PipelineLogger.log("  → " + k3 + ": " + ax3);
        }

        toRemove.forEach(owlMap::remove);
        owlMap.putAll(toAdd);

        PipelineLogger.log("\n  Rule (5) applied to " + toRemove.size() + " entries.");
        PipelineLogger.log("\n=== owlMap after applyNegatedRoleAssertion ===");
        PipelineLogger.log("owlMap size: " + owlMap.size());
        owlMap.forEach((key, na) -> PipelineLogger.log("  " + key + " → " + na));
    }

    // ─── Rule (7): negated transitivity ──────────────────────────────────────

    private void applyNegatedTransitivity(Map<String, NormalisedAxiom> owlMap) {
        PipelineLogger.log("\n=== RULE (7): NEGATED TRANSITIVITY ===\n");

        List<String> toRemove = new ArrayList<>();
        Map<String, NormalisedAxiom> toAdd = new LinkedHashMap<>();

        for (Map.Entry<String, NormalisedAxiom> e : owlMap.entrySet()) {
            NormalisedAxiom na = e.getValue();
            if (!na.isRoot) continue;
            if (!na.isNegatedInner) continue;
            if (na.axiomType != StandpointAxiomType.ROLE_TRANSITIVITY) continue;
            if (!(na.owlAxiom instanceof OWLTransitiveObjectPropertyAxiom)) continue;

            OWLTransitiveObjectPropertyAxiom tra = (OWLTransitiveObjectPropertyAxiom) na.owlAxiom;
            OWLObjectProperty role = (OWLObjectProperty) tra.getProperty();

            String fcaName = placeholderCounter.generate(PlaceholderType.FRESH_CONCEPT);
            String fcbName = placeholderCounter.generate(PlaceholderType.FRESH_CONCEPT);
            String frName = placeholderCounter.generate(PlaceholderType.FRESH_ROLE);
            OWLClass freshCa = freshClass(fcaName);
            OWLClass freshCb = freshClass(fcbName);
            OWLObjectProperty freshRProp = freshRole(frName);
            registerFreshClass(fcaName);
            registerFreshClass(fcbName);
            registerFreshRole(frName);

            // Thing ⊑ ∃FR.FCa
            OWLAxiom ax1 = helperDf.getOWLSubClassOfAxiom(
                    helperDf.getOWLThing(),
                    helperDf.getOWLObjectSomeValuesFrom(freshRProp, freshCa));
            // (FCa ⊓ ∃role.FCb) ⊑ ⊥  →  Rule (11)
            OWLAxiom ax2 = applyRule11(
                    helperDf.getOWLObjectIntersectionOf(
                            freshCa, helperDf.getOWLObjectSomeValuesFrom(role, freshCb)),
                    helperDf.getOWLNothing());
            // FCa ⊑ ∃role.∃role.FCb  →  Rule (11)
            OWLAxiom ax3 = applyRule11(
                    freshCa,
                    helperDf.getOWLObjectSomeValuesFrom(
                            role, helperDf.getOWLObjectSomeValuesFrom(role, freshCb)));

            String k1 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
            String k2 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
            String k3 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
            registerSPn(k1);
            registerSPn(k2);
            registerSPn(k3);
            toAdd.put(k1, owlRootEntry(na.operator, na.standpoint, StandpointAxiomType.CONCEPT_INCLUSION, ax1));
            toAdd.put(k2, owlRootEntry(na.operator, na.standpoint, StandpointAxiomType.CONCEPT_INCLUSION, ax2));
            toAdd.put(k3, owlRootEntry(na.operator, na.standpoint, StandpointAxiomType.CONCEPT_INCLUSION, ax3));

            toRemove.add(e.getKey());

            PipelineLogger.log("Rule (7) on " + e.getKey() + " [Tra(" + role + ")]:");
            PipelineLogger.log("  → " + k1 + ": " + ax1);
            PipelineLogger.log("  → " + k2 + ": " + ax2);
            PipelineLogger.log("  → " + k3 + ": " + ax3);
        }

        toRemove.forEach(owlMap::remove);
        owlMap.putAll(toAdd);

        PipelineLogger.log("\n  Rule (7) applied to " + toRemove.size() + " entries.");
        PipelineLogger.log("\n=== owlMap after applyNegatedTransitivity ===");
        PipelineLogger.log("owlMap size: " + owlMap.size());
        owlMap.forEach((key, na) -> PipelineLogger.log("  " + key + " → " + na));
    }

    // ─── Rule (8): negated sharpenings ───────────────────────────────────────

    private void applyNegatedSharpenings(List<Sharpening> loadedSharpenings,
                                         List<Sharpening> sharpenings,
                                         Map<String, NormalisedAxiom> owlMap) {
        PipelineLogger.log("\n=== RULE (8): NEGATED SHARPENING ===\n");

        for (Sharpening s : loadedSharpenings) {
            if (!s.isNegated) continue;

            String freshV = placeholderCounter.generate(PlaceholderType.FRESH_STANDPOINT);
            for (String si : s.lhsStandpoints) {
                sharpenings.add(new Sharpening(Collections.singletonList(freshV), si));
                PipelineLogger.log("  → " + freshV + " ⪯ " + si);
            }
            List<String> vAndU = new ArrayList<>();
            vAndU.add(freshV);
            vAndU.add(s.rhsStandpoint);
            sharpenings.add(new Sharpening(vAndU, "0"));
            PipelineLogger.log("Rule (8) applied — fresh standpoint: " + freshV);
            PipelineLogger.log("  → " + freshV + " ∩ " + s.rhsStandpoint + " ⪯ 0");
        }

        PipelineLogger.log("\n=== owlMap after applyNegatedSharpenings ===");
        PipelineLogger.log("owlMap size: " + owlMap.size());
        owlMap.forEach((key, na) -> PipelineLogger.log("  " + key + " → " + na));
    }

    // ─── Rule (9): zero sharpenings ──────────────────────────────────────────

    private void applyZeroSharpenings(List<Sharpening> sharpenings,
                                      List<Sharpening> loadedSharpenings,
                                      Map<String, NormalisedAxiom> owlMap) {
        PipelineLogger.log("\n=== RULE (9): ZERO SHARPENING ===\n");

        List<Sharpening> zeroSharpenings = new ArrayList<>();
        for (Sharpening s : sharpenings) {
            if (s.isZero()) zeroSharpenings.add(s);
        }
        sharpenings.removeAll(zeroSharpenings);
        for (Sharpening s : loadedSharpenings) {
            if (!s.isNegated && s.isZero()) zeroSharpenings.add(s);
        }

        for (Sharpening zero : zeroSharpenings) {
            if (zero.lhsStandpoints.contains("0")) {
                PipelineLogger.log("Rule (9) skipped (trivial 0 ⪯ 0): " + zero);
                continue;
            }

            PipelineLogger.log("Rule (9) on: " + zero);
            List<OWLClass> freshConcepts = new ArrayList<>();

            for (String si : zero.lhsStandpoints) {
                String freshCiName = placeholderCounter.generate(PlaceholderType.FRESH_CONCEPT);
                OWLClass freshCi = freshClass(freshCiName);
                registerFreshClass(freshCiName);
                freshConcepts.add(freshCi);

                // Thing ⊑ FC_i — Rule (11) identity since subClass = Thing
                OWLAxiom axiom = helperDf.getOWLSubClassOfAxiom(helperDf.getOWLThing(), freshCi);
                String key = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
                registerSPn(key);
                owlMap.put(key, owlRootEntry(Operator.BOX, si,
                        StandpointAxiomType.CONCEPT_INCLUSION, axiom));
                PipelineLogger.log("  → " + key + ": □_" + si + "[⊤ ⊑ " + freshCiName + "]");
            }

            // (FC_1 ⊓ ... ⊓ FC_n) ⊑ ⊥  →  Rule (11)
            OWLClassExpression intersection = freshConcepts.size() == 1
                    ? freshConcepts.get(0)
                    : helperDf.getOWLObjectIntersectionOf(new HashSet<>(freshConcepts));
            OWLAxiom globalAxiom = applyRule11(intersection, helperDf.getOWLNothing());
            String globalKey = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
            registerSPn(globalKey);
            owlMap.put(globalKey, owlRootEntry(Operator.BOX, "*",
                    StandpointAxiomType.CONCEPT_INCLUSION, globalAxiom));
            PipelineLogger.log("  → " + globalKey + ": □_*[" + freshConcepts + " ⊑ ⊥]");
        }

        PipelineLogger.log("\n=== owlMap after applyZeroSharpening ===");
        PipelineLogger.log("owlMap size: " + owlMap.size());
        owlMap.forEach((key, na) -> PipelineLogger.log("  " + key + " → " + na));
    }

    // ─── collectNormalSharpenings ─────────────────────────────────────────────

    private void collectNormalSharpenings(List<Sharpening> loadedSharpenings,
                                          List<Sharpening> sharpenings) {
        for (Sharpening s : loadedSharpenings) {
            if (!s.isNegated && !s.isZero()) {
                sharpenings.add(s);
                PipelineLogger.log("Normal sharpening added: " + s);
            }
        }
    }

    private void expandToSubClassOf(Map<String, NormalisedAxiom> owlMap) {

        // Keys of multi-axiom root entries to remove after iteration
        // (EquivalentClasses, DisjointClasses, DisjointUnion)
        List<String> toRemove = new ArrayList<>();

        // New SubClassOf entries generated from splitting — added after iteration
        Map<String, NormalisedAxiom> toAdd = new LinkedHashMap<>();

        // SP_n keys that were cloned into fresh copies during splitting
        // The originals become orphaned after cloning so they are also deleted
        // Example: SP_3 was a child of SP_1 (EquivalentClasses)
        //          SP_1 is split into SP_17 and SP_18
        //          SP_3 is cloned into SP_19 (child of SP_17) and SP_20 (child of SP_18)
        //          SP_3 is now orphaned — no parent references it anymore → deleted
        Set<String> clonedOriginals = new LinkedHashSet<>();

        for (Map.Entry<String, NormalisedAxiom> e : owlMap.entrySet()) {
            String key = e.getKey();
            NormalisedAxiom na = e.getValue();

            // only root entries carry multi-axiom types
            // non-root entries are modal sub-expressions (NONE type) — skip them
            if (!na.isRoot) continue;

            // ── EquivalentClasses → two SubClassOf ───────────────────────────────
            // A EquivalentTo: B  →  SubClassOf(A, B) + SubClassOf(B, A)
            // OWL API asOWLSubClassOfAxioms() produces both directions automatically
            if (na.owlAxiom instanceof OWLEquivalentClassesAxiom) {
                for (OWLSubClassOfAxiom sub :
                        ((OWLEquivalentClassesAxiom) na.owlAxiom).asOWLSubClassOfAxioms()) {

                    // if the SubClassOf contains SP_n placeholders as children,
                    // clone them into fresh copies so no SP_n is shared between
                    // two parent entries — each SubClassOf owns its children exclusively
                    OWLSubClassOfAxiom cloned = cloneWithFreshPlaceholders(sub, owlMap, toAdd, clonedOriginals);

                    // generate a fresh SP_n key for the new SubClassOf root entry
                    String freshKey = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);

                    // inherit operator and standpoint from the original EquivalentClasses entry
                    // axiom type becomes CONCEPT_INCLUSION — it is now a plain SubClassOf
                    toAdd.put(freshKey, new NormalisedAxiom(
                            na.operator, na.standpoint,
                            StandpointAxiomType.CONCEPT_INCLUSION,
                            true, na.isNegatedInner,
                            cloned, null, na.manchester,
                            extractChildKeysFromAxiom(cloned)));
                }
                // mark original EquivalentClasses entry for removal
                toRemove.add(key);
                continue;
            }

            // ── DisjointClasses → n*(n-1)/2 SubClassOf ───────────────────────────
            // DisjointClasses(A, B, C)  →  SubClassOf(A, not(B))
            //                               SubClassOf(A, not(C))
            //                               SubClassOf(B, not(C))
            // OWL API asOWLSubClassOfAxioms() generates all pairs automatically
            if (na.owlAxiom instanceof OWLDisjointClassesAxiom) {
                for (OWLSubClassOfAxiom sub :
                        ((OWLDisjointClassesAxiom) na.owlAxiom).asOWLSubClassOfAxioms()) {

                    // clone SP_n children — same reason as EquivalentClasses above
                    OWLSubClassOfAxiom cloned = cloneWithFreshPlaceholders(sub, owlMap, toAdd, clonedOriginals);

                    String freshKey = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);

                    toAdd.put(freshKey, new NormalisedAxiom(
                            na.operator, na.standpoint,
                            StandpointAxiomType.CONCEPT_INCLUSION,
                            true, na.isNegatedInner,
                            cloned, null, na.manchester,
                            extractChildKeysFromAxiom(cloned)));
                }
                toRemove.add(key);
                continue;
            }

            // ── DisjointUnionOf → union part + disjoint part ─────────────────────
            // DisjointUnion(A, B, C, D) splits into TWO groups:
            //
            // GROUP 1 — union part (from getOWLEquivalentClassesAxiom()):
            //   A EquivalentTo: B or C or D
            //   → SubClassOf(A, B or C or D)
            //   → SubClassOf(B or C or D, A)
            //
            // GROUP 2 — disjoint part (from getOWLDisjointClassesAxiom()):
            //   DisjointClasses(B, C, D)
            //   → SubClassOf(B, not(C))
            //   → SubClassOf(B, not(D))
            //   → SubClassOf(C, not(D))
            if (na.owlAxiom instanceof OWLDisjointUnionAxiom) {
                OWLDisjointUnionAxiom du = (OWLDisjointUnionAxiom) na.owlAxiom;

                // GROUP 1 — union part
                for (OWLSubClassOfAxiom sub :
                        du.getOWLEquivalentClassesAxiom().asOWLSubClassOfAxioms()) {
                    OWLSubClassOfAxiom cloned = cloneWithFreshPlaceholders(sub, owlMap, toAdd, clonedOriginals);

                    String freshKey = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);

                    toAdd.put(freshKey, new NormalisedAxiom(
                            na.operator, na.standpoint,
                            StandpointAxiomType.CONCEPT_INCLUSION,
                            true, na.isNegatedInner,
                            cloned, null, na.manchester,
                            extractChildKeysFromAxiom(cloned)));
                }

                // GROUP 2 — disjoint part
                for (OWLSubClassOfAxiom sub :
                        du.getOWLDisjointClassesAxiom().asOWLSubClassOfAxioms()) {
                    OWLSubClassOfAxiom cloned = cloneWithFreshPlaceholders(sub, owlMap, toAdd, clonedOriginals);

                    String freshKey = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);

                    toAdd.put(freshKey, new NormalisedAxiom(
                            na.operator, na.standpoint,
                            StandpointAxiomType.CONCEPT_INCLUSION,
                            true, na.isNegatedInner,
                            cloned, null, na.manchester,
                            extractChildKeysFromAxiom(cloned)));
                }

                toRemove.add(key);
            }
        }

        // STEP 1 — remove original multi-axiom root entries
        //          (EquivalentClasses, DisjointClasses, DisjointUnion)
        //          they have been replaced by their SubClassOf expansions in toAdd
        toRemove.forEach(owlMap::remove);

        // STEP 2 — remove SP_n entries that were cloned during expansion
        //          their content now lives under fresh keys in toAdd
        //          the originals are orphaned — no parent references them anymore
        clonedOriginals.forEach(owlMap::remove);

        // STEP 3 — add all fresh SubClassOf entries and fresh SP_n clones
        //          cannot do this during iteration — ConcurrentModificationException
        owlMap.putAll(toAdd);
    }

    /**
     * Clones an OWLSubClassOfAxiom by replacing every SP_n placeholder
     * found in its subClass or superClass with a fresh copy.
     *
     * This ensures that after splitting EquivalentClasses/DisjointClasses/DisjointUnion
     * into multiple SubClassOf entries, no SP_n is shared between two parent entries.
     *
     * Example:
     *   Original: SubClassOf(SP_2, Teacher)   where SP_2 → □_s363[SP_1], SP_1 → ◇_s3[Q2]
     *   Cloned:   SubClassOf(SP_4, Teacher)   where SP_4 → □_s363[SP_5], SP_5 → ◇_s3[Q2]
     *   SP_2 and SP_1 are marked for deletion — SP_4 and SP_5 are fresh exclusive copies
     */
    private OWLSubClassOfAxiom cloneWithFreshPlaceholders(
            OWLSubClassOfAxiom sub,
            Map<String, NormalisedAxiom> owlMap,
            Map<String, NormalisedAxiom> toAdd,
            Set<String> clonedOriginals) {

        // walk subClass and superClass separately
        // each side gets its own fresh SP_n copies — they never share
        OWLClassExpression subClass = cloneExpr(
                sub.getSubClass(), owlMap, toAdd, clonedOriginals);
        OWLClassExpression superClass = cloneExpr(
                sub.getSuperClass(), owlMap, toAdd, clonedOriginals);
        return helperDf.getOWLSubClassOfAxiom(subClass, superClass);
    }

    /**
     * Recursively walks an OWLClassExpression tree.
     *
     * For every SP_n placeholder class found at any depth:
     *   1. Generates a fresh SP_n key
     *   2. Recursively clones the placeholder's own owlTree
     *      (so children of children also get fresh copies — full deep clone)
     *   3. Registers the new entry in toAdd under the fresh key
     *   4. Marks the original key in clonedOriginals for deletion
     *   5. Returns a new OWLClass pointing to the fresh key
     *
     * For non-placeholder named classes (e.g. Cat, Animal, r):
     *   Returns the class unchanged — real ontology entities are shared safely
     *
     * For complex expressions (UnionOf, IntersectionOf, ComplementOf, etc.):
     *   Recurses into each operand/filler — rebuilds the expression with
     *   fresh copies of any SP_n found inside
     *
     * Example deep clone:
     *   SP_2 → □_s363 [ SP_1 ]    SP_1 → ◇_s3 [ Q2 ]
     *
     *   cloneExpr(SP_2):
     *     → generates SP_4
     *     → cloneExpr(SP_1):           ← recursive call on SP_2's owlTree
     *         → generates SP_5
     *         → SP_5 owlTree = Q2      ← Q2 is not a placeholder, returned unchanged
     *         → marks SP_1 for deletion
     *     → SP_4 owlTree = SP_5        ← rebuilt with fresh child
     *     → marks SP_2 for deletion
     *   → returns OWLClass(SP_4)
     *
     *   Result: SP_4 → □_s363[SP_5],  SP_5 → ◇_s3[Q2]
     *           SP_2 deleted, SP_1 deleted
     */
    private OWLClassExpression cloneExpr(
            OWLClassExpression expr,
            Map<String, NormalisedAxiom> owlMap,
            Map<String, NormalisedAxiom> toAdd,
            Set<String> clonedOriginals) {

        if (expr instanceof OWLClass) {
            OWLClass cls = (OWLClass) expr;
            if (PlaceholderType.isModalPlaceholder(cls)) {
                String oldKey = PlaceholderType.keyOf(cls);
                String newKey = placeholderCounter
                        .generate(PlaceholderType.MODAL_PLACEHOLDER);

                // look up the original entry — may already be in toAdd
                // if it was cloned in an earlier iteration of the same expandToSubClassOf call
                NormalisedAxiom original = owlMap.get(oldKey);
                if (original == null) original = toAdd.get(oldKey);

                if (original != null) {
                    // deep clone — recursively clone this placeholder's own owlTree
                    // so children of children also get their own fresh copies
                    OWLClassExpression clonedTree = original.owlTree != null
                            ? cloneExpr(original.owlTree, owlMap, toAdd, clonedOriginals)
                            : null;

                    // root axioms (owlAxiom != null) are not cloned recursively
                    // they belong to root entries which are not SP_n children
                    OWLAxiom clonedAxiom = original.owlAxiom;

                    // recompute childKeys from the cloned tree
                    // (the fresh tree has new SP_n keys — old keys would be wrong)
                    Set<String> newChildKeys = clonedTree != null
                            ? extractChildKeysFromExpr(clonedTree)
                            : original.childKeys;

                    toAdd.put(newKey, new NormalisedAxiom(
                            original.operator, original.standpoint,
                            original.axiomType, original.isRoot,
                            original.isNegatedInner,
                            clonedAxiom, clonedTree,
                            original.manchester, newChildKeys));
                }

                // mark original for deletion — it now has a fresh copy
                // and no parent references it anymore
                clonedOriginals.add(oldKey);

                // register the new SP_n key in the helper ontology
                // so the Manchester parser can resolve it if needed later
                registerSPn(newKey);

                // return a new OWLClass pointing to the fresh key
                return helperDf.getOWLClass(IRI.create(
                        PlaceholderType.PLUGIN_NS + newKey));
            }
            // not a placeholder — real ontology entity, return unchanged
            return cls;
        }

        // ── Recurse into complex expressions ─────────────────────────────────────
        // rebuild each expression type with fresh-cloned operands

        if (expr instanceof OWLObjectIntersectionOf) {
            Set<OWLClassExpression> ops = ((OWLObjectIntersectionOf) expr)
                    .getOperands().stream()
                    .map(op -> cloneExpr(op, owlMap, toAdd, clonedOriginals))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return helperDf.getOWLObjectIntersectionOf(ops);
        }
        if (expr instanceof OWLObjectUnionOf) {
            Set<OWLClassExpression> ops = ((OWLObjectUnionOf) expr)
                    .getOperands().stream()
                    .map(op -> cloneExpr(op, owlMap, toAdd, clonedOriginals))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return helperDf.getOWLObjectUnionOf(ops);
        }
        if (expr instanceof OWLObjectComplementOf) {
            return helperDf.getOWLObjectComplementOf(cloneExpr(
                    ((OWLObjectComplementOf) expr).getOperand(),
                    owlMap, toAdd, clonedOriginals));
        }
        if (expr instanceof OWLObjectSomeValuesFrom) {
            OWLObjectSomeValuesFrom some = (OWLObjectSomeValuesFrom) expr;
            return helperDf.getOWLObjectSomeValuesFrom(
                    some.getProperty(),
                    cloneExpr(some.getFiller(), owlMap, toAdd, clonedOriginals));
        }
        if (expr instanceof OWLObjectAllValuesFrom) {
            OWLObjectAllValuesFrom all = (OWLObjectAllValuesFrom) expr;
            return helperDf.getOWLObjectAllValuesFrom(
                    all.getProperty(),
                    cloneExpr(all.getFiller(), owlMap, toAdd, clonedOriginals));
        }

        // no SP_n inside — return expression unchanged
        return expr;
    }

    // ─── Expansion ───────────────────────────────────────────────────────────

    private List<AxiomWithLabel> expandFormulas(
            List<ParsedFormula> formulas,
            Map<String, AxiomWithLabel> axiomLabelMap,
            List<Sharpening> sharpening) {

        List<AxiomWithLabel> result = new ArrayList<>();

        for (ParsedFormula formula : formulas) {
            String operator = formula.operator;
            String standpoint = formula.standpoint;

            if (Operator.DIAMOND.toString().toLowerCase().equals(operator)) {
                String freshStandpoint = placeholderCounter.generate(PlaceholderType.FRESH_STANDPOINT);
                sharpening.add(new Sharpening(Collections.singletonList(freshStandpoint), standpoint));
                PipelineLogger.log("Rule (1) applied on formula ◇_" + standpoint + " → fresh standpoint: " + freshStandpoint + " ⪯ " + standpoint);
                operator = Operator.BOX.toString().toLowerCase();
                standpoint = freshStandpoint;
            }

            for (ParsedLiteral literal : formula.literals) {
                AxiomWithLabel axiomWithLabel = axiomLabelMap.get(literal.ref);
                if (axiomWithLabel == null) {
                    PipelineLogger.log("WARNING: axiom ref '" + literal.ref + "' not found!");
                    continue;
                }

                String innerContent = extractInnerContent(axiomWithLabel.standpointLabel);
                String wrappedLabel = literal.negated
                        ? "<modal op=\"" + operator + "\" standpoint=\"" + standpoint
                        + "\" negatedInner=\"true\">" + innerContent + "</modal>"
                        : "<modal op=\"" + operator + "\" standpoint=\"" + standpoint
                        + "\">" + innerContent + "</modal>";
                result.add(new AxiomWithLabel(axiomWithLabel.axiom, wrappedLabel, axiomWithLabel.standpointAxiomType));
            }
        }
        return result;
    }

    // ─── Helper ontology ─────────────────────────────────────────────────────

    private void buildHelperOntology() throws OWLOntologyCreationException {
        helperManager = OWLManager.createOWLOntologyManager();
        helperDf = helperManager.getOWLDataFactory();
        helperOntology = helperManager.createOntology(IRI.create("http://standpoint.org/helper"));
        helperManager.addAxiom(helperOntology,
                helperDf.getOWLDeclarationAxiom(helperDf.getOWLNothing()));
        helperManager.addAxiom(helperOntology,
                helperDf.getOWLDeclarationAxiom(helperDf.getOWLThing()));

        // TODO: remove unused individual from the start
        for (OWLClass cls : ontology.getClassesInSignature())
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(cls));
        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature())
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(prop));
        for (OWLNamedIndividual ind : ontology.getIndividualsInSignature())
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(ind));

        String iri = ontology.getOntologyID().getOntologyIRI().get().toString();
        if (!iri.endsWith("#") && !iri.endsWith("/")) iri += "#";
        sourceBase = iri;

        normaliser = new ManchesterNormaliser(helperDf, helperManager, helperOntology);
    }

    // ─── Substitution ─────────────────────────────────────────────

    private Map<String, ModalPlaceholder> substitute(List<AxiomWithLabel> expandedAxioms) {

        Map<String, ModalPlaceholder> placeholderMap = new LinkedHashMap<>();
        for (AxiomWithLabel axiomWithLabel : expandedAxioms) {
            String standpointLabel = axiomWithLabel.standpointLabel;
            PipelineLogger.log("Processing: " + standpointLabel);

            ModalExpressionDecomposer decomposer = new ModalExpressionDecomposer(placeholderCounter);
            String rootKey = decomposer.substitute(standpointLabel, axiomWithLabel.standpointAxiomType);
            Map<String, ModalPlaceholder> subMap = decomposer.getMap();

            // Register SP_n keys in helper ontology
            for (String key : subMap.keySet()) registerSPn(key);

            // Set axiom type on root entry
            ModalPlaceholder rootEntry = subMap.get(rootKey);
            rootEntry.standpointAxiomType = axiomWithLabel.standpointAxiomType;

            PipelineLogger.log("  Root: " + rootKey + " → " + rootEntry);
            placeholderMap.putAll(subMap);
        }
        return placeholderMap;
    }

    // ─── convertToOwl ─────────────────────────────────────────────

    private Map<String, NormalisedAxiom> convertToOwl(Map<String, ModalPlaceholder> placeholderMap) {

        Map<String, NormalisedAxiom> owlMap = new LinkedHashMap<>();
        PipelineLogger.log("\n=== STEP 4: CONVERT TO OWL ===\n");

        for (Map.Entry<String, ModalPlaceholder> e : placeholderMap.entrySet()) {
            String key = e.getKey();
            ModalPlaceholder mp = e.getValue();

            NormalisedAxiom na = parseEntry(key, mp);
            owlMap.put(key, na);
        }

        PipelineLogger.log("\n✅ OWL conversion complete — " + owlMap.size() + " entries.");
        return owlMap;
    }

    // Thing ⊑ NNF(¬subClass ⊔ superClass)
    private OWLAxiom applyRule11(OWLClassExpression subClass, OWLClassExpression superClass) {
        OWLClassExpression rhs;
        if (subClass.isOWLThing()) {
            rhs = superClass.getNNF();
        } else {
            rhs = helperDf.getOWLObjectUnionOf(
                    helperDf.getOWLObjectComplementOf(subClass), superClass).getNNF();
        }
        return helperDf.getOWLSubClassOfAxiom(helperDf.getOWLThing(), rhs);
    }

    private NormalisedAxiom owlRootEntry(Operator op, String standpoint,
                                         StandpointAxiomType type, OWLAxiom axiom) {
        return new NormalisedAxiom(op, standpoint, type, true, false,
                axiom, null, axiom.toString(), extractChildKeysFromAxiom(axiom));
    }

    private void registerFreshClass(String name) {
        helperManager.addAxiom(helperOntology,
                helperDf.getOWLDeclarationAxiom(freshClass(name)));
    }

    private void registerFreshRole(String name) {
        helperManager.addAxiom(helperOntology,
                helperDf.getOWLDeclarationAxiom(freshRole(name)));
    }

    private OWLClass freshClass(String name) {
        return helperDf.getOWLClass(IRI.create(sourceBase + name));
    }

    private OWLObjectProperty freshRole(String name) {
        return helperDf.getOWLObjectProperty(IRI.create(sourceBase + name));
    }

    // ─── Helper ontology registration ────────────────────────────────────────

    private void registerSPn(String key) {
        helperManager.addAxiom(helperOntology,
                helperDf.getOWLDeclarationAxiom(
                        helperDf.getOWLClass(
                                IRI.create(PlaceholderType.PLUGIN_NS + key))));
    }

    private OWLClassExpression parseInnerContent(String manchester) {
        try {
            return normaliser.parseManchesterExpression(manchester);
        } catch (Exception e) {
            PipelineLogger.log("WARNING: could not parse OWL expression: '"
                    + manchester + "' — " + e.getMessage());
            return null;
        }
    }

    private OWLAxiom parseInnerAxiom(String manchester, StandpointAxiomType standpointAxiomType) {
        OWLAxiom axiom = normaliser.parseAxiom(manchester, standpointAxiomType);
        if (axiom == null)
            PipelineLogger.log("WARNING: could not parse OWL axiom: '" + manchester + "'");
        return axiom;
    }

    private NormalisedAxiom parseEntry(String key, ModalPlaceholder mp) {
        StandpointAxiomType type = mp.standpointAxiomType;

        // Non-root modal sub-expression — concept expression only
        if (type == StandpointAxiomType.NONE) {
            OWLClassExpression owlTree = parseInnerContent(mp.manchester);
            return new NormalisedAxiom(mp.operator, mp.standpoint, type, mp.isRoot, mp.isNegatedInner,
                    null, owlTree, mp.manchester, extractChildKeysFromExpr(owlTree));
        }

        // TODO: to check
        // Root axiom — parse as full OWL axiom
//        String axiomString = mp.manchester
//                .replace("owl:Nothing", "Nothing")
//                .replace("owl:Thing", "Thing");

        OWLAxiom owlAxiom = parseInnerAxiom(mp.manchester, mp.standpointAxiomType);

        // Verify parsed axiom type matches expected StandpointAxiomType
        switch (type) {
            case CONCEPT_INCLUSION:
                if (!(owlAxiom instanceof OWLSubClassOfAxiom))
                    PipelineLogger.log("WARNING: expected SubClassOf for " + key + " but got " + owlAxiom.getAxiomType());
                break;
            case CONCEPT_EQUIVALENCE:
                if (!(owlAxiom instanceof OWLEquivalentClassesAxiom))
                    PipelineLogger.log("WARNING: expected EquivalentClasses for " + key + " but got " + owlAxiom.getAxiomType());
                break;
            case CONCEPT_DISJOINT:
                if (!(owlAxiom instanceof OWLDisjointClassesAxiom))
                    PipelineLogger.log("WARNING: expected DisjointClasses for " + key + " but got " + owlAxiom.getAxiomType());
                break;
            case CONCEPT_DISJOINT_UNION:
                if (!(owlAxiom instanceof OWLDisjointUnionAxiom))
                    PipelineLogger.log("WARNING: expected DisjointUnion for " + key + " but got " + owlAxiom.getAxiomType());
                break;
            case CONCEPT_ASSERTION:
                if (!(owlAxiom instanceof OWLClassAssertionAxiom))
                    PipelineLogger.log("WARNING: expected ClassAssertion for " + key + " but got " + owlAxiom.getAxiomType());
                break;
            case ROLE_INCLUSION:
                if (!(owlAxiom instanceof OWLSubObjectPropertyOfAxiom))
                    PipelineLogger.log("WARNING: expected SubObjectPropertyOf for " + key + " but got " + owlAxiom.getAxiomType());
                break;
            case ROLE_ASSERTION:
                if (!(owlAxiom instanceof OWLObjectPropertyAssertionAxiom))
                    PipelineLogger.log("WARNING: expected ObjectPropertyAssertion for " + key + " but got " + owlAxiom.getAxiomType());
                break;
            case ROLE_TRANSITIVITY:
                if (!(owlAxiom instanceof OWLTransitiveObjectPropertyAxiom))
                    PipelineLogger.log("WARNING: expected TransitiveObjectProperty for " + key + " but got " + owlAxiom.getAxiomType());
                break;
            default:
                PipelineLogger.log("WARNING: unhandled StandpointAxiomType " + type + " for " + key);
        }

        return new NormalisedAxiom(mp.operator, mp.standpoint, type, mp.isRoot, mp.isNegatedInner,
                owlAxiom, null, mp.manchester, extractChildKeysFromAxiom(owlAxiom));
    }

    private Set<String> extractChildKeysFromAxiom(OWLAxiom owlAxiom) {
        Set<String> children = new LinkedHashSet<>();
        if (owlAxiom == null) return children;
        for (OWLClass cls : owlAxiom.getClassesInSignature()) {
            if (PlaceholderType.isModalPlaceholder(cls))
                children.add(cls.getIRI().getShortForm());
        }
        return children;
    }

    private Set<String> extractChildKeysFromExpr(OWLClassExpression owlTree) {
        Set<String> children = new LinkedHashSet<>();
        if (owlTree == null) return children;
        for (OWLClass cls : owlTree.getClassesInSignature()) {
            if (PlaceholderType.isModalPlaceholder(cls))
                children.add(cls.getIRI().getShortForm());
        }
        return children;
    }

    // ─── Logging ─────────────────────────────────────────────────────────────

    private void logLoadedSharpening(List<Sharpening> sharpenings) {
        PipelineLogger.log("\n=== LOADED SHARPENINGS ===\n");
        if (sharpenings.isEmpty()) PipelineLogger.log("(none)");
        else sharpenings.forEach(s -> PipelineLogger.log(s.toString()));
        PipelineLogger.log("");
    }

    private void logOriginalFormulas(List<ParsedFormula> formulas,
                                     Map<String, AxiomWithLabel> axiomLabelMap) {
        PipelineLogger.log("\n=== ORIGINAL FORMULAS ===\n");
        for (ParsedFormula formula : formulas) {
            StringBuilder sb = new StringBuilder();
            String symbol = "box".equals(formula.operator) ? "□" : "◇";
            sb.append(symbol).append("_").append(formula.standpoint).append("[");
            for (int i = 0; i < formula.literals.size(); i++) {
                ParsedLiteral lit = formula.literals.get(i);
                if (lit.negated) sb.append("¬");
                sb.append(lit.ref);
                if (i < formula.literals.size() - 1) sb.append(" ∧ ");
            }
            sb.append("]");
            PipelineLogger.log(sb.toString());
        }
        PipelineLogger.log("");
        PipelineLogger.log("\n=== AXIOM LABELS (XML) ===\n");
        axiomLabelMap.forEach((k, v) ->
                PipelineLogger.log(k + " → " + v.standpointLabel));
        PipelineLogger.log("");
        PipelineLogger.log("\n=== AXIOM LABELS (READABLE) ===\n");
        axiomLabelMap.forEach((k, v) ->
                PipelineLogger.log(k + " → " + formatAxiomLabel(v.standpointLabel)));
        PipelineLogger.log("");
    }

    private void logOwlMap(Map<String, NormalisedAxiom> owlMap, String title) {
        PipelineLogger.log("\n=== " + title.toUpperCase() + " ===");
        PipelineLogger.log("owlMap size: " + owlMap.size());
        owlMap.forEach((key, na) ->
                PipelineLogger.log("  " + key + " → "
                        + (na.operator == Operator.BOX ? "□" : "◇")
                        + "_" + na.standpoint
                        + (na.isRoot
                        ? " [ROOT][" + na.axiomType + "]"
                        + (na.isNegatedInner ? "[NEGATED_INNER]" : "")
                        + " " + na.owlAxiom.getAxiomType().getName()
                        + ": " + na.owlAxiom
                        : " " + na.owlTree.getClass().getSimpleName()
                        + ": " + na.owlTree)
                        + (na.childKeys != null && !na.childKeys.isEmpty()
                        ? " children=" + na.childKeys : "")));
    }

    private void logExpandedFormulas(List<AxiomWithLabel> expandedAxioms) {
        PipelineLogger.log("\n=== EXPANDED FORMULAS (XML) ===\n");
        expandedAxioms.forEach(a -> PipelineLogger.log(a.standpointLabel));
        PipelineLogger.log("");
        PipelineLogger.log("\n=== EXPANDED FORMULAS (READABLE) ===\n");
        expandedAxioms.forEach(a -> PipelineLogger.log(formatFormula(a.standpointLabel)));
        PipelineLogger.log("");
    }

    // ─── XML / Manchester rendering (annotation parsing) ─────────────────────

    private String extractInnerContent(String xml) {
        try {
            Document doc = parseXml(xml);
            Node axiom = doc.getDocumentElement().getFirstChild();
            StringBuilder sb = new StringBuilder();
            NodeList children = axiom.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.TEXT_NODE)
                    sb.append(child.getTextContent());
                else if (child.getNodeType() == Node.ELEMENT_NODE)
                    sb.append(nodeToString(child));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid axiom label XML: " + e.getMessage(), e);
        }
    }

    private String formatFormula(String xml) {
        try {
            Document doc = parseXml(xml);
            return formatNode(doc.getDocumentElement().getFirstChild());
        } catch (Exception e) {
            return xml;
        }
    }

    private String formatAxiomLabel(String xml) {
        try {
            Document doc = parseXml(xml);
            Node axiom = doc.getDocumentElement().getFirstChild();
            StringBuilder sb = new StringBuilder();
            NodeList children = axiom.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.TEXT_NODE)
                    sb.append(child.getTextContent().trim());
                else if (child.getNodeType() == Node.ELEMENT_NODE
                        && child.getNodeName().equals("modal"))
                    sb.append(formatNode(child));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return xml;
        }
    }

    private String formatNode(Node node) {
        if (node == null) return "";
        if (node.getNodeType() == Node.TEXT_NODE)
            return node.getTextContent().trim();

        String op = node.getAttributes().getNamedItem("op").getNodeValue();
        String standpoint = node.getAttributes().getNamedItem("standpoint").getNodeValue();
        Node negated = node.getAttributes().getNamedItem("negated");
        Node negatedInner = node.getAttributes().getNamedItem("negatedInner");
        String symbol = "box".equals(op) ? "□" : "◇";

        StringBuilder inner = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE)
                inner.append(child.getTextContent().trim());
            else if (child.getNodeType() == Node.ELEMENT_NODE)
                inner.append(formatNode(child));
        }

        if (negatedInner != null && "true".equals(negatedInner.getNodeValue()))
            return symbol + "_" + standpoint + " [ ¬ ( " + inner.toString().trim() + " ) ] ";
        if (negated != null && "true".equals(negated.getNodeValue()))
            return "¬" + symbol + "_" + standpoint + " [ " + inner.toString().trim() + " ] ";
        return symbol + "_" + standpoint + " [ " + inner.toString().trim() + " ] ";
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(
                new InputSource(new StringReader("<root>" + xml.trim() + "</root>")));
    }

    private String nodeToString(Node node) throws Exception {
        javax.xml.transform.TransformerFactory tf =
                javax.xml.transform.TransformerFactory.newInstance();
        javax.xml.transform.Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(
                javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
        java.io.StringWriter writer = new java.io.StringWriter();
        transformer.transform(
                new javax.xml.transform.dom.DOMSource(node),
                new javax.xml.transform.stream.StreamResult(writer));
        return writer.toString();
    }

    // ─── Unreferenced / unlabelled axiom collection ───────────────────────────

    private void injectUnreferencedAxioms(
            List<AxiomWithLabel> expandedAxioms,
            Map<String, AxiomWithLabel> axiomLabelMap,
            List<ParsedFormula> formulas) {

        Set<String> referencedIds = new HashSet<>();
        for (ParsedFormula formula : formulas)
            for (ParsedLiteral lit : formula.literals)
                referencedIds.add(lit.ref);

        List<AxiomWithLabel> result = new ArrayList<>();

        for (Map.Entry<String, AxiomWithLabel> e : axiomLabelMap.entrySet()) {
            if (!referencedIds.contains(e.getKey())) {
                AxiomWithLabel original = e.getValue();
                String manchesterContent =
                        extractInnerContent(original.standpointLabel);
                result.add(new AxiomWithLabel(original.axiom, wrapAsStar(manchesterContent), original.standpointAxiomType));
            }
        }

        PipelineLogger.log("Step 2b — wrapped " + result.size() + " unreferenced/unlabelled axioms as □_*");
        expandedAxioms.addAll(result);
    }

    private void injectUnlabelledAxioms(
            Map<String, NormalisedAxiom> owlMap,
            Map<String, AxiomWithLabel> axiomLabelMap) {

        Set<OWLAxiom> labelledAxioms = new HashSet<>();
        for (AxiomWithLabel ax : axiomLabelMap.values())
            labelledAxioms.add(ax.axiom);

        Set<AxiomType<?>> supported = new HashSet<>(Arrays.asList(
                AxiomType.SUBCLASS_OF, AxiomType.CLASS_ASSERTION,
                AxiomType.SUB_OBJECT_PROPERTY, AxiomType.OBJECT_PROPERTY_ASSERTION,
                AxiomType.TRANSITIVE_OBJECT_PROPERTY, AxiomType.EQUIVALENT_CLASSES,
                AxiomType.DISJOINT_CLASSES, AxiomType.DISJOINT_UNION));

        for (OWLAxiom ax : ontology.getAxioms()) {
            if (!supported.contains(ax.getAxiomType())) continue;
            if (labelledAxioms.contains(ax)) continue;

            String key = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
            StandpointAxiomType type = getAxiomType(ax);
            owlMap.put(key, new NormalisedAxiom(Operator.BOX, "*", type, true,
                    false, ax, null, "", extractChildKeysFromAxiom(ax)));
        }
    }

    private String wrapAsStar(String manchesterContent) {
        return "<modal op=\"box\" standpoint=\"*\">" + manchesterContent + "</modal>";
    }

    private StandpointAxiomType getAxiomType(OWLAxiom ax) {
        if (ax instanceof OWLSubClassOfAxiom) return StandpointAxiomType.CONCEPT_INCLUSION;
        if (ax instanceof OWLEquivalentClassesAxiom) return StandpointAxiomType.CONCEPT_EQUIVALENCE;
        if (ax instanceof OWLDisjointClassesAxiom) return StandpointAxiomType.CONCEPT_DISJOINT;
        if (ax instanceof OWLDisjointUnionAxiom) return StandpointAxiomType.CONCEPT_DISJOINT_UNION;
        if (ax instanceof OWLClassAssertionAxiom) return CONCEPT_ASSERTION;
        if (ax instanceof OWLSubObjectPropertyOfAxiom) return StandpointAxiomType.ROLE_INCLUSION;
        if (ax instanceof OWLObjectPropertyAssertionAxiom) return StandpointAxiomType.ROLE_ASSERTION;
        if (ax instanceof OWLTransitiveObjectPropertyAxiom) return StandpointAxiomType.ROLE_TRANSITIVITY;
        return null;
    }
}
