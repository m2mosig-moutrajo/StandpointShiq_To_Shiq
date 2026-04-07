package org.standpoint.plugin.pipeline;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.loader.OntologyLoader;
import org.standpoint.plugin.normalisation.PlaceholderRestorer;
import org.standpoint.plugin.normalisation.StandpointNormaliser;
import org.standpoint.plugin.parser.FormulaParser;
import org.standpoint.plugin.parser.PlaceholderSubstituter;
import org.standpoint.plugin.parser.PlaceholderSubstituter.Operator;
import org.standpoint.plugin.parser.PlaceholderUtil;
import org.standpoint.plugin.translation.SharpeningStatement;
import org.standpoint.plugin.util.PipelineLogger;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;

public class StandpointPipeline {

    private final OWLOntology ontology;

    public StandpointPipeline(OWLOntology ontology) {
        this.ontology = ontology;
    }

    public StandpointPipeline(OWLOntology ontology, PipelineLogger.Level logLevel) {
        this.ontology = ontology;
        PipelineLogger.setLevel(logLevel);
    }

    public PipelineResult run() throws Exception {
        // Step 1 — load axiom labels map (id → AxiomWithLabel)
        Map<String, OntologyLoader.AxiomWithLabel> axiomLabelMap =
                OntologyLoader.loadAxiomLabels(ontology);

        // Step 1.1 — load formulas
        List<FormulaParser.ParsedFormula> formulas =
                OntologyLoader.loadFormulas(ontology);

        // Step 1.2 — load sharpenings
        List<SharpeningStatement> loadedSharpenings =
                OntologyLoader.loadSharpenings(ontology);

        PipelineLogger.log("\n=== LOADED SHARPENINGS ===\n");
        if (loadedSharpenings.isEmpty()) {
            PipelineLogger.log("(none)");
        } else {
            for (SharpeningStatement s : loadedSharpenings) {
                PipelineLogger.log(s.toString());
            }
        }
        PipelineLogger.log("");

        if (formulas.isEmpty() && loadedSharpenings.isEmpty()) return null;

        // Print original formulas before expansion
        PipelineLogger.log("\n=== ORIGINAL FORMULAS ===\n");
        for (FormulaParser.ParsedFormula formula : formulas) {
            StringBuilder sb = new StringBuilder();
            String symbol = "box".equals(formula.operator) ? "□" : "◇";
            sb.append(symbol).append("_").append(formula.standpoint).append("[");
            if (formula.literals.size() == 1) {
                FormulaParser.ParsedLiteral lit = formula.literals.get(0);
                if (lit.negated) sb.append("¬");
                sb.append(lit.ref);
            } else {
                for (int i = 0; i < formula.literals.size(); i++) {
                    FormulaParser.ParsedLiteral lit = formula.literals.get(i);
                    if (lit.negated) sb.append("¬");
                    sb.append(lit.ref);
                    if (i < formula.literals.size() - 1) sb.append(" ∧ ");
                }
            }
            sb.append("]");
            PipelineLogger.log(sb.toString());
        }
        PipelineLogger.log("");

        // Print axiom labels map
        PipelineLogger.log("\n=== AXIOM LABELS (XML) ===\n");
        for (Map.Entry<String, OntologyLoader.AxiomWithLabel> e : axiomLabelMap.entrySet()) {
            PipelineLogger.log(e.getKey() + " → " + e.getValue().standpointLabels.get(0));
        }
        PipelineLogger.log("");

        PipelineLogger.log("\n=== AXIOM LABELS (READABLE) ===\n");
        for (Map.Entry<String, OntologyLoader.AxiomWithLabel> e : axiomLabelMap.entrySet()) {
            PipelineLogger.log(e.getKey() + " → " + formatAxiomLabel(
                    e.getValue().standpointLabels.get(0)));
        }
        PipelineLogger.log("");

        // Step 1.3 — expand formulas into AxiomWithLabel list
        // Apply Rule (1) here for diamond formulas
        List<SharpeningStatement> sharpenings = new ArrayList<>();
        List<OntologyLoader.AxiomWithLabel> axiomsWithLabels = new ArrayList<>();

        for (FormulaParser.ParsedFormula formula : formulas) {

            String operator   = formula.operator;
            String standpoint = formula.standpoint;

            // Rule (1): diamond → fresh standpoint + box
            if ("diamond".equals(operator)) {
                String freshStandpoint = "FS_" + PlaceholderUtil.generateWithoutPrefix();
                sharpenings.add(new SharpeningStatement(
                        Collections.singletonList(freshStandpoint), standpoint));
                PipelineLogger.log("Rule (1) applied on formula ◇_" + standpoint
                        + " → fresh standpoint: " + freshStandpoint + " ⪯ " + standpoint);
                operator   = "box";
                standpoint = freshStandpoint;
            }

            for (FormulaParser.ParsedLiteral literal : formula.literals) {
                OntologyLoader.AxiomWithLabel axiomWithLabel =
                        axiomLabelMap.get(literal.ref);
                if (axiomWithLabel == null) {
                    PipelineLogger.result("WARNING: axiom ref '" + literal.ref + "' not found!");
                    continue;
                }

                String innerContent = extractInnerContent(
                        axiomWithLabel.standpointLabels.get(0));

                String wrappedLabel;
                if (literal.negated) {
                    wrappedLabel = "<modal op=\"" + operator
                            + "\" standpoint=\"" + standpoint
                            + "\" negatedInner=\"true\">" + innerContent + "</modal>";
                } else {
                    wrappedLabel = "<modal op=\"" + operator
                            + "\" standpoint=\"" + standpoint
                            + "\">" + innerContent + "</modal>";
                }

                axiomsWithLabels.add(new OntologyLoader.AxiomWithLabel(
                        axiomWithLabel.axiom,
                        Collections.singletonList(wrappedLabel),
                        axiomWithLabel.axiomType));
            }
        }

        // Print expanded formulas
        PipelineLogger.log("\n=== EXPANDED FORMULAS (XML) ===\n");
        for (OntologyLoader.AxiomWithLabel axiomWithLabel : axiomsWithLabels) {
            PipelineLogger.log(axiomWithLabel.standpointLabels.get(0));
        }
        PipelineLogger.log("");

        PipelineLogger.log("\n=== EXPANDED FORMULAS (READABLE) ===\n");
        for (OntologyLoader.AxiomWithLabel axiomWithLabel : axiomsWithLabels) {
            PipelineLogger.log(formatFormula(axiomWithLabel.standpointLabels.get(0)));
        }
        PipelineLogger.log("");

        // Step 2 — setup helper ontology for Manchester parsing
        OWLOntologyManager helperManager = OWLManager.createOWLOntologyManager();
        OWLDataFactory helperDf = helperManager.getOWLDataFactory();
        OWLOntology helperOntology = helperManager.createOntology(
                IRI.create("http://standpoint.org/helper"));
        helperManager.addAxiom(helperOntology,
                helperDf.getOWLDeclarationAxiom(helperDf.getOWLNothing()));
        helperManager.addAxiom(helperOntology,
                helperDf.getOWLDeclarationAxiom(helperDf.getOWLThing()));

        StandpointNormaliser standpointNormaliser =
                new StandpointNormaliser(helperDf, helperManager, helperOntology);

        // Step 3 — substitute and normalise each axiom
        Map<String, PlaceholderSubstituter.PlaceholderEntry> normalisedPlaceholderMap =
                new LinkedHashMap<>();

        PipelineLogger.log("\n=== STEP 3: SUBSTITUTION + NORMALISATION ===\n");

        for (OntologyLoader.AxiomWithLabel axiomWithLabel : axiomsWithLabels) {
            for (String standpointLabel : axiomWithLabel.standpointLabels) {

                PipelineLogger.log("Processing: " + standpointLabel);

                PlaceholderSubstituter substituter = new PlaceholderSubstituter();
                String rootPlaceholderKey = substituter.substitute(standpointLabel);
                Map<String, PlaceholderSubstituter.PlaceholderEntry> placeholderMap =
                        substituter.getMap();

                registerAxiomEntitiesInHelper(placeholderMap, axiomWithLabel.axiom,
                        helperManager, helperOntology, helperDf);

                PlaceholderSubstituter.PlaceholderEntry rootEntry =
                        placeholderMap.get(rootPlaceholderKey);
                rootEntry.standpointAxiomType = axiomWithLabel.axiomType;

                if (!rootEntry.isNegatedAxiom
                        && rootEntry.standpointAxiomType
                        == PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION) {
                    String before = rootEntry.manchester;
                    rootEntry.manchester =
                            standpointNormaliser.normaliseSubClassOf(rootEntry.manchester);
                    PipelineLogger.log("  Rule (11): " + before + " → " + rootEntry.manchester);
                }

                new PlaceholderRestorer(placeholderMap).restoreModalDuality();

                PipelineLogger.log("  Root: " + rootPlaceholderKey + " → " + rootEntry);
                normalisedPlaceholderMap.putAll(placeholderMap);
            }
        }

        // Step 4 — Rule (3): □_s[¬(C ⊑ D)] → {□_s[A ⊑ C], □_s[A ⊓ D ⊑ ⊥], □_s[⊤ ⊑ ∃R'.A]}
        PipelineLogger.log("\n=== STEP 4: RULE (3) — NEGATED GCI ===\n");

        List<String> keysToRemove = new ArrayList<>();
        List<Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry>> snapshot =
                new ArrayList<>(normalisedPlaceholderMap.entrySet());

        for (Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry> e : snapshot) {
            PlaceholderSubstituter.PlaceholderEntry entry = e.getValue();
            if (!entry.isNegatedAxiom
                    || entry.standpointAxiomType
                    != PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION) continue;

            String inner      = entry.manchester;
            int subClassOfIdx = inner.indexOf("SubClassOf");
            String C = inner.substring(0, subClassOfIdx).trim();
            String D = inner.substring(subClassOfIdx + "SubClassOf".length()).trim();

            String freshA = "FC_" + PlaceholderUtil.generateWithoutPrefix();
            String freshR = "FR_" + PlaceholderUtil.generateWithoutPrefix();

            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(IRI.create("http://standpoint.org/helper#" + freshA))));
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLObjectProperty(IRI.create("http://standpoint.org/helper#" + freshR))));

            String key1 = PlaceholderUtil.generate();
            String key2 = PlaceholderUtil.generate();
            String key3 = PlaceholderUtil.generate();

            PlaceholderSubstituter.PlaceholderEntry e1 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint, freshA + " SubClassOf " + C);
            PlaceholderSubstituter.PlaceholderEntry e2 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    "(" + freshA + " and " + D + ") SubClassOf owl:Nothing");
            PlaceholderSubstituter.PlaceholderEntry e3 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    "Thing SubClassOf (" + freshR + " some " + freshA + ")");

            e1.isRoot = true;
            e1.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;
            e2.isRoot = true;
            e2.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;
            e3.isRoot = true;
            e3.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;

            normalisedPlaceholderMap.put(key1, e1);
            normalisedPlaceholderMap.put(key2, e2);
            normalisedPlaceholderMap.put(key3, e3);

            e1.manchester = standpointNormaliser.normaliseSubClassOf(e1.manchester);
            e2.manchester = standpointNormaliser.normaliseSubClassOf(e2.manchester);
            e3.manchester = standpointNormaliser.normaliseSubClassOf(e3.manchester);

            keysToRemove.add(e.getKey());

            PipelineLogger.log("Rule (3) on " + e.getKey() + " [" + C + " ⊑ " + D + "]:");
            PipelineLogger.log("  → " + key1 + ": " + e1);
            PipelineLogger.log("  → " + key2 + ": " + e2);
            PipelineLogger.log("  → " + key3 + ": " + e3);
        }
        for (String key : keysToRemove) normalisedPlaceholderMap.remove(key);

        // Step 5 — Rule (4): □_s[¬(C(a))] → □_s[(¬C)(a)]
        //           Rule (10): □_s[C(a)] → □_s[NNF(C)(a)]
        PipelineLogger.log("\n=== STEP 5: RULES (4)(10) — ASSERTIONS ===\n");

        List<Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry>> assertionSnapshot =
                new ArrayList<>(normalisedPlaceholderMap.entrySet());

        for (Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry> e : assertionSnapshot) {
            PlaceholderSubstituter.PlaceholderEntry entry = e.getValue();
            if (entry.standpointAxiomType
                    != PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_ASSERTION) continue;

            String inner      = entry.manchester;
            int typeIdx       = inner.indexOf(" Type ");
            if (typeIdx == -1) continue;

            String individual = inner.substring(0, typeIdx).trim();
            String concept    = inner.substring(typeIdx + " Type ".length()).trim();

            String newConcept;
            if (entry.isNegatedAxiom) {
                newConcept = "not (" + concept + ")";
                PipelineLogger.log("Rule (4) on " + e.getKey() + ": " + inner
                        + " → " + individual + " Type " + newConcept);
            } else {
                newConcept = standpointNormaliser.applyNNFToConceptExpression(concept);
                PipelineLogger.log("Rule (10) on " + e.getKey() + ": " + inner
                        + " → " + individual + " Type " + newConcept);
            }

            entry.manchester          = individual + " Type " + newConcept;
            entry.isNegatedAxiom      = false;
            entry.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.NONE;
            entry.isRoot              = true;
        }

        // Step 6 — Rule (6): □_s[¬(S ⊑ R)] → {□_s[⊤ ⊑ ∃R'.Ca], □_s[Ca ⊓ ∃R.Cb ⊑ ⊥], □_s[Ca ⊑ ∃S.Cb]}
        PipelineLogger.log("\n=== STEP 6: RULE (6) — NEGATED ROLE INCLUSION ===\n");

        List<String> roleKeysToRemove = new ArrayList<>();
        List<Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry>> roleSnapshot =
                new ArrayList<>(normalisedPlaceholderMap.entrySet());

        for (Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry> e : roleSnapshot) {
            PlaceholderSubstituter.PlaceholderEntry entry = e.getValue();
            if (!entry.isNegatedAxiom
                    || entry.standpointAxiomType
                    != PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.ROLE_INCLUSION) continue;

            String inner = entry.manchester;
            int idx      = inner.indexOf("SubPropertyOf");
            String S     = inner.substring(0, idx).trim();
            String R     = inner.substring(idx + "SubPropertyOf".length()).trim();

            String freshCa = "FC_" + PlaceholderUtil.generateWithoutPrefix();
            String freshCb = "FC_" + PlaceholderUtil.generateWithoutPrefix();
            String freshRp = "FR_" + PlaceholderUtil.generateWithoutPrefix();

            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(IRI.create("http://standpoint.org/helper#" + freshCa))));
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(IRI.create("http://standpoint.org/helper#" + freshCb))));
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLObjectProperty(IRI.create("http://standpoint.org/helper#" + freshRp))));

            String key1 = PlaceholderUtil.generate();
            String key2 = PlaceholderUtil.generate();
            String key3 = PlaceholderUtil.generate();

            PlaceholderSubstituter.PlaceholderEntry e1 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    "Thing SubClassOf (" + freshRp + " some " + freshCa + ")");
            PlaceholderSubstituter.PlaceholderEntry e2 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    "(" + freshCa + " and (" + R + " some " + freshCb + ")) SubClassOf owl:Nothing");
            PlaceholderSubstituter.PlaceholderEntry e3 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    freshCa + " SubClassOf (" + S + " some " + freshCb + ")");

            e1.isRoot = true;
            e1.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;
            e2.isRoot = true;
            e2.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;
            e3.isRoot = true;
            e3.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;

            normalisedPlaceholderMap.put(key1, e1);
            normalisedPlaceholderMap.put(key2, e2);
            normalisedPlaceholderMap.put(key3, e3);

            e1.manchester = standpointNormaliser.normaliseSubClassOf(e1.manchester);
            e2.manchester = standpointNormaliser.normaliseSubClassOf(e2.manchester);
            e3.manchester = standpointNormaliser.normaliseSubClassOf(e3.manchester);

            roleKeysToRemove.add(e.getKey());

            PipelineLogger.log("Rule (6) on " + e.getKey() + " [" + S + " ⊑ " + R + "]:");
            PipelineLogger.log("  → " + key1 + ": " + e1);
            PipelineLogger.log("  → " + key2 + ": " + e2);
            PipelineLogger.log("  → " + key3 + ": " + e3);
        }
        for (String key : roleKeysToRemove) normalisedPlaceholderMap.remove(key);

        // Step 7 — Rule (5): □_s[¬R(a,b)] → {□_s[Ca(a)], □_s[Cb(b)], □_s[Ca ⊓ ∃R.Cb ⊑ ⊥]}
        PipelineLogger.log("\n=== STEP 7: RULE (5) — NEGATED ROLE ASSERTION ===\n");

        List<String> roleAssertionKeysToRemove = new ArrayList<>();
        List<Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry>> roleAssertionSnapshot =
                new ArrayList<>(normalisedPlaceholderMap.entrySet());

        for (Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry> e : roleAssertionSnapshot) {
            PlaceholderSubstituter.PlaceholderEntry entry = e.getValue();
            if (!entry.isNegatedAxiom
                    || entry.standpointAxiomType
                    != PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.ROLE_ASSERTION) continue;

            String inner    = entry.manchester;
            String[] tokens = inner.trim().split("\\s+");
            String a        = tokens[0];
            String role     = tokens[1];
            String b        = tokens[2];

            String freshCa = "FC_" + PlaceholderUtil.generateWithoutPrefix();
            String freshCb = "FC_" + PlaceholderUtil.generateWithoutPrefix();

            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(IRI.create("http://standpoint.org/helper#" + freshCa))));
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(IRI.create("http://standpoint.org/helper#" + freshCb))));

            String key1 = PlaceholderUtil.generate();
            String key2 = PlaceholderUtil.generate();
            String key3 = PlaceholderUtil.generate();

            PlaceholderSubstituter.PlaceholderEntry e1 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint, a + " Type " + freshCa);
            PlaceholderSubstituter.PlaceholderEntry e2 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint, b + " Type " + freshCb);
            PlaceholderSubstituter.PlaceholderEntry e3 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    "(" + freshCa + " and (" + role + " some " + freshCb + ")) SubClassOf owl:Nothing");

            e1.isRoot = true;
            e1.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_ASSERTION;
            e2.isRoot = true;
            e2.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_ASSERTION;
            e3.isRoot = true;
            e3.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;

            normalisedPlaceholderMap.put(key1, e1);
            normalisedPlaceholderMap.put(key2, e2);
            normalisedPlaceholderMap.put(key3, e3);

            e3.manchester = standpointNormaliser.normaliseSubClassOf(e3.manchester);

            roleAssertionKeysToRemove.add(e.getKey());

            PipelineLogger.log("Rule (5) on " + e.getKey()
                    + " [" + a + " " + role + " " + b + "]:");
            PipelineLogger.log("  → " + key1 + ": " + e1);
            PipelineLogger.log("  → " + key2 + ": " + e2);
            PipelineLogger.log("  → " + key3 + ": " + e3);
        }
        for (String key : roleAssertionKeysToRemove) normalisedPlaceholderMap.remove(key);

        // Step 8 — Rule (7): □_s[¬(Tra(R))] → {□_s[⊤ ⊑ ∃R'.Ca], □_s[Ca ⊓ ∃R.Cb ⊑ ⊥], □_s[Ca ⊑ ∃R.∃R.Cb]}
        PipelineLogger.log("\n=== STEP 8: RULE (7) — NEGATED TRANSITIVITY ===\n");

        List<String> transitivityKeysToRemove = new ArrayList<>();
        List<Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry>> transitivitySnapshot =
                new ArrayList<>(normalisedPlaceholderMap.entrySet());

        for (Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry> e : transitivitySnapshot) {
            PlaceholderSubstituter.PlaceholderEntry entry = e.getValue();
            if (!entry.isNegatedAxiom
                    || entry.standpointAxiomType
                    != PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.ROLE_TRANSITIVITY) continue;

            String inner = entry.manchester;
            String role  = inner.replace("Transitive", "").trim();

            String freshCa = "FC_" + PlaceholderUtil.generateWithoutPrefix();
            String freshCb = "FC_" + PlaceholderUtil.generateWithoutPrefix();
            String freshR  = "FR_" + PlaceholderUtil.generateWithoutPrefix();

            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(IRI.create("http://standpoint.org/helper#" + freshCa))));
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(IRI.create("http://standpoint.org/helper#" + freshCb))));
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLObjectProperty(IRI.create("http://standpoint.org/helper#" + freshR))));

            String key1 = PlaceholderUtil.generate();
            String key2 = PlaceholderUtil.generate();
            String key3 = PlaceholderUtil.generate();

            PlaceholderSubstituter.PlaceholderEntry e1 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    "Thing SubClassOf (" + freshR + " some " + freshCa + ")");
            PlaceholderSubstituter.PlaceholderEntry e2 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    "(" + freshCa + " and (" + role + " some " + freshCb + ")) SubClassOf owl:Nothing");
            PlaceholderSubstituter.PlaceholderEntry e3 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    freshCa + " SubClassOf (" + role + " some (" + role + " some " + freshCb + "))");

            e1.isRoot = true;
            e1.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;
            e2.isRoot = true;
            e2.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;
            e3.isRoot = true;
            e3.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;

            normalisedPlaceholderMap.put(key1, e1);
            normalisedPlaceholderMap.put(key2, e2);
            normalisedPlaceholderMap.put(key3, e3);

            e1.manchester = standpointNormaliser.normaliseSubClassOf(e1.manchester);
            e2.manchester = standpointNormaliser.normaliseSubClassOf(e2.manchester);
            e3.manchester = standpointNormaliser.normaliseSubClassOf(e3.manchester);

            transitivityKeysToRemove.add(e.getKey());

            PipelineLogger.log("Rule (7) on " + e.getKey() + " [Tra(" + role + ")]:");
            PipelineLogger.log("  → " + key1 + ": " + e1);
            PipelineLogger.log("  → " + key2 + ": " + e2);
            PipelineLogger.log("  → " + key3 + ": " + e3);
        }
        for (String key : transitivityKeysToRemove) normalisedPlaceholderMap.remove(key);

        // Step 9 — Rule (8): ¬(s1 ∩ ... ∩ sn ⪯ u) → {v ⪯ s1, ..., v ⪯ sn, v ∩ u ⪯ 0}
        PipelineLogger.log("\n=== STEP 9: RULE (8) — NEGATED SHARPENING ===\n");

        for (SharpeningStatement parsed : loadedSharpenings) {
            if (!parsed.isNegated) continue;

            String freshV = "FS_" + PlaceholderUtil.generateWithoutPrefix();

            for (String si : parsed.lhsStandpoints) {
                sharpenings.add(new SharpeningStatement(
                        Collections.singletonList(freshV), si));
                PipelineLogger.log("  → " + freshV + " ⪯ " + si);
            }

            List<String> vAndU = new ArrayList<>();
            vAndU.add(freshV);
            vAndU.add(parsed.rhsStandpoint);
            sharpenings.add(new SharpeningStatement(vAndU, "0"));

            PipelineLogger.log("Rule (8) applied — fresh standpoint: " + freshV);
            PipelineLogger.log("  → " + freshV + " ∩ " + parsed.rhsStandpoint + " ⪯ 0");
        }

        // Step 10 — Rule (9): s1 ∩ ... ∩ sn ⪯ 0
        PipelineLogger.log("\n=== STEP 10: RULE (9) — ZERO SHARPENING ===\n");

        List<SharpeningStatement> zeroSharpenings = new ArrayList<>();
        for (SharpeningStatement s : sharpenings) {
            if (s.isZero()) zeroSharpenings.add(s);
        }
        sharpenings.removeAll(zeroSharpenings);

        for (SharpeningStatement parsed : loadedSharpenings) {
            if (!parsed.isNegated && parsed.isZero()) zeroSharpenings.add(parsed);
        }

        for (SharpeningStatement zero : zeroSharpenings) {
            List<String> freshConcepts = new ArrayList<>();

            PipelineLogger.log("Rule (9) on: " + zero);

            for (String si : zero.lhsStandpoints) {
                String freshCi = "FC_" + PlaceholderUtil.generateWithoutPrefix();
                freshConcepts.add(freshCi);

                helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                        helperDf.getOWLClass(IRI.create("http://standpoint.org/helper#" + freshCi))));

                String key = PlaceholderUtil.generate();
                PlaceholderSubstituter.PlaceholderEntry entry =
                        new PlaceholderSubstituter.PlaceholderEntry(
                                Operator.BOX, si, "Thing SubClassOf " + freshCi);
                entry.isRoot = true;
                entry.standpointAxiomType =
                        PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;
                entry.manchester = standpointNormaliser.normaliseSubClassOf(entry.manchester);
                normalisedPlaceholderMap.put(key, entry);

                PipelineLogger.log("  → " + key + ": □_" + si + "[⊤ ⊑ " + freshCi + "]");
            }

            String intersection = String.join(" and ", freshConcepts);
            String key = PlaceholderUtil.generate();
            PlaceholderSubstituter.PlaceholderEntry entry =
                    new PlaceholderSubstituter.PlaceholderEntry(
                            Operator.BOX, "*",
                            "(" + intersection + ") SubClassOf owl:Nothing");
            entry.isRoot = true;
            entry.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;
            entry.manchester = standpointNormaliser.normaliseSubClassOf(entry.manchester);
            normalisedPlaceholderMap.put(key, entry);

            PipelineLogger.log("  → " + key + ": □_*[" + intersection + " ⊑ ⊥]");
        }

        // Add normal sharpenings from loaded
        for (SharpeningStatement parsed : loadedSharpenings) {
            if (!parsed.isNegated && !parsed.isZero()) {
                sharpenings.add(parsed);
                PipelineLogger.log("Normal sharpening added: " + parsed);
            }
        }

        // Step 11 — Final NNF loop + duality restoration
        PipelineLogger.log("\n=== STEP 11: FINAL NNF LOOP + DUALITY RESTORATION ===\n");

        boolean changed = true;
        int iteration = 0;
        while (changed) {
            changed = false;
            iteration++;
            for (PlaceholderSubstituter.PlaceholderEntry entry : normalisedPlaceholderMap.values()) {
                if (entry.standpointAxiomType
                        == PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.NONE) {
                    String before = entry.manchester;
                    String nnf = standpointNormaliser.applyNNFToConceptExpression(entry.manchester);
                    if (!nnf.equals(entry.manchester)) {
                        entry.manchester = nnf;
                        changed = true;
                        PipelineLogger.log("  NNF iteration " + iteration
                                + ": " + before + " → " + nnf);
                    }
                }
            }
            PlaceholderRestorer dualityRestorer = new PlaceholderRestorer(normalisedPlaceholderMap);
            if (dualityRestorer.restoreModalDuality()) {
                changed = true;
                PipelineLogger.log("  Duality restored in iteration " + iteration);
            }
        }
        PipelineLogger.log("NNF loop completed in " + iteration + " iteration(s)");

        // Print final results
        PipelineLogger.result("\n=== FULL NORMALISED PLACEHOLDER MAP ===\n");
        for (Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry> e :
                normalisedPlaceholderMap.entrySet()) {
            PipelineLogger.result(e.getKey() + " → " + e.getValue());
        }

        PipelineLogger.result("\n=== SHARPENINGS ===\n");
        if (sharpenings.isEmpty()) {
            PipelineLogger.result("(none)");
        } else {
            for (SharpeningStatement s : sharpenings) {
                PipelineLogger.result(s.toString());
            }
        }

        return new PipelineResult(normalisedPlaceholderMap, sharpenings);
    }

    private void registerAxiomEntitiesInHelper(
            Map<String, PlaceholderSubstituter.PlaceholderEntry> placeholderMap,
            OWLAxiom axiom,
            OWLOntologyManager helperManager,
            OWLOntology helperOntology,
            OWLDataFactory helperDf) throws Exception {

        for (String placeholderKey : placeholderMap.keySet()) {
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(
                            IRI.create("http://standpoint.org/helper#" + placeholderKey))));
        }
        for (OWLClass cls : axiom.getClassesInSignature()) {
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(cls));
        }
        for (OWLObjectProperty prop : axiom.getObjectPropertiesInSignature()) {
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(prop));
        }
        for (OWLNamedIndividual ind : axiom.getIndividualsInSignature()) {
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(ind));
        }
    }

    private String extractInnerContent(String xml) {
        try {
            String wrapped = "<root>" + xml.trim() + "</root>";
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(wrapped)));
            Node axiom = doc.getDocumentElement().getFirstChild();
            // Get all child content as string
            StringBuilder sb = new StringBuilder();
            NodeList children = axiom.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.TEXT_NODE) {
                    sb.append(child.getTextContent());
                } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                    // Serialize element back to string
                    sb.append(nodeToString(child));
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid axiom label XML: " + e.getMessage(), e);
        }
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

    private String formatFormula(String xml) {
        try {
            String wrapped = "<root>" + xml.trim() + "</root>";
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(wrapped)));
            Node modal = doc.getDocumentElement().getFirstChild();
            return formatNode(modal);
        } catch (Exception e) {
            return xml;
        }
    }

    private String formatNode(Node node) {
        if (node == null) return "";
        if (node.getNodeType() == Node.TEXT_NODE)
            return node.getTextContent().trim();

        String op         = node.getAttributes().getNamedItem("op").getNodeValue();
        String standpoint = node.getAttributes().getNamedItem("standpoint").getNodeValue();
        Node negated      = node.getAttributes().getNamedItem("negated");
        Node negatedInner = node.getAttributes().getNamedItem("negatedInner");

        String symbol = "box".equals(op) ? "□" : "◇";

        StringBuilder inner = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                inner.append(child.getTextContent().trim());
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                inner.append(formatNode(child));
            }
        }

        String result = symbol + "_" + standpoint + " [ " + inner.toString().trim() + " ] ";

        if (negated != null && "true".equals(negated.getNodeValue()))
            result = "¬" + result;
        if (negatedInner != null && "true".equals(negatedInner.getNodeValue()))
            result = symbol + "_" + standpoint + " [ ¬ ( " + inner.toString().trim() + " ) ] ";

        return result;
    }

    private String formatAxiomLabel(String xml) {
        try {
            String wrapped = "<root>" + xml.trim() + "</root>";
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(wrapped)));
            Node axiom = doc.getDocumentElement().getFirstChild();

            // Get inner content of <axiom id="...">
            StringBuilder sb = new StringBuilder();
            NodeList children = axiom.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.TEXT_NODE) {
                    sb.append(child.getTextContent().trim());
                } else if (child.getNodeType() == Node.ELEMENT_NODE
                        && child.getNodeName().equals("modal")) {
                    sb.append(formatNode(child));
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return xml;
        }
    }
}