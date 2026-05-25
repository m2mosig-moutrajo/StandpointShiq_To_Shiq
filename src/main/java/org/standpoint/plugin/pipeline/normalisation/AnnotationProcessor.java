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

    private final Map<String, NormalisedAxiom> rawOwlMap = new LinkedHashMap<>();

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

        PipelineLogger.log("\n=== owlMap after injection before expand to subclassof===");
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

        expandToSubClassOf(owlMap);

        PipelineLogger.log("\n=== owlMap after injection ===");
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

//        applyNegatedGCI(placeholderMap);
//        applyConceptAssertions(placeholderMap);
//        applyNegatedRoleInclusion(placeholderMap);
//        applyNegatedRoleAssertion(placeholderMap);
//        applyNegatedTransitivity(placeholderMap);
//        applyNegatedSharpenings(loadedSharpening, sharpening);
//        applyZeroSharpenings(sharpening, loadedSharpening);
//        collectNormalSharpenings(loadedSharpening, sharpening);
//
//        applyFinalOWLNormalisations();
//
//        sharpening.removeIf(s -> s.lhsStandpoints.contains("0"));
//        PipelineLogger.log("Sharpenings after trivial removal: " + sharpening);
//
//        printResults(placeholderMap, sharpening);
//
        StandpointKnowledgeBase kb = new StandpointKnowledgeBase(placeholderMap, sharpening, ontology);
