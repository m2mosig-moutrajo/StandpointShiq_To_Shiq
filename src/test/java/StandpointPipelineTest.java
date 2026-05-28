import org.junit.Test;
import org.junit.Assert;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.model.PlaceholderType;
import org.standpoint.plugin.pipeline.data.NormalisedAxiom;
import org.standpoint.plugin.pipeline.data.StandpointKnowledgeBase;
import org.standpoint.plugin.pipeline.normalisation.AnnotationProcessor;
import org.standpoint.plugin.util.PipelineLogger;

import java.util.*;

public class StandpointPipelineTest {

    private int conceptCounter    = 0;
    private int roleCounter       = 0;
    private int indCounter        = 0;
    private int standpointCounter = 0;
    private int axiomIdCounter    = 0;

    private String freshConcept()    { return "C"   + (++conceptCounter); }
    private String freshRole()       { return "r"   + (++roleCounter); }
    private String freshIndividual() { return "ind" + (++indCounter); }
    private String freshStandpoint() { return "s"   + (++standpointCounter); }
    private String freshAxiomId()    { return "F"   + (++axiomIdCounter); }

    @Test
    public void testRandomGeneration() throws Exception {
        runTest(2, 2, 2, 2, 2, 2, 2, 10, 10, 10);
    }

    private void runTest(
            int numCI, int numCA, int numRI, int numRA, int numRT,
            int numNested, int numFormulas,
            int numNormalSharpenings,
            int numZeroSharpenings,
            int numNegatedSharpenings) throws Exception {

        conceptCounter    = 0;
        roleCounter       = 0;
        indCounter        = 0;
        standpointCounter = 0;
        axiomIdCounter    = 0;

        List<GeneratedAxiom> axioms = new ArrayList<>();
        for (int i = 0; i < numCI;     i++) axioms.add(generateCI());
        for (int i = 0; i < numCA;     i++) axioms.add(generateCA());
        for (int i = 0; i < numRI;     i++) axioms.add(generateRI());
        for (int i = 0; i < numRA;     i++) axioms.add(generateRA());
        for (int i = 0; i < numRT;     i++) axioms.add(generateRT());
        for (int i = 0; i < numNested; i++) axioms.add(generateNestedCI());

        List<GeneratedFormula> formulas = generateFormulas(axioms, numFormulas);

        List<String> standpoints = new ArrayList<>();
        for (GeneratedFormula f : formulas) standpoints.add(f.standpoint);

        List<GeneratedSharpening> sharpenings = generateSharpenings(
                standpoints, numNormalSharpenings,
                numZeroSharpenings, numNegatedSharpenings);

        ExpectedCounts expected = computeExpected(axioms, formulas, sharpenings);

        OWLOntology ontology = buildOntology(axioms, formulas, sharpenings);

        PipelineLogger.setLevel(PipelineLogger.Level.OFF);
        StandpointKnowledgeBase result = new AnnotationProcessor(ontology).run();

        // count root entries in owlMap
        long actualRoots = result.owlMap.values().stream()
                .filter(na -> na.isRoot)
                .count();

        System.out.println("\n=== TEST RESULTS ===");
        System.out.println("Expected axioms:      " + expected.axiomCount);
        System.out.println("Actual axioms:        " + actualRoots);
        System.out.println("Expected sharpenings: " + expected.sharpeningCount);
        System.out.println("Actual sharpenings:   " + result.sharpenings.size());
        System.out.println("Expected fresh FC:    " + expected.freshConceptCount);
        System.out.println("Actual fresh FC:      " + countFreshConcepts(result));
        System.out.println("Expected fresh FR:    " + expected.freshRoleCount);
        System.out.println("Actual fresh FR:      " + countFreshRoles(result));

        Assert.assertEquals("Axiom count mismatch",
                expected.axiomCount, (int) actualRoots);
        Assert.assertEquals("Sharpening count mismatch",
                expected.sharpeningCount, result.sharpenings.size());
    }

    // ===== Fresh entity counters — scan OWL axiom signatures =====

    private int countFreshConcepts(StandpointKnowledgeBase result) {
        Set<String> found = new HashSet<>();
        for (NormalisedAxiom na : result.owlMap.values()) {
            Set<OWLClass> classes = na.isRoot && na.owlAxiom != null
                    ? na.owlAxiom.getClassesInSignature()
                    : na.owlTree != null
                    ? na.owlTree.getClassesInSignature()
                    : Collections.emptySet();
            for (OWLClass cls : classes) {
                String shortForm = cls.getIRI().getShortForm();
                if (shortForm.startsWith(PlaceholderType.FRESH_CONCEPT.prefix))
                    found.add(shortForm);
            }
        }
        return found.size();
    }

