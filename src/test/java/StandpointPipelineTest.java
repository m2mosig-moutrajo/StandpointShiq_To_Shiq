import org.junit.Test;
import org.junit.Assert;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.pipeline.PipelineResult;
import org.standpoint.plugin.pipeline.StandpointPipeline;
import org.standpoint.plugin.util.PipelineLogger;

import java.util.*;

public class StandpointPipelineTest {

    // Counters for generating unique names
    private int conceptCounter = 0;
    private int roleCounter    = 0;
    private int indCounter     = 0;
    private int standpointCounter = 0;
    private int axiomIdCounter = 0;

    private String freshConcept()    { return "C" + (++conceptCounter); }
    private String freshRole()       { return "r" + (++roleCounter); }
    private String freshIndividual() { return "ind" + (++indCounter); }
    private String freshStandpoint() { return "s" + (++standpointCounter); }
    private String freshAxiomId()    { return "F" + (++axiomIdCounter); }

    @Test
    public void testRandomGeneration() throws Exception {
        runTest(
                4,  // CONCEPT_INCLUSION
                5,  // CONCEPT_ASSERTION
                6,  // ROLE_INCLUSION
                7,  // ROLE_ASSERTION
                5,  // ROLE_TRANSITIVITY
                2   // formulas
        );
    }

    private void runTest(
            int numCI, int numCA, int numRI, int numRA, int numRT,
            int numFormulas) throws Exception {

        // Reset counters
        conceptCounter = 0;
        roleCounter    = 0;
        indCounter     = 0;
        standpointCounter = 0;
        axiomIdCounter = 0;

        // Step 1 — Generate axioms
        List<GeneratedAxiom> axioms = new ArrayList<>();
        for (int i = 0; i < numCI; i++) axioms.add(generateCI());
        for (int i = 0; i < numCA; i++) axioms.add(generateCA());
        for (int i = 0; i < numRI; i++) axioms.add(generateRI());
        for (int i = 0; i < numRA; i++) axioms.add(generateRA());
        for (int i = 0; i < numRT; i++) axioms.add(generateRT());

        // Step 2 — Generate formulas
        List<GeneratedFormula> formulas = generateFormulas(axioms, numFormulas);

        // Step 3 — Compute expected counts
        ExpectedCounts expected = computeExpected(formulas, axioms);

        // Step 4 — Build ontology and run pipeline
        OWLOntology ontology = buildOntology(axioms, formulas);
        PipelineLogger.setLevel(PipelineLogger.Level.ON); // ← AFTER buildOntology
        PipelineResult result = new StandpointPipeline(ontology).run(); // single-arg constructor

        // Step 5 — Assert
        System.out.println("\n=== TEST RESULTS ===");
        System.out.println("Expected axioms:      " + expected.axiomCount);
        System.out.println("Actual axioms:        " + result.normalisedPlaceholderMap.size());
        System.out.println("Expected sharpenings: " + expected.sharpeningCount);
        System.out.println("Actual sharpenings:   " + result.sharpenings.size());
        System.out.println("Expected fresh FC:    " + expected.freshConceptCount);
        System.out.println("Actual fresh FC:      " + countFresh(result, "FC_"));
        System.out.println("Expected fresh FR:    " + expected.freshRoleCount);
        System.out.println("Actual fresh FR:      " + countFresh(result, "FR_"));

        Assert.assertEquals("Axiom count mismatch",
                expected.axiomCount, result.normalisedPlaceholderMap.size());
        Assert.assertEquals("Sharpening count mismatch",
                expected.sharpeningCount, result.sharpenings.size());
    }

    // ===== Generated axiom types =====

    private static class GeneratedAxiom {
        public final String id;
        public final String manchesterContent; // inner content of <axiom id="...">
        public final AxiomKind kind;
        public final boolean negated; // will be negated in formula

        public GeneratedAxiom(String id, String manchesterContent,
                              AxiomKind kind, boolean negated) {
            this.id               = id;
            this.manchesterContent = manchesterContent;
            this.kind             = kind;
            this.negated          = negated;
        }
    }

    private enum AxiomKind {
        CONCEPT_INCLUSION, CONCEPT_ASSERTION,
        ROLE_INCLUSION, ROLE_ASSERTION, ROLE_TRANSITIVITY
    }

    private GeneratedAxiom generateCI() {
        String C = freshConcept();
        String D = freshConcept();
        boolean negated = new Random().nextBoolean();
        return new GeneratedAxiom(freshAxiomId(),
                C + " SubClassOf " + D,
                AxiomKind.CONCEPT_INCLUSION, negated);
    }

    private GeneratedAxiom generateCA() {
        String ind = freshIndividual();
        String C   = freshConcept();
        boolean negated = new Random().nextBoolean();
        return new GeneratedAxiom(freshAxiomId(),
                ind + " Type " + C,
                AxiomKind.CONCEPT_ASSERTION, negated);
    }