//        kb.owlMap = rawOwlMap;
//
//        PipelineLogger.log("\n=== OWL MAP (normalised) ===\n");
//        rawOwlMap.forEach((k, v) -> PipelineLogger.log("  " + k + " → " + v));
//
        return kb;
    }

    private void expandToSubClassOf(Map<String, NormalisedAxiom> owlMap) {

        List<String> toRemove = new ArrayList<>();
        Map<String, NormalisedAxiom> toAdd = new LinkedHashMap<>();
        Set<String> clonedOriginals = new LinkedHashSet<>(); // SP_n that were cloned

        for (Map.Entry<String, NormalisedAxiom> e : owlMap.entrySet()) {
            String key = e.getKey();
            NormalisedAxiom na = e.getValue();

            if (!na.isRoot) continue;

            if (na.owlAxiom instanceof OWLEquivalentClassesAxiom) {
                for (OWLSubClassOfAxiom sub :
                        ((OWLEquivalentClassesAxiom) na.owlAxiom)
                                .asOWLSubClassOfAxioms()) {
                    OWLSubClassOfAxiom cloned = cloneWithFreshPlaceholders(
                            sub, owlMap, toAdd, clonedOriginals);
                    String freshKey = placeholderCounter
                            .generate(PlaceholderType.MODAL_PLACEHOLDER);
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

            if (na.owlAxiom instanceof OWLDisjointClassesAxiom) {
                for (OWLSubClassOfAxiom sub :
                        ((OWLDisjointClassesAxiom) na.owlAxiom)
                                .asOWLSubClassOfAxioms()) {
                    OWLSubClassOfAxiom cloned = cloneWithFreshPlaceholders(
                            sub, owlMap, toAdd, clonedOriginals);
                    String freshKey = placeholderCounter
                            .generate(PlaceholderType.MODAL_PLACEHOLDER);
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

            if (na.owlAxiom instanceof OWLDisjointUnionAxiom) {
                OWLDisjointUnionAxiom du = (OWLDisjointUnionAxiom) na.owlAxiom;

                for (OWLSubClassOfAxiom sub :
                        du.getOWLEquivalentClassesAxiom()
                                .asOWLSubClassOfAxioms()) {
                    OWLSubClassOfAxiom cloned = cloneWithFreshPlaceholders(
                            sub, owlMap, toAdd, clonedOriginals);
                    String freshKey = placeholderCounter
                            .generate(PlaceholderType.MODAL_PLACEHOLDER);
                    toAdd.put(freshKey, new NormalisedAxiom(
                            na.operator, na.standpoint,
                            StandpointAxiomType.CONCEPT_INCLUSION,
                            true, na.isNegatedInner,
                            cloned, null, na.manchester,
                            extractChildKeysFromAxiom(cloned)));
                }

                for (OWLSubClassOfAxiom sub :
                        du.getOWLDisjointClassesAxiom()
                                .asOWLSubClassOfAxioms()) {
                    OWLSubClassOfAxiom cloned = cloneWithFreshPlaceholders(
                            sub, owlMap, toAdd, clonedOriginals);
                    String freshKey = placeholderCounter
                            .generate(PlaceholderType.MODAL_PLACEHOLDER);
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

        // remove multi-axiom roots
        toRemove.forEach(owlMap::remove);
        // remove original SP_n that were cloned — they now have fresh copies
        clonedOriginals.forEach(owlMap::remove);
        // add all fresh entries
        owlMap.putAll(toAdd);
    }

    private OWLSubClassOfAxiom cloneWithFreshPlaceholders(
            OWLSubClassOfAxiom sub,
            Map<String, NormalisedAxiom> owlMap,
            Map<String, NormalisedAxiom> toAdd,
            Set<String> clonedOriginals) {

        OWLClassExpression subClass = cloneExpr(
                sub.getSubClass(), owlMap, toAdd, clonedOriginals);
        OWLClassExpression superClass = cloneExpr(
                sub.getSuperClass(), owlMap, toAdd, clonedOriginals);
        return helperDf.getOWLSubClassOfAxiom(subClass, superClass);
    }

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

                // copy original entry under new key
                NormalisedAxiom original = owlMap.get(oldKey);
                if (original == null) original = toAdd.get(oldKey);
                if (original != null) {
                    toAdd.put(newKey, new NormalisedAxiom(
                            original.operator, original.standpoint,
                            original.axiomType, original.isRoot,
                            original.isNegatedInner,
                            original.owlAxiom, original.owlTree,
                            original.manchester, original.childKeys));
                }

                // mark original as cloned — will be deleted
                clonedOriginals.add(oldKey);

                registerSPn(newKey);

                return helperDf.getOWLClass(IRI.create(
                        PlaceholderType.PLUGIN_NS + newKey));
            }
            return cls;
        }

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

    // ─── Rule (3): negated GCI ────────────────────────────────────────────────

//    private void applyNegatedGCI(Map<String, ModalPlaceholder> placeholderMap) {
//        PipelineLogger.log("\n=== STEP 4: RULE (3) — NEGATED GCI ===\n");
//
//        List<String> toRemove = new ArrayList<>();
//        for (Map.Entry<String, ModalPlaceholder> e :
//                new ArrayList<>(placeholderMap.entrySet())) {
//
//            ModalPlaceholder mp = e.getValue();
//            if (!mp.isNegated
//                    || mp.standpointAxiomType != StandpointAxiomType.CONCEPT_INCLUSION) continue;
//
//            NormalisedAxiom na = rawOwlMap.get(e.getKey());
//            if (na == null || !(na.owlAxiom instanceof OWLSubClassOfAxiom)) continue;
//
//            OWLSubClassOfAxiom gci = (OWLSubClassOfAxiom) na.owlAxiom;
//            OWLClassExpression C = gci.getSubClass();
//            OWLClassExpression D = gci.getSuperClass();
//
//            String fcName = placeholderCounter.generate(PlaceholderType.FRESH_CONCEPT);
//            String frName = placeholderCounter.generate(PlaceholderType.FRESH_ROLE);
//            OWLClass freshC = freshClass(fcName);
//            OWLObjectProperty freshR = freshRole(frName);
//
//            // FC ⊑ C  →  Thing ⊑ NNF(¬FC ⊔ C)
//            OWLAxiom ax1 = applyRule11(freshC, C);
//            // (FC ⊓ D) ⊑ ⊥  →  Thing ⊑ NNF(¬FC ⊔ ¬D)
//            OWLAxiom ax2 = applyRule11(
//                    helperDf.getOWLObjectIntersectionOf(freshC, D), helperDf.getOWLNothing());
//            // Thing ⊑ ∃FR.FC  (Thing ⊑ X is a no-op for Rule 11)
//            OWLAxiom ax3 = helperDf.getOWLSubClassOfAxiom(
//                    helperDf.getOWLThing(),
//                    helperDf.getOWLObjectSomeValuesFrom(freshR, freshC));
//
//            String k1 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
//            String k2 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
//            String k3 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
//            rawOwlMap.put(k1, owlRootEntry(mp.operator, mp.standpoint,
//                    StandpointAxiomType.CONCEPT_INCLUSION, ax1));
//            rawOwlMap.put(k2, owlRootEntry(mp.operator, mp.standpoint,
//                    StandpointAxiomType.CONCEPT_INCLUSION, ax2));
//            rawOwlMap.put(k3, owlRootEntry(mp.operator, mp.standpoint,
//                    StandpointAxiomType.CONCEPT_INCLUSION, ax3));
//
//            rawOwlMap.remove(e.getKey());
//            toRemove.add(e.getKey());
//
//            PipelineLogger.log("Rule (3) on " + e.getKey() + ":");
//            PipelineLogger.log("  → " + k1 + ": " + ax1);
//            PipelineLogger.log("  → " + k2 + ": " + ax2);
//            PipelineLogger.log("  → " + k3 + ": " + ax3);
//        }
//        toRemove.forEach(placeholderMap::remove);
//    }

    // ─── Rules (4)+(10): concept assertions ──────────────────────────────────

//    private void applyConceptAssertions(Map<String, ModalPlaceholder> placeholderMap) {
//        PipelineLogger.log("\n=== STEP 5: RULES (4)(10) — ASSERTIONS ===\n");
//
//        for (Map.Entry<String, ModalPlaceholder> e :
//                new ArrayList<>(placeholderMap.entrySet())) {
//
//            ModalPlaceholder mp = e.getValue();
//            if (mp.standpointAxiomType != StandpointAxiomType.CONCEPT_ASSERTION) continue;
//
//            NormalisedAxiom na = rawOwlMap.get(e.getKey());
//            if (na == null || !(na.owlAxiom instanceof OWLClassAssertionAxiom)) continue;
//
//            OWLClassAssertionAxiom ca = (OWLClassAssertionAxiom) na.owlAxiom;
//            OWLClassExpression concept = ca.getClassExpression();
//            OWLNamedIndividual ind = (OWLNamedIndividual) ca.getIndividual();
//
//            if (mp.isNegated)
//                concept = helperDf.getOWLObjectComplementOf(concept);
//
//            OWLClassExpression nnf = concept.getNNF();
//            OWLAxiom newAxiom = helperDf.getOWLClassAssertionAxiom(nnf, ind);
//
//            rawOwlMap.put(e.getKey(), new NormalisedAxiom(
//                    na.operator, na.standpoint,
//                    StandpointAxiomType.CONCEPT_ASSERTION, true,
//                    newAxiom, null, na.manchester,
//                    extractChildKeysFromAxiom(newAxiom)));
//
//            mp.isNegated = false;
//            mp.standpointAxiomType = StandpointAxiomType.CONCEPT_ASSERTION;
//            mp.isRoot = true;
//
//            PipelineLogger.log("Rule (4)+(10) on " + e.getKey() + ": " + ca + " → " + newAxiom);
//        }
//    }

    // ─── Rule (6): negated role inclusion ────────────────────────────────────

//    private void applyNegatedRoleInclusion(Map<String, ModalPlaceholder> placeholderMap) {
//        PipelineLogger.log("\n=== STEP 6: RULE (6) — NEGATED ROLE INCLUSION ===\n");
//
//        List<String> toRemove = new ArrayList<>();
//        for (Map.Entry<String, ModalPlaceholder> e :
//                new ArrayList<>(placeholderMap.entrySet())) {
//
//            ModalPlaceholder mp = e.getValue();
//            if (!mp.isNegated
//                    || mp.standpointAxiomType != StandpointAxiomType.ROLE_INCLUSION) continue;
//
//            NormalisedAxiom na = rawOwlMap.get(e.getKey());
//            if (na == null || !(na.owlAxiom instanceof OWLSubObjectPropertyOfAxiom)) continue;
//
//            OWLSubObjectPropertyOfAxiom ri = (OWLSubObjectPropertyOfAxiom) na.owlAxiom;
//            OWLObjectProperty S = (OWLObjectProperty) ri.getSubProperty();
//            OWLObjectProperty R = (OWLObjectProperty) ri.getSuperProperty();
//
//            String fcaName = placeholderCounter.generate(PlaceholderType.FRESH_CONCEPT);
//            String fcbName = placeholderCounter.generate(PlaceholderType.FRESH_CONCEPT);
//            String frName  = placeholderCounter.generate(PlaceholderType.FRESH_ROLE);
//            OWLClass freshCa = freshClass(fcaName);
//            OWLClass freshCb = freshClass(fcbName);
//            OWLObjectProperty freshRProp = freshRole(frName);
//
//            // Thing ⊑ ∃FR.FCa
//            OWLAxiom ax1 = helperDf.getOWLSubClassOfAxiom(
//                    helperDf.getOWLThing(),
//                    helperDf.getOWLObjectSomeValuesFrom(freshRProp, freshCa));
//            // (FCa ⊓ ∃R.FCb) ⊑ ⊥  →  Rule (11)
//            OWLAxiom ax2 = applyRule11(
//                    helperDf.getOWLObjectIntersectionOf(
//                            freshCa, helperDf.getOWLObjectSomeValuesFrom(R, freshCb)),
//                    helperDf.getOWLNothing());
//            // FCa ⊑ ∃S.FCb  →  Rule (11)
//            OWLAxiom ax3 = applyRule11(
//                    freshCa, helperDf.getOWLObjectSomeValuesFrom(S, freshCb));
//
//            String k1 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
//            String k2 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
//            String k3 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
//            rawOwlMap.put(k1, owlRootEntry(mp.operator, mp.standpoint,
//                    StandpointAxiomType.CONCEPT_INCLUSION, ax1));
//            rawOwlMap.put(k2, owlRootEntry(mp.operator, mp.standpoint,
//                    StandpointAxiomType.CONCEPT_INCLUSION, ax2));
//            rawOwlMap.put(k3, owlRootEntry(mp.operator, mp.standpoint,
//                    StandpointAxiomType.CONCEPT_INCLUSION, ax3));
//
//            rawOwlMap.remove(e.getKey());
//            toRemove.add(e.getKey());
//
//            PipelineLogger.log("Rule (6) on " + e.getKey() + " [" + S + " ⊑ " + R + "]:");
//            PipelineLogger.log("  → " + k1 + ": " + ax1);
//            PipelineLogger.log("  → " + k2 + ": " + ax2);
//            PipelineLogger.log("  → " + k3 + ": " + ax3);
//        }
//        toRemove.forEach(placeholderMap::remove);
//    }

    // ─── Rule (5): negated role assertion ────────────────────────────────────

//    private void applyNegatedRoleAssertion(Map<String, ModalPlaceholder> placeholderMap) {
//        PipelineLogger.log("\n=== STEP 7: RULE (5) — NEGATED ROLE ASSERTION ===\n");
//
//        List<String> toRemove = new ArrayList<>();
//        for (Map.Entry<String, ModalPlaceholder> e :
//                new ArrayList<>(placeholderMap.entrySet())) {
//
//            ModalPlaceholder mp = e.getValue();
//            if (!mp.isNegated
//                    || mp.standpointAxiomType != StandpointAxiomType.ROLE_ASSERTION) continue;
//
//            NormalisedAxiom na = rawOwlMap.get(e.getKey());
//            if (na == null || !(na.owlAxiom instanceof OWLObjectPropertyAssertionAxiom)) continue;
//
//            OWLObjectPropertyAssertionAxiom ra = (OWLObjectPropertyAssertionAxiom) na.owlAxiom;
//            OWLObjectProperty role = (OWLObjectProperty) ra.getProperty();
//            OWLNamedIndividual a = (OWLNamedIndividual) ra.getSubject();
//            OWLNamedIndividual b = (OWLNamedIndividual) ra.getObject();
//
//            String fcaName = placeholderCounter.generate(PlaceholderType.FRESH_CONCEPT);
//            String fcbName = placeholderCounter.generate(PlaceholderType.FRESH_CONCEPT);
//            OWLClass freshCa = freshClass(fcaName);
//            OWLClass freshCb = freshClass(fcbName);
//
//            OWLAxiom ax1 = helperDf.getOWLClassAssertionAxiom(freshCa, a);
//            OWLAxiom ax2 = helperDf.getOWLClassAssertionAxiom(freshCb, b);
//            OWLAxiom ax3 = applyRule11(
//                    helperDf.getOWLObjectIntersectionOf(
//                            freshCa, helperDf.getOWLObjectSomeValuesFrom(role, freshCb)),
//                    helperDf.getOWLNothing());
//
//            String k1 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
//            String k2 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
//            String k3 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
//            rawOwlMap.put(k1, owlRootEntry(mp.operator, mp.standpoint,
//                    StandpointAxiomType.CONCEPT_ASSERTION, ax1));
//            rawOwlMap.put(k2, owlRootEntry(mp.operator, mp.standpoint,
//                    StandpointAxiomType.CONCEPT_ASSERTION, ax2));
//            rawOwlMap.put(k3, owlRootEntry(mp.operator, mp.standpoint,
//                    StandpointAxiomType.CONCEPT_INCLUSION, ax3));
//
//            rawOwlMap.remove(e.getKey());
//            toRemove.add(e.getKey());
//
//            PipelineLogger.log("Rule (5) on " + e.getKey()
//                    + " [" + a + " " + role + " " + b + "]:");
//            PipelineLogger.log("  → " + k1 + ": " + ax1);
//            PipelineLogger.log("  → " + k2 + ": " + ax2);
//            PipelineLogger.log("  → " + k3 + ": " + ax3);
//        }
//        toRemove.forEach(placeholderMap::remove);
//    }

    // ─── Rule (7): negated transitivity ──────────────────────────────────────

//    private void applyNegatedTransitivity(Map<String, ModalPlaceholder> placeholderMap) {
//        PipelineLogger.log("\n=== STEP 8: RULE (7) — NEGATED TRANSITIVITY ===\n");
//
//        List<String> toRemove = new ArrayList<>();
//        for (Map.Entry<String, ModalPlaceholder> e :
//                new ArrayList<>(placeholderMap.entrySet())) {
//
//            ModalPlaceholder mp = e.getValue();
//            if (!mp.isNegated
//                    || mp.standpointAxiomType != StandpointAxiomType.ROLE_TRANSITIVITY) continue;
//
//            NormalisedAxiom na = rawOwlMap.get(e.getKey());
//            if (na == null || !(na.owlAxiom instanceof OWLTransitiveObjectPropertyAxiom)) continue;
//
//            OWLTransitiveObjectPropertyAxiom tra =
//                    (OWLTransitiveObjectPropertyAxiom) na.owlAxiom;
//            OWLObjectProperty role = (OWLObjectProperty) tra.getProperty();
//
//            String fcaName = placeholderCounter.generate(PlaceholderType.FRESH_CONCEPT);
//            String fcbName = placeholderCounter.generate(PlaceholderType.FRESH_CONCEPT);
//            String frName  = placeholderCounter.generate(PlaceholderType.FRESH_ROLE);
//            OWLClass freshCa = freshClass(fcaName);
//            OWLClass freshCb = freshClass(fcbName);
//            OWLObjectProperty freshRProp = freshRole(frName);
//
//            // Thing ⊑ ∃FR.FCa
//            OWLAxiom ax1 = helperDf.getOWLSubClassOfAxiom(
//                    helperDf.getOWLThing(),
//                    helperDf.getOWLObjectSomeValuesFrom(freshRProp, freshCa));
//            // (FCa ⊓ ∃role.FCb) ⊑ ⊥  →  Rule (11)
//            OWLAxiom ax2 = applyRule11(
//                    helperDf.getOWLObjectIntersectionOf(
//                            freshCa, helperDf.getOWLObjectSomeValuesFrom(role, freshCb)),
//                    helperDf.getOWLNothing());
//            // FCa ⊑ ∃role.∃role.FCb  →  Rule (11)
//            OWLAxiom ax3 = applyRule11(
//                    freshCa,
//                    helperDf.getOWLObjectSomeValuesFrom(
//                            role, helperDf.getOWLObjectSomeValuesFrom(role, freshCb)));
//
//            String k1 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
//            String k2 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
//            String k3 = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
//            rawOwlMap.put(k1, owlRootEntry(mp.operator, mp.standpoint,
//                    StandpointAxiomType.CONCEPT_INCLUSION, ax1));
//            rawOwlMap.put(k2, owlRootEntry(mp.operator, mp.standpoint,
//                    StandpointAxiomType.CONCEPT_INCLUSION, ax2));
//            rawOwlMap.put(k3, owlRootEntry(mp.operator, mp.standpoint,
//                    StandpointAxiomType.CONCEPT_INCLUSION, ax3));
//
//            rawOwlMap.remove(e.getKey());
//            toRemove.add(e.getKey());
//
//            PipelineLogger.log("Rule (7) on " + e.getKey() + " [Tra(" + role + ")]:");
//            PipelineLogger.log("  → " + k1 + ": " + ax1);
//            PipelineLogger.log("  → " + k2 + ": " + ax2);
//            PipelineLogger.log("  → " + k3 + ": " + ax3);
//        }
//        toRemove.forEach(placeholderMap::remove);
//    }

    // ─── Rule (8): negated sharpenings ───────────────────────────────────────

    private void applyNegatedSharpenings(List<Sharpening> loadedSharpenings,
                                         List<Sharpening> sharpenings) {
        PipelineLogger.log("\n=== STEP 9: RULE (8) — NEGATED SHARPENING ===\n");

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
    }

    // ─── Rule (9): zero sharpenings ──────────────────────────────────────────

//    private void applyZeroSharpenings(List<Sharpening> sharpenings,
//                                      List<Sharpening> loadedSharpenings) {
//        PipelineLogger.log("\n=== STEP 10: RULE (9) — ZERO SHARPENING ===\n");
//
//        List<Sharpening> zeroSharpenings = new ArrayList<>();
//        for (Sharpening s : sharpenings) {
//            if (s.isZero()) zeroSharpenings.add(s);
//        }
//        sharpenings.removeAll(zeroSharpenings);
//        for (Sharpening s : loadedSharpenings) {
//            if (!s.isNegated && s.isZero()) zeroSharpenings.add(s);
//        }
//
//        for (Sharpening zero : zeroSharpenings) {
//            if (zero.lhsStandpoints.contains("0")) {
//                PipelineLogger.log("Rule (9) skipped (trivial 0 ⪯ 0): " + zero);
//                continue;
//            }
//
//            PipelineLogger.log("Rule (9) on: " + zero);
//            List<OWLClass> freshConcepts = new ArrayList<>();
//
//            for (String si : zero.lhsStandpoints) {
//                String freshCiName = placeholderCounter.generate(PlaceholderType.FRESH_CONCEPT);;
//                OWLClass freshCi = freshClass(freshCiName);
//                freshConcepts.add(freshCi);
//
//                // Thing ⊑ FC_i — Rule (11) is identity since subClass = Thing
//                OWLAxiom axiom = helperDf.getOWLSubClassOfAxiom(helperDf.getOWLThing(), freshCi);
//                String key = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
//                rawOwlMap.put(key, owlRootEntry(Operator.BOX, si,
//                        StandpointAxiomType.CONCEPT_INCLUSION, axiom));
//                PipelineLogger.log("  → " + key + ": □_" + si + "[⊤ ⊑ " + freshCiName + "]");
//            }
//
//            // (FC_1 ⊓ ... ⊓ FC_n) ⊑ ⊥  →  Rule (11)
//            OWLClassExpression intersection = freshConcepts.size() == 1
//                    ? freshConcepts.get(0)
//                    : helperDf.getOWLObjectIntersectionOf(new HashSet<>(freshConcepts));
//            OWLAxiom globalAxiom = applyRule11(intersection, helperDf.getOWLNothing());
//            String key = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
//            rawOwlMap.put(key, owlRootEntry(Operator.BOX, "*",
//                    StandpointAxiomType.CONCEPT_INCLUSION, globalAxiom));
//            PipelineLogger.log("  → " + key + ": □_*[" + freshConcepts + " ⊑ ⊥]");
//        }
//    }

    private void collectNormalSharpenings(List<Sharpening> loadedSharpenings,
                                          List<Sharpening> sharpenings) {
        for (Sharpening s : loadedSharpenings) {
            if (!s.isNegated && !s.isZero()) {
                sharpenings.add(s);
                PipelineLogger.log("Normal sharpening added: " + s);
            }
        }
    }

    // ─── Final OWL normalisations (NNF + duality) ────────────────────────────

//    private void applyFinalOWLNormalisations() {
//        PipelineLogger.log("\n=== STEP 11: FINAL OWL NNF + DUALITY ===\n");
//
//        // Single NNF pass on all concept expression entries
//        for (Map.Entry<String, NormalisedAxiom> e : new ArrayList<>(rawOwlMap.entrySet())) {
//            NormalisedAxiom na = e.getValue();
//            if (na.owlTree == null) continue;
//            OWLClassExpression nnf = na.owlTree.getNNF();
//            if (!nnf.equals(na.owlTree)) {
//                rawOwlMap.put(e.getKey(), new NormalisedAxiom(
//                        na.operator, na.standpoint, na.axiomType, na.isRoot,
//                        null, nnf, na.manchester, extractChildKeysFromExpr(nnf)));
//                PipelineLogger.log("  NNF: " + e.getKey() + " " + na.owlTree + " → " + nnf);
//            }
//        }
//
//        // Recursive duality pass — descends into union/intersection/restriction fillers
//        for (Map.Entry<String, NormalisedAxiom> e : new ArrayList<>(rawOwlMap.entrySet())) {
//            NormalisedAxiom na = e.getValue();
//            if (na.owlTree == null) continue;
//            OWLClassExpression resolved = applyDuality(na.owlTree, rawOwlMap);
//            if (resolved != na.owlTree) {
//                rawOwlMap.put(e.getKey(), new NormalisedAxiom(
//                        na.operator, na.standpoint, na.axiomType, na.isRoot,
//                        null, resolved, na.manchester, extractChildKeysFromExpr(resolved)));
//                PipelineLogger.log("  Duality: " + e.getKey() + " " + na.owlTree + " → " + resolved);
//            }
//        }
//
//        // Duality pass on root axioms — not(SP_n) can appear inside the superclass
//        // of a SubClassOf axiom or the class expression of a ClassAssertion
//        for (Map.Entry<String, NormalisedAxiom> e : new ArrayList<>(rawOwlMap.entrySet())) {
//            NormalisedAxiom na = e.getValue();
//            if (!na.isRoot || na.owlAxiom == null) continue;
//            if (na.owlAxiom instanceof OWLSubClassOfAxiom) {
//                OWLSubClassOfAxiom gci = (OWLSubClassOfAxiom) na.owlAxiom;
//                OWLClassExpression resolved = applyDuality(gci.getSuperClass(), rawOwlMap);
//                if (resolved != gci.getSuperClass()) {
//                    OWLAxiom newAxiom = helperDf.getOWLSubClassOfAxiom(gci.getSubClass(), resolved);
//                    rawOwlMap.put(e.getKey(), new NormalisedAxiom(
//                            na.operator, na.standpoint, na.axiomType, true,
//                            newAxiom, null, na.manchester, extractChildKeysFromAxiom(newAxiom)));
//                    PipelineLogger.log("  Duality (root): " + e.getKey() + " → " + resolved);
//                }
//            } else if (na.owlAxiom instanceof OWLClassAssertionAxiom) {
//                OWLClassAssertionAxiom ca = (OWLClassAssertionAxiom) na.owlAxiom;
//                OWLClassExpression resolved = applyDuality(ca.getClassExpression(), rawOwlMap);
//                if (resolved != ca.getClassExpression()) {
//                    OWLAxiom newAxiom = helperDf.getOWLClassAssertionAxiom(
//                            resolved, (OWLNamedIndividual) ca.getIndividual());
//                    rawOwlMap.put(e.getKey(), new NormalisedAxiom(
//                            na.operator, na.standpoint, na.axiomType, true,
//                            newAxiom, null, na.manchester, extractChildKeysFromAxiom(newAxiom)));
//                    PipelineLogger.log("  Duality (root): " + e.getKey() + " → " + resolved);
//                }
//            }
//        }
//
//        // Verification: warn if any ObjectComplementOf(SP_...) still remains
//        for (Map.Entry<String, NormalisedAxiom> e : rawOwlMap.entrySet()) {
//            NormalisedAxiom na = e.getValue();
//            if (na.owlTree != null && containsPlaceholderComplement(na.owlTree))
//                System.out.println("WARNING: unresolved ObjectComplementOf(SP_...) in "
//                        + e.getKey() + " owlTree: " + na.owlTree);
//            if (na.owlAxiom instanceof OWLSubClassOfAxiom) {
//                OWLClassExpression sup = ((OWLSubClassOfAxiom) na.owlAxiom).getSuperClass();
//                if (containsPlaceholderComplement(sup))
//                    System.out.println("WARNING: unresolved ObjectComplementOf(SP_...) in "
//                            + e.getKey() + " root axiom: " + na.owlAxiom);
//            }
//            if (na.owlAxiom instanceof OWLClassAssertionAxiom) {
//                OWLClassExpression ce = ((OWLClassAssertionAxiom) na.owlAxiom).getClassExpression();
//                if (containsPlaceholderComplement(ce))
//                    System.out.println("WARNING: unresolved ObjectComplementOf(SP_...) in "
//                            + e.getKey() + " root axiom: " + na.owlAxiom);
//            }
//        }
//
//        PipelineLogger.log("Final OWL normalisations complete.");
//    }

    // Recursively resolves not(SP_x) patterns anywhere inside an expression tree.
    // At each OWLObjectComplementOf(SP_x) leaf: flips SP_x's operator in owlMap,
    // eliminates the complement wrapper, and returns the bare SP_x class.
    // Descends into union, intersection, restriction fillers for nested patterns.
//    private OWLClassExpression applyDuality(OWLClassExpression expr,
//                                            Map<String, NormalisedAxiom> owlMap) {
//        if (expr instanceof OWLObjectComplementOf) {
//            OWLObjectComplementOf comp = (OWLObjectComplementOf) expr;
//            OWLClassExpression inner = comp.getOperand();
//            if (inner instanceof OWLClass
//                    && PlaceholderType.isModalPlaceholder((OWLClass) inner)) {
//                OWLClass spClass = (OWLClass) inner;
//                String spKey = spClass.getIRI().getShortForm();
//                NormalisedAxiom target = owlMap.get(spKey);
//                if (target != null && target.owlTree != null) {
//                    Operator dualOp = target.operator == Operator.BOX
//                            ? Operator.DIAMOND : Operator.BOX;
//                    OWLClassExpression rawDualTree =
//                            target.owlTree instanceof OWLObjectComplementOf
//                                    ? ((OWLObjectComplementOf) target.owlTree).getOperand()
//                                    : helperDf.getOWLObjectComplementOf(target.owlTree);
//                    // NNF pushes the new negation to leaves, then recurse to resolve
//                    // any nested not(SP_m) patterns that NNF may expose
//                    OWLClassExpression dualTree = applyDuality(rawDualTree.getNNF(), owlMap);
//                    owlMap.put(spKey, new NormalisedAxiom(
//                            dualOp, target.standpoint, target.axiomType, target.isRoot,
//                            target.owlAxiom, dualTree, target.manchester, target.childKeys));
//                    PipelineLogger.log("  Duality: not(" + spKey + ") → " + dualOp
//                            + "[" + spKey + "]");
//                }
//                return spClass;
//            }
//            OWLClassExpression newInner = applyDuality(inner, owlMap);
//            return newInner != inner
//                    ? helperDf.getOWLObjectComplementOf(newInner) : expr;
//        }
//
//        if (expr instanceof OWLObjectUnionOf) {
//            Set<OWLClassExpression> ops = ((OWLObjectUnionOf) expr).getOperands();
//            List<OWLClassExpression> newOps = new ArrayList<>();
//            boolean changed = false;
//            for (OWLClassExpression op : ops) {
//                OWLClassExpression r = applyDuality(op, owlMap);
//                newOps.add(r);
//                if (r != op) changed = true;
//            }
//            return changed ? helperDf.getOWLObjectUnionOf(new HashSet<>(newOps)) : expr;
//        }
//
//        if (expr instanceof OWLObjectIntersectionOf) {
//            Set<OWLClassExpression> ops = ((OWLObjectIntersectionOf) expr).getOperands();
//            List<OWLClassExpression> newOps = new ArrayList<>();
//            boolean changed = false;
//            for (OWLClassExpression op : ops) {
//                OWLClassExpression r = applyDuality(op, owlMap);
//                newOps.add(r);
//                if (r != op) changed = true;
//            }
//            return changed ? helperDf.getOWLObjectIntersectionOf(new HashSet<>(newOps)) : expr;
//        }
//
//        if (expr instanceof OWLObjectSomeValuesFrom) {
//            OWLObjectSomeValuesFrom some = (OWLObjectSomeValuesFrom) expr;
//            OWLClassExpression filler = some.getFiller();
//            OWLClassExpression newFiller = applyDuality(filler, owlMap);
//            return newFiller != filler
//                    ? helperDf.getOWLObjectSomeValuesFrom(some.getProperty(), newFiller) : expr;
//        }
//
//        if (expr instanceof OWLObjectAllValuesFrom) {
//            OWLObjectAllValuesFrom all = (OWLObjectAllValuesFrom) expr;
//            OWLClassExpression filler = all.getFiller();
//            OWLClassExpression newFiller = applyDuality(filler, owlMap);
//            return newFiller != filler
//                    ? helperDf.getOWLObjectAllValuesFrom(all.getProperty(), newFiller) : expr;
//        }
//
//        return expr;
//    }

    private boolean containsPlaceholderComplement(OWLClassExpression expr) {
        if (expr instanceof OWLObjectComplementOf) {
            OWLClassExpression inner = ((OWLObjectComplementOf) expr).getOperand();
            if (inner instanceof OWLClass
                    && PlaceholderType.isModalPlaceholder((OWLClass) inner)) return true;
            return containsPlaceholderComplement(inner);
        }
        if (expr instanceof OWLObjectUnionOf) {
            for (OWLClassExpression op : ((OWLObjectUnionOf) expr).getOperands())
                if (containsPlaceholderComplement(op)) return true;
        }
        if (expr instanceof OWLObjectIntersectionOf) {
            for (OWLClassExpression op : ((OWLObjectIntersectionOf) expr).getOperands())
                if (containsPlaceholderComplement(op)) return true;
        }
        if (expr instanceof OWLObjectSomeValuesFrom)
            return containsPlaceholderComplement(((OWLObjectSomeValuesFrom) expr).getFiller());
        if (expr instanceof OWLObjectAllValuesFrom)
            return containsPlaceholderComplement(((OWLObjectAllValuesFrom) expr).getFiller());
        return false;
    }

    // ─── OWL helpers ─────────────────────────────────────────────────────────

    private Set<OWLSubClassOfAxiom> expandToSubClassOf(OWLAxiom axiom) {
        if (axiom instanceof OWLEquivalentClassesAxiom)
            return ((OWLEquivalentClassesAxiom) axiom).asOWLSubClassOfAxioms();
        if (axiom instanceof OWLDisjointClassesAxiom)
            return ((OWLDisjointClassesAxiom) axiom).asOWLSubClassOfAxioms();
        if (axiom instanceof OWLDisjointUnionAxiom) {
            OWLDisjointUnionAxiom du = (OWLDisjointUnionAxiom) axiom;
            Set<OWLSubClassOfAxiom> result = new HashSet<>();
            result.addAll(du.getOWLEquivalentClassesAxiom().asOWLSubClassOfAxioms());
            result.addAll(du.getOWLDisjointClassesAxiom().asOWLSubClassOfAxioms());
            return result;
        }
        return Collections.emptySet();
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

//    private NormalisedAxiom owlRootEntry(Operator op, String standpoint,
//                                         StandpointAxiomType type, OWLAxiom axiom) {
//        return new NormalisedAxiom(op, standpoint, type, true,
//                axiom, null, axiom.toString(), extractChildKeysFromAxiom(axiom));
//    }

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

    private void registerAxiomEntitiesInHelper(OWLAxiom axiom) {
        axiom.getClassesInSignature().forEach(c ->
                helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(c)));
        axiom.getObjectPropertiesInSignature().forEach(p ->
                helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(p)));
        axiom.getIndividualsInSignature().forEach(i ->
                helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(i)));
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

        return new NormalisedAxiom(mp.operator, mp.standpoint, type, mp.isRoot,  mp.isNegatedInner,
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

    private void logExpandedFormulas(List<AxiomWithLabel> expandedAxioms) {
        PipelineLogger.log("\n=== EXPANDED FORMULAS (XML) ===\n");
        expandedAxioms.forEach(a -> PipelineLogger.log(a.standpointLabel));
        PipelineLogger.log("");
        PipelineLogger.log("\n=== EXPANDED FORMULAS (READABLE) ===\n");
        expandedAxioms.forEach(a -> PipelineLogger.log(formatFormula(a.standpointLabel)));
        PipelineLogger.log("");
    }

    private void printResults(Map<String, ModalPlaceholder> placeholderMap,
                              List<Sharpening> sharpenings) {
        PipelineLogger.log("\n=== FULL NORMALISED PLACEHOLDER MAP ===\n");
        placeholderMap.forEach((k, v) -> PipelineLogger.log(k + " → " + v));
        PipelineLogger.log("\n=== SHARPENINGS ===\n");
        if (sharpenings.isEmpty()) PipelineLogger.log("(none)");
        else sharpenings.forEach(s -> PipelineLogger.log(s.toString()));
        PipelineLogger.log("\n======================================\n\n");
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
        if (ax instanceof OWLSubClassOfAxiom)              return StandpointAxiomType.CONCEPT_INCLUSION;
        if (ax instanceof OWLEquivalentClassesAxiom)       return StandpointAxiomType.CONCEPT_EQUIVALENCE;
        if (ax instanceof OWLDisjointClassesAxiom)         return StandpointAxiomType.CONCEPT_DISJOINT;
        if (ax instanceof OWLDisjointUnionAxiom)           return StandpointAxiomType.CONCEPT_DISJOINT_UNION;
        if (ax instanceof OWLClassAssertionAxiom)          return CONCEPT_ASSERTION;
        if (ax instanceof OWLSubObjectPropertyOfAxiom)     return StandpointAxiomType.ROLE_INCLUSION;
        if (ax instanceof OWLObjectPropertyAssertionAxiom) return StandpointAxiomType.ROLE_ASSERTION;
        if (ax instanceof OWLTransitiveObjectPropertyAxiom) return StandpointAxiomType.ROLE_TRANSITIVITY;
        return null;
    }
}