    private int countFreshRoles(StandpointKnowledgeBase result) {
        Set<String> found = new HashSet<>();
        for (NormalisedAxiom na : result.owlMap.values()) {
            if (na.owlAxiom == null) continue;
            for (OWLObjectProperty prop : na.owlAxiom.getObjectPropertiesInSignature()) {
                String shortForm = prop.getIRI().getShortForm();
                if (shortForm.startsWith(PlaceholderType.FRESH_ROLE.prefix))
                    found.add(shortForm);
            }
        }
        return found.size();
    }

    // ===== Axiom generators =====

    private static class GeneratedAxiom {
        public final String id;
        public final String manchesterContent;
        public final AxiomKind kind;
        public final boolean negated;
        public final String extraC;
        public final String extraD;

        public GeneratedAxiom(String id, String manchesterContent,
                              AxiomKind kind, boolean negated) {
            this(id, manchesterContent, kind, negated, null, null);
        }

        public GeneratedAxiom(String id, String manchesterContent,
                              AxiomKind kind, boolean negated,
                              String extraC, String extraD) {
            this.id                = id;
            this.manchesterContent = manchesterContent;
            this.kind              = kind;
            this.negated           = negated;
            this.extraC            = extraC;
            this.extraD            = extraD;
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
                C + " SubClassOf: " + D,
                AxiomKind.CONCEPT_INCLUSION, negated);
    }

    private GeneratedAxiom generateNestedCI() {
        String C         = freshConcept();
        String D         = freshConcept();
        String s         = freshStandpoint();
        Random rand      = new Random();
        boolean negated  = rand.nextBoolean();
        String innerOp   = rand.nextBoolean() ? "box" : "diamond";
        boolean innerNeg = rand.nextBoolean();

        String innerContent = innerNeg ? " not ( " + C + " ) " : C;
        String modalTag = "<modal op=\"" + innerOp + "\" standpoint=\"" + s + "\">"
                + innerContent + "</modal>";

        return new GeneratedAxiom(freshAxiomId(),
                modalTag + " SubClassOf: " + D,
                AxiomKind.CONCEPT_INCLUSION, negated, C, D);
    }

    private GeneratedAxiom generateCA() {
        String ind = freshIndividual();
        String C   = freshConcept();
        boolean negated = new Random().nextBoolean();
        return new GeneratedAxiom(freshAxiomId(),
                ind + " Type: " + C,
                AxiomKind.CONCEPT_ASSERTION, negated);
    }

    private GeneratedAxiom generateRI() {
        String S = freshRole();
        String R = freshRole();
        boolean negated = new Random().nextBoolean();
        return new GeneratedAxiom(freshAxiomId(),
                S + " SubPropertyOf: " + R,
                AxiomKind.ROLE_INCLUSION, negated);
    }

    private GeneratedAxiom generateRA() {
        String a    = freshIndividual();
        String b    = freshIndividual();
        String role = freshRole();
        boolean negated = new Random().nextBoolean();
        // use Individual: frame syntax — required by parseAxiom() for ROLE_ASSERTION
        return new GeneratedAxiom(freshAxiomId(),
                "Individual: " + a + " Facts: " + role + " " + b,
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
        public final String operator;
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

            int remaining         = shuffled.size() - idx;
            int remainingFormulas = numFormulas - i;
            int maxCount          = remaining - (remainingFormulas - 1);
            int count             = maxCount <= 1 ? 1 : 1 + rand.nextInt(maxCount);

            List<GeneratedAxiom> literals = new ArrayList<>();
            for (int j = 0; j < count && idx < shuffled.size(); j++, idx++) {
                literals.add(shuffled.get(idx));
            }
            formulas.add(new GeneratedFormula(op, sp, literals));
        }
        return formulas;
    }

    // ===== Sharpening generation =====

    private enum SharpeningKind { NORMAL, ZERO, NEGATED }

    private static class GeneratedSharpening {
        public final List<String> lhs;
        public final String rhs;
        public final SharpeningKind kind;

        public GeneratedSharpening(List<String> lhs, String rhs, SharpeningKind kind) {
            this.lhs  = lhs;
            this.rhs  = rhs;
            this.kind = kind;
        }
    }

    private List<GeneratedSharpening> generateSharpenings(
            List<String> standpoints,
            int numNormal, int numZero, int numNegated) {

        List<GeneratedSharpening> result = new ArrayList<>();
        if (standpoints.size() < 2) return result;

        Random rand = new Random();
        List<String> pool = new ArrayList<>(standpoints);
        Set<String> usedKeys = new HashSet<>();

        for (int i = 0; i < numNormal && pool.size() >= 2; i++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                Collections.shuffle(pool, rand);
                int lhsSize = 1 + rand.nextInt(Math.min(3, pool.size() - 1));
                List<String> lhs = new ArrayList<>(pool.subList(0, lhsSize));
                String rhs = pool.get(lhsSize);
                String key = "NORMAL:" + lhs + "->" + rhs;
                if (!usedKeys.contains(key)) {
                    usedKeys.add(key);
                    result.add(new GeneratedSharpening(lhs, rhs, SharpeningKind.NORMAL));
                    break;
                }
            }
        }