    private GeneratedAxiom generateRI() {
        String S = freshRole();
        String R = freshRole();
        boolean negated = new Random().nextBoolean();
        return new GeneratedAxiom(freshAxiomId(),
                S + " SubPropertyOf " + R,
                AxiomKind.ROLE_INCLUSION, negated);
    }

    private GeneratedAxiom generateRA() {
        String a    = freshIndividual();
        String b    = freshIndividual();
        String role = freshRole();
        boolean negated = new Random().nextBoolean();
        return new GeneratedAxiom(freshAxiomId(),
                a + " " + role + " " + b,
                AxiomKind.ROLE_ASSERTION, negated);
    }

    private GeneratedAxiom generateRT() {
        String role = freshRole();
        boolean negated = new Random().nextBoolean();
        return new GeneratedAxiom(freshAxiomId(),
                "Transitive " + role,
                AxiomKind.ROLE_TRANSITIVITY, negated);
    }

    // ===== Formula generation =====

    private static class GeneratedFormula {
        public final String operator;   // box or diamond
        public final String standpoint;
        public final List<GeneratedAxiom> literals;

        public GeneratedFormula(String operator, String standpoint,
                                List<GeneratedAxiom> literals) {
            this.operator   = operator;
            this.standpoint = standpoint;
            this.literals   = literals;
        }
    }

    private List<GeneratedFormula> generateFormulas(
            List<GeneratedAxiom> axioms, int numFormulas) {
        List<GeneratedFormula> formulas = new ArrayList<>();
        Random rand = new Random();

        List<GeneratedAxiom> shuffled = new ArrayList<>(axioms);
        Collections.shuffle(shuffled);

        int idx = 0;
        for (int i = 0; i < numFormulas && idx < shuffled.size(); i++) {
            String op = rand.nextBoolean() ? "box" : "diamond";
            String sp = freshStandpoint();

            int remaining = shuffled.size() - idx;
            int remainingFormulas = numFormulas - i;
            int maxCount = remaining - (remainingFormulas - 1);
            int count = maxCount <= 1 ? 1 : 1 + rand.nextInt(maxCount);

            List<GeneratedAxiom> literals = new ArrayList<>();
            for (int j = 0; j < count && idx < shuffled.size(); j++, idx++) {
                literals.add(shuffled.get(idx));
            }
            formulas.add(new GeneratedFormula(op, sp, literals));
        }
        return formulas;
    }

    // ===== Expected count computation =====

    private static class ExpectedCounts {
        public int axiomCount      = 0;
        public int sharpeningCount = 0;
        public int freshConceptCount = 0;
        public int freshRoleCount    = 0;
    }

    private ExpectedCounts computeExpected(
            List<GeneratedFormula> formulas,
            List<GeneratedAxiom> allAxioms) {

        ExpectedCounts counts = new ExpectedCounts();

        for (GeneratedFormula formula : formulas) {
            // Rule (1): diamond formula → 1 sharpening
            if ("diamond".equals(formula.operator)) {
                counts.sharpeningCount++;
            }

            for (GeneratedAxiom axiom : formula.literals) {
                if (axiom.negated) {
                    switch (axiom.kind) {
                        case CONCEPT_INCLUSION:
                            // Rule (3): → 3 axioms, 1 FC, 1 FR
                            counts.axiomCount      += 3;
                            counts.freshConceptCount += 1;
                            counts.freshRoleCount    += 1;
                            break;
                        case CONCEPT_ASSERTION:
                            // Rule (4): → 1 axiom
                            counts.axiomCount += 1;
                            break;
                        case ROLE_INCLUSION:
                            // Rule (6): → 3 axioms, 2 FC, 1 FR
                            counts.axiomCount        += 3;
                            counts.freshConceptCount += 2;
                            counts.freshRoleCount    += 1;
                            break;
                        case ROLE_ASSERTION:
                            // Rule (5): → 3 axioms, 2 FC
                            counts.axiomCount        += 3;
                            counts.freshConceptCount += 2;
                            break;
                        case ROLE_TRANSITIVITY:
                            // Rule (7): → 3 axioms, 2 FC, 1 FR
                            counts.axiomCount        += 3;
                            counts.freshConceptCount += 2;
                            counts.freshRoleCount    += 1;
                            break;
                    }
                } else {
                    // Non-negated — always 1 axiom
                    counts.axiomCount += 1;
                }
            }
        }
        return counts;
    }

    // ===== Ontology builder =====