        for (int i = 0; i < numZero && pool.size() >= 1; i++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                Collections.shuffle(pool, rand);
                int lhsSize = 1 + rand.nextInt(Math.min(3, pool.size()));
                List<String> lhs = new ArrayList<>(pool.subList(0, lhsSize));
                String key = "ZERO:" + lhs;
                if (!usedKeys.contains(key)) {
                    usedKeys.add(key);
                    result.add(new GeneratedSharpening(lhs, "0", SharpeningKind.ZERO));
                    break;
                }
            }
        }

        for (int i = 0; i < numNegated && pool.size() >= 2; i++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                Collections.shuffle(pool, rand);
                int lhsSize = 1 + rand.nextInt(Math.min(3, pool.size() - 1));
                List<String> lhs = new ArrayList<>(pool.subList(0, lhsSize));
                String rhs = pool.get(lhsSize);
                String key = "NEGATED:" + lhs + "->" + rhs;
                if (!usedKeys.contains(key)) {
                    usedKeys.add(key);
                    result.add(new GeneratedSharpening(lhs, rhs, SharpeningKind.NEGATED));
                    break;
                }
            }
        }

        return result;
    }

    // ===== Expected count computation =====

    private static class ExpectedCounts {
        public int axiomCount        = 0;
        public int sharpeningCount   = 0;
        public int freshConceptCount = 0;
        public int freshRoleCount    = 0;
    }

    private ExpectedCounts computeExpected(
            List<GeneratedAxiom> allAxioms,           // ← add this
            List<GeneratedFormula> formulas,
            List<GeneratedSharpening> sharpenings) {

        ExpectedCounts counts = new ExpectedCounts();

        // collect all axiom IDs referenced by any formula
        Set<String> referencedIds = new HashSet<>();
        for (GeneratedFormula formula : formulas)
            for (GeneratedAxiom axiom : formula.literals)
                referencedIds.add(axiom.id);

        // unreferenced axioms → each wrapped as □_*[axiom] by injectUnreferencedAxioms
        // non-negated, so each contributes exactly 1 root entry
        for (GeneratedAxiom axiom : allAxioms)
            if (!referencedIds.contains(axiom.id))
                counts.axiomCount += 1;

        // formula-based axioms — unchanged
        for (GeneratedFormula formula : formulas) {
            if ("diamond".equals(formula.operator))
                counts.sharpeningCount++;

            for (GeneratedAxiom axiom : formula.literals) {
                if (axiom.negated) {
                    switch (axiom.kind) {
                        case CONCEPT_INCLUSION:
                            counts.axiomCount        += 3;
                            counts.freshConceptCount += 1;
                            counts.freshRoleCount    += 1;
                            break;
                        case CONCEPT_ASSERTION:
                            counts.axiomCount += 1;
                            break;
                        case ROLE_INCLUSION:
                            counts.axiomCount        += 3;
                            counts.freshConceptCount += 2;
                            counts.freshRoleCount    += 1;
                            break;
                        case ROLE_ASSERTION:
                            counts.axiomCount        += 3;
                            counts.freshConceptCount += 2;
                            break;
                        case ROLE_TRANSITIVITY:
                            counts.axiomCount        += 3;
                            counts.freshConceptCount += 2;
                            counts.freshRoleCount    += 1;
                            break;
                    }
                } else {
                    counts.axiomCount += 1;
                }
            }
        }

        // sharpening-based counts — unchanged
        for (GeneratedSharpening s : sharpenings) {
            int n = s.lhs.size();
            switch (s.kind) {
                case NORMAL:
                    counts.sharpeningCount += 1;
                    break;
                case ZERO:
                    counts.axiomCount        += n + 1;
                    counts.freshConceptCount += n;
                    break;
                case NEGATED:
                    counts.sharpeningCount   += n;
                    counts.axiomCount        += 3;
                    counts.freshConceptCount += 2;
                    break;
            }
        }

        return counts;
    }

    // ===== Ontology builder =====

    private OWLOntology buildOntology(
            List<GeneratedAxiom> axioms,
            List<GeneratedFormula> formulas,
            List<GeneratedSharpening> sharpenings) throws Exception {

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df          = manager.getOWLDataFactory();
        OWLOntology ontology       = manager.createOntology(
                IRI.create("http://standpoint.org/test"));

        String base = "http://standpoint.org/test#";

        OWLAnnotationProperty axiomProp = df.getOWLAnnotationProperty(
                IRI.create(base + "standpointAxiom"));
        OWLAnnotationProperty formulaProp = df.getOWLAnnotationProperty(
                IRI.create(base + "standpointFormula"));
        OWLAnnotationProperty sharpeningProp = df.getOWLAnnotationProperty(
                IRI.create(base + "standpointSharpening"));

        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(axiomProp));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(formulaProp));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(sharpeningProp));

        for (GeneratedAxiom axiom : axioms) {
            OWLAxiom owlAxiom = buildOWLAxiom(axiom, df, base);
            String label = "<axiom id=\"" + axiom.id + "\">"
                    + axiom.manchesterContent + "</axiom>";
            OWLAnnotation ann = df.getOWLAnnotation(axiomProp,
                    df.getOWLLiteral(label));
            manager.addAxiom(ontology,
                    owlAxiom.getAnnotatedAxiom(Collections.singleton(ann)));
        }

        for (GeneratedFormula formula : formulas) {
            OWLAnnotation ann = df.getOWLAnnotation(formulaProp,
                    df.getOWLLiteral(buildFormulaXml(formula)));
            manager.applyChange(new AddOntologyAnnotation(ontology, ann));
        }

        for (GeneratedSharpening s : sharpenings) {
            OWLAnnotation ann = df.getOWLAnnotation(sharpeningProp,
                    df.getOWLLiteral(buildSharpeningXml(s)));
            manager.applyChange(new AddOntologyAnnotation(ontology, ann));
        }

        return ontology;
    }

    private OWLAxiom buildOWLAxiom(GeneratedAxiom axiom,
                                   OWLDataFactory df, String base) {
        switch (axiom.kind) {
            case CONCEPT_INCLUSION: {
                String subName   = axiom.extraC != null ? axiom.extraC
                        : axiom.manchesterContent.split(" SubClassOf: ")[0].trim();
                String superName = axiom.extraD != null ? axiom.extraD
                        : axiom.manchesterContent.split(" SubClassOf: ")[1].trim();
                return df.getOWLSubClassOfAxiom(
                        df.getOWLClass(IRI.create(base + subName)),
                        df.getOWLClass(IRI.create(base + superName)));
            }
            case CONCEPT_ASSERTION: {
                String[] parts = axiom.manchesterContent.split(" Type: ");
                return df.getOWLClassAssertionAxiom(
                        df.getOWLClass(IRI.create(base + parts[1].trim())),
                        df.getOWLNamedIndividual(IRI.create(base + parts[0].trim())));
            }
            case ROLE_INCLUSION: {
                String[] parts = axiom.manchesterContent.split(" SubPropertyOf: ");
                return df.getOWLSubObjectPropertyOfAxiom(
                        df.getOWLObjectProperty(IRI.create(base + parts[0].trim())),
                        df.getOWLObjectProperty(IRI.create(base + parts[1].trim())));
            }
            case ROLE_ASSERTION: {
                // "Individual: a Facts: role b"
                String content = axiom.manchesterContent
                        .replace("Individual:", "").replace("Facts:", "").trim();
                String[] parts = content.trim().split("\\s+");
                return df.getOWLObjectPropertyAssertionAxiom(
                        df.getOWLObjectProperty(IRI.create(base + parts[1])),
                        df.getOWLNamedIndividual(IRI.create(base + parts[0])),
                        df.getOWLNamedIndividual(IRI.create(base + parts[2])));
            }
            case ROLE_TRANSITIVITY: {
                String role = axiom.manchesterContent.replace("Transitive", "").trim();
                return df.getOWLTransitiveObjectPropertyAxiom(
                        df.getOWLObjectProperty(IRI.create(base + role)));
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

    private String buildSharpeningXml(GeneratedSharpening s) {
        StringBuilder sb = new StringBuilder();
        if (s.kind == SharpeningKind.NEGATED)
            sb.append("<sharpening negated=\"true\">");
        else
            sb.append("<sharpening>");

        sb.append("<lhs>");
        if (s.lhs.size() == 1) {
            sb.append("<standpoint>").append(s.lhs.get(0)).append("</standpoint>");
        } else {
            sb.append("<intersection>");
            for (String sp : s.lhs)
                sb.append("<standpoint>").append(sp).append("</standpoint>");
            sb.append("</intersection>");
        }
        sb.append("</lhs>");

        sb.append("<rhs>");
        if ("0".equals(s.rhs))
            sb.append("<zero/>");
        else
            sb.append("<standpoint>").append(s.rhs).append("</standpoint>");
        sb.append("</rhs>");

        sb.append("</sharpening>");
        return sb.toString();
    }
}