    private OWLOntology buildOntology(
            List<GeneratedAxiom> axioms,
            List<GeneratedFormula> formulas) throws Exception {

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(
                IRI.create("http://standpoint.org/test"));

        String base = "http://standpoint.org/test#";

        // Create annotation properties
        OWLAnnotationProperty axiomProp = df.getOWLAnnotationProperty(
                IRI.create(base + "standpointAxiom"));
        OWLAnnotationProperty formulaProp = df.getOWLAnnotationProperty(
                IRI.create(base + "standpointFormula"));

        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(axiomProp));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(formulaProp));

        // Add axioms with standpointAxiom annotations
        for (GeneratedAxiom axiom : axioms) {
            OWLAxiom owlAxiom = buildOWLAxiom(axiom, df, base);
            String label = "<axiom id=\"" + axiom.id + "\">"
                    + axiom.manchesterContent + "</axiom>";
            OWLAnnotation ann = df.getOWLAnnotation(axiomProp,
                    df.getOWLLiteral(label));
            OWLAxiom annotated = owlAxiom.getAnnotatedAxiom(
                    Collections.singleton(ann));
            manager.addAxiom(ontology, annotated);
        }

        // Add formula annotations on ontology
        for (GeneratedFormula formula : formulas) {
            String xml = buildFormulaXml(formula);
            OWLAnnotation ann = df.getOWLAnnotation(formulaProp,
                    df.getOWLLiteral(xml));
            manager.applyChange(new AddOntologyAnnotation(ontology, ann));
        }

        return ontology;
    }

    private OWLAxiom buildOWLAxiom(GeneratedAxiom axiom,
                                   OWLDataFactory df, String base) {
        switch (axiom.kind) {
            case CONCEPT_INCLUSION: {
                String[] parts = axiom.manchesterContent.split(" SubClassOf ");
                OWLClass C = df.getOWLClass(IRI.create(base + parts[0].trim()));
                OWLClass D = df.getOWLClass(IRI.create(base + parts[1].trim()));
                return df.getOWLSubClassOfAxiom(C, D);
            }
            case CONCEPT_ASSERTION: {
                String[] parts = axiom.manchesterContent.split(" Type ");
                OWLNamedIndividual ind = df.getOWLNamedIndividual(
                        IRI.create(base + parts[0].trim()));
                OWLClass C = df.getOWLClass(IRI.create(base + parts[1].trim()));
                return df.getOWLClassAssertionAxiom(C, ind);
            }
            case ROLE_INCLUSION: {
                String[] parts = axiom.manchesterContent.split(" SubPropertyOf ");
                OWLObjectProperty S = df.getOWLObjectProperty(
                        IRI.create(base + parts[0].trim()));
                OWLObjectProperty R = df.getOWLObjectProperty(
                        IRI.create(base + parts[1].trim()));
                return df.getOWLSubObjectPropertyOfAxiom(S, R);
            }
            case ROLE_ASSERTION: {
                String[] parts = axiom.manchesterContent.trim().split("\\s+");
                OWLNamedIndividual a = df.getOWLNamedIndividual(
                        IRI.create(base + parts[0]));
                OWLObjectProperty r = df.getOWLObjectProperty(
                        IRI.create(base + parts[1]));
                OWLNamedIndividual b = df.getOWLNamedIndividual(
                        IRI.create(base + parts[2]));
                return df.getOWLObjectPropertyAssertionAxiom(r, a, b);
            }
            case ROLE_TRANSITIVITY: {
                String role = axiom.manchesterContent
                        .replace("Transitive", "").trim();
                OWLObjectProperty r = df.getOWLObjectProperty(
                        IRI.create(base + role));
                return df.getOWLTransitiveObjectPropertyAxiom(r);
            }
            default: throw new IllegalArgumentException("Unknown kind");
        }
    }

    private String buildFormulaXml(GeneratedFormula formula) {
        StringBuilder sb = new StringBuilder();
        sb.append("<formula op=\"").append(formula.operator)
                .append("\" standpoint=\"").append(formula.standpoint).append("\">");

        if (formula.literals.size() == 1) {
            GeneratedAxiom lit = formula.literals.get(0);
            sb.append("<literal ref=\"").append(lit.id).append("\"");
            if (lit.negated) sb.append(" negated=\"true\"");
            sb.append("/>");
        } else {
            sb.append("<intersection>");
            for (GeneratedAxiom lit : formula.literals) {
                sb.append("<literal ref=\"").append(lit.id).append("\"");
                if (lit.negated) sb.append(" negated=\"true\"");
                sb.append("/>");
            }
            sb.append("</intersection>");
        }

        sb.append("</formula>");
        return sb.toString();
    }

    // ===== Helpers =====

    private int countFresh(PipelineResult result, String prefix) {
        Set<String> found = new HashSet<>();
        for (Map.Entry<String, ?> e : result.normalisedPlaceholderMap.entrySet()) {
            String manchester = result.normalisedPlaceholderMap
                    .get(e.getKey()).manchester;
            // Find all FC_ or FR_ tokens
            for (String token : manchester.split("\\s+|\\(|\\)")) {
                if (token.startsWith(prefix)) found.add(token);
            }
        }
        return found.size();
    }
}