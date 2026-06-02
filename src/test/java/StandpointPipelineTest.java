import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.Assert;
import org.junit.runners.MethodSorters;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.model.PlaceholderType;
import org.standpoint.plugin.pipeline.NormalisationPipeline;
import org.standpoint.plugin.pipeline.PrecisificationPipeline;
import org.standpoint.plugin.pipeline.TranslationPipeline;
import org.standpoint.plugin.pipeline.data.NormalisedAxiom;
import org.standpoint.plugin.pipeline.data.StandpointKnowledgeBase;
import org.standpoint.plugin.pipeline.normalisation.AnnotationProcessor;
import org.standpoint.plugin.pipeline.precisification.PrecisificationContext;
import org.standpoint.plugin.util.PipelineLogger;

import java.util.*;

/**
 * Test suite for the three-pipeline Standpoint-SHIQ translation system.
 *
 * All three tests now use randomly generated knowledge bases.
 * Expected values for Pipelines 2 and 3 are derived programmatically
 * from the pipeline outputs rather than being hardcoded.
 *
 *   testNormalisationPipeline   — P1: owlMap root count + sharpening count
 *   testPrecisificationPipeline — P2: |Π_K| derived from S, D, I
 *   testTranslationPipeline     — P3: axiom count derived from σ(s) sums
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
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

    private void resetCounters() {
        conceptCounter = roleCounter = indCounter
                = standpointCounter = axiomIdCounter = 0;
    }

    // =========================================================================
    // PIPELINE 1 — Normalisation  (random KB, analytical expected counts)
    // =========================================================================

    /**
     * Generates a random SSHIQ knowledge base, predicts the number of root
     * owlMap entries and sharpenings analytically from the normalisation rules,
     * runs Pipeline 1, and asserts both counts match.
     */
    @Test
    public void testNormalisationPipeline() throws Exception {
        resetCounters();
        runNormalisationTest(2, 2, 2, 2, 2, 2, 2, 10, 10, 10);
    }

    private void runNormalisationTest(
            int numCI, int numCA, int numRI, int numRA, int numRT,
            int numNested, int numFormulas,
            int numNormal, int numZero, int numNegated) throws Exception {

        List<GeneratedAxiom> axioms = new ArrayList<>();
        for (int i = 0; i < numCI;     i++) axioms.add(generateCI());
        for (int i = 0; i < numCA;     i++) axioms.add(generateCA());
        for (int i = 0; i < numRI;     i++) axioms.add(generateRI());
        for (int i = 0; i < numRA;     i++) axioms.add(generateRA());
        for (int i = 0; i < numRT;     i++) axioms.add(generateRT());
        for (int i = 0; i < numNested; i++) axioms.add(generateNestedCI());

        List<GeneratedFormula> formulas = generateFormulas(axioms, numFormulas);
        List<String> standpoints = extractStandpoints(formulas);
        List<GeneratedSharpening> sharpenings =
                generateSharpenings(standpoints, numNormal, numZero, numNegated);

        ExpectedNormalisationCounts expected =
                computeExpectedNormalisation(axioms, formulas, sharpenings);

        OWLOntology ontology = buildOntology(axioms, formulas, sharpenings);

        PipelineLogger.setLevel(PipelineLogger.Level.ON);
        StandpointKnowledgeBase kb = new AnnotationProcessor(ontology).run();

        long actualRoots = kb.owlMap.values().stream()
                .filter(na -> na.isRoot).count();

        System.out.println("\n=== PIPELINE 1 — NORMALISATION TEST ===");
        System.out.printf("Root axioms:  expected=%-4d actual=%d%n",
                expected.axiomCount, actualRoots);
        System.out.printf("Sharpenings:  expected=%-4d actual=%d%n",
                expected.sharpeningCount, kb.sharpening.size());
        System.out.printf("Fresh FC:     expected=%-4d actual=%d%n",
                expected.freshConceptCount, countFreshConcepts(kb));
        System.out.printf("Fresh FR:     expected=%-4d actual=%d%n",
                expected.freshRoleCount, countFreshRoles(kb));

        Assert.assertEquals("Axiom count mismatch",
                expected.axiomCount, (int) actualRoots);
        Assert.assertEquals("Sharpening count mismatch",
                expected.sharpeningCount, kb.sharpening.size());
    }

    // =========================================================================
    // PIPELINE 2 — Precisification  (random KB, expected |Π_K| derived from kb)
    // =========================================================================

    /**
     * Generates a random SSHIQ KB, runs Pipelines 1 and 2, then verifies
     * the precisification set size using the formula:
     *
     *   |Π_K| = S + 2·D + I·D
     *
     * where:
     *   S = number of distinct standpoints (from ctx.standpoints)
     *   D = number of distinct diamond subterms after deduplication
     *       (from ctx.diamonds)
     *   I = number of named individuals used in the KB
     *       (from collectIndividualCount(kb))
     *
     * All three quantities are derivable without hardcoding any value.
     */
    @Test
    public void testPrecisificationPipeline() throws Exception {
        resetCounters();
        PipelineLogger.setLevel(PipelineLogger.Level.ON);

        // ── Build a random KB ────────────────────────────────────────────────
        // Include at least one nested CI to guarantee at least one diamond
        // subterm in the precisification set, and at least one concept assertion
        // to guarantee at least one named individual.
        List<GeneratedAxiom> axioms = new ArrayList<>();
        for (int i = 0; i < 2; i++) axioms.add(generateCI());
        for (int i = 0; i < 2; i++) axioms.add(generateCA());
        for (int i = 0; i < 2; i++) axioms.add(generateRI());
        for (int i = 0; i < 2; i++) axioms.add(generateRA());
        for (int i = 0; i < 2; i++) axioms.add(generateRT());
        for (int i = 0; i < 2; i++) axioms.add(generateNestedCI());

        List<GeneratedFormula> formulas = generateFormulas(axioms, 2);
        List<String> standpoints = extractStandpoints(formulas);
        List<GeneratedSharpening> sharpenings =
                generateSharpenings(standpoints, 3, 3, 3);

        OWLOntology ontology = buildOntology(axioms, formulas, sharpenings);

        // ── Run Pipeline 1 + 2 ───────────────────────────────────────────────
        StandpointKnowledgeBase kb = new NormalisationPipeline(ontology).run();
        PrecisificationContext ctx = new PrecisificationPipeline(kb).run();

        // ── Derive expected |Π_K| from the three structural parameters ────────
        int S = ctx.standpoints.size();           // distinct standpoints incl. *
        int D = ctx.diamonds.size();              // distinct diamond subterms (D_n)
        int I = collectIndividualCount(kb);       // named individuals in root axioms
        int expectedPrecSet = S + 2 * D + I * D;

        System.out.println("\n=== PIPELINE 2 — PRECISIFICATION TEST ===");
        System.out.printf("Standpoints S:        %d%n", S);
        System.out.printf("Diamond subterms D:   %d%n", D);
        System.out.printf("Individuals I:        %d%n", I);
        System.out.printf("|Π_K|: expected=%-4d actual=%d%n",
                expectedPrecSet, ctx.precSet.size());

        Assert.assertEquals("Precisification set size mismatch",
                expectedPrecSet, ctx.precSet.size());
    }

    // =========================================================================
    // PIPELINE 3 — Translation  (random KB, expected axiom count derived from σ)
    // =========================================================================

    /**
     * Generates a random SSHIQ KB, runs all three pipelines, then verifies
     * the translated axiom count using the σ(s) sums:
     *
     *   Type 1 (AUX defs):   Σ over non-root owlMap entries → |σ(na.standpoint)|
     *   Type 2 (GCIs):       Σ over root CONCEPT_INCLUSION entries  → |σ(s)|
     *   Type 3 (RIs):        Σ over root ROLE_INCLUSION entries      → |σ(s)|
     *   Type 4 (Tra):        Σ over root ROLE_TRANSITIVITY entries   → |σ(s)|
     *   Type 5 (CAs):        Σ over root CONCEPT_ASSERTION entries   → |σ(s)|
     *   Type 6 (RAs):        Σ over root ROLE_ASSERTION entries      → |σ(s)|
     *
     * All quantities are read from ctx (diamonds, precSet.sigma) and kb (owlMap),
     * so the expected value is derived without hardcoding any number.
     *
     * Note: the OWL API deduplicates structurally identical axioms silently.
     * With randomly generated distinct entity names, duplication is extremely
     * unlikely, but the assertion may fail for degenerate inputs.
     */
    @Test
    public void testTranslationPipeline() throws Exception {
        resetCounters();
        PipelineLogger.setLevel(PipelineLogger.Level.ON);

        // ── Build a random KB ────────────────────────────────────────────────
        List<GeneratedAxiom> axioms = new ArrayList<>();
        for (int i = 0; i < 2; i++) axioms.add(generateCI());
        for (int i = 0; i < 2; i++) axioms.add(generateCA());
        for (int i = 0; i < 2; i++) axioms.add(generateRI());
        for (int i = 0; i < 2; i++) axioms.add(generateRA());
        for (int i = 0; i < 2; i++) axioms.add(generateRT());
        for (int i = 0; i < 2; i++) axioms.add(generateNestedCI());

        List<GeneratedFormula> formulas = generateFormulas(axioms, 2);
        List<String> standpoints = extractStandpoints(formulas);
        List<GeneratedSharpening> sharpenings =
                generateSharpenings(standpoints, 3, 3, 3);

        OWLOntology ontology = buildOntology(axioms, formulas, sharpenings);

        // ── Run all three pipelines ───────────────────────────────────────────
        StandpointKnowledgeBase kb  = new NormalisationPipeline(ontology).run();
        PrecisificationContext  ctx = new PrecisificationPipeline(kb).run();
        OWLOntology translated      = new TranslationPipeline(kb, ctx, null).run();

        // ── Derive expected axiom count from σ sums ───────────────────────────
        int expectedTotal = computeExpectedTranslationAxiomCount(kb, ctx);
        int actualAxioms  = translated.getLogicalAxiomCount();

        System.out.println("\n=== PIPELINE 3 — TRANSLATION TEST ===");
        System.out.printf("Expected total axioms: %-4d actual=%d%n",
                expectedTotal, actualAxioms);

        Assert.assertEquals("Translated axiom count mismatch",
                expectedTotal, actualAxioms);
    }

    // =========================================================================
    // Expected count derivation helpers
    // =========================================================================

    /**
     * Derives the expected translated axiom count from the pipeline outputs.
     *
     * Every owlMap entry contributes |σ(standpoint)| logical axioms:
     *   non-root entries (isRoot=false) → Type 1 AUX definition axioms
     *   root entries     (isRoot=true)  → Types 2–6 translated axioms
     *
     * The operator (box or diamond) of a non-root entry does not affect the count —
     * AUX definitions are generated for every non-root placeholder regardless of
     * operator. Duality restoration may convert diamond entries to box, but they
     * still generate the same number of AUX axioms.
     *
     * No value is hardcoded — everything is read from kb.owlMap and ctx.
     */
    private int computeExpectedTranslationAxiomCount(
            StandpointKnowledgeBase kb,
            PrecisificationContext ctx) {

        int total = 0;
        // Every owlMap entry (root or non-root) contributes |σ(standpoint)| axioms:
        //   non-root entries → Type 1 AUX definitions
        //   root entries     → Types 2-6 translated axioms
        for (NormalisedAxiom na : kb.owlMap.values()) {
            total += ctx.precSet.sigma(na.standpoint).size();
        }
        return total;
    }

    /**
     * Mirrors PrecisificationPipeline.collectUsedIndividuals(): scans every
     * NormalisedAxiom entry in owlMap and returns the count of distinct named
     * individuals found in owlAxiom and owlTree signatures.
     */
    private int collectIndividualCount(StandpointKnowledgeBase kb) {
        Set<OWLNamedIndividual> found = new HashSet<>();
        for (NormalisedAxiom na : kb.owlMap.values()) {
            if (na.owlAxiom != null)
                found.addAll(na.owlAxiom.getIndividualsInSignature());
            if (na.owlTree != null)
                found.addAll(na.owlTree.getIndividualsInSignature());
        }
        return found.size();
    }

    // =========================================================================
    // GeneratedAxiom — stores entity names, never Manchester strings
    // =========================================================================

    private enum AxiomKind {
        CONCEPT_INCLUSION, CONCEPT_ASSERTION,
        ROLE_INCLUSION, ROLE_ASSERTION, ROLE_TRANSITIVITY
    }

    private static class GeneratedAxiom {
        public final String   id;
        public final AxiomKind kind;
        public final boolean  negated;
        public final String   nameA;
        public final String   nameB;
        public final String   nameC;
        public final String   modalOp;
        public final String   modalStandpoint;
        public final boolean  modalInnerNeg;

        public GeneratedAxiom(String id, AxiomKind kind, boolean negated,
                              String nameA, String nameB, String nameC,
                              String modalOp, String modalStandpoint,
                              boolean modalInnerNeg) {
            this.id              = id;
            this.kind            = kind;
            this.negated         = negated;
            this.nameA           = nameA;
            this.nameB           = nameB;
            this.nameC           = nameC;
            this.modalOp         = modalOp;
            this.modalStandpoint = modalStandpoint;
            this.modalInnerNeg   = modalInnerNeg;
        }

        public boolean hasModal() { return modalOp != null; }
    }

    // ── Axiom generators ─────────────────────────────────────────────────────

    private GeneratedAxiom generateCI() {
        return new GeneratedAxiom(freshAxiomId(),
                AxiomKind.CONCEPT_INCLUSION, new Random().nextBoolean(),
                freshConcept(), freshConcept(), null,
                null, null, false);
    }

    private GeneratedAxiom generateNestedCI() {
        Random rand = new Random();
        return new GeneratedAxiom(freshAxiomId(),
                AxiomKind.CONCEPT_INCLUSION, rand.nextBoolean(),
                freshConcept(), freshConcept(), null,
                rand.nextBoolean() ? "box" : "diamond",
                freshStandpoint(),
                rand.nextBoolean());
    }

    private GeneratedAxiom generateCA() {
        return new GeneratedAxiom(freshAxiomId(),
                AxiomKind.CONCEPT_ASSERTION, new Random().nextBoolean(),
                freshIndividual(), freshConcept(), null,
                null, null, false);
    }

    private GeneratedAxiom generateRI() {
        return new GeneratedAxiom(freshAxiomId(),
                AxiomKind.ROLE_INCLUSION, new Random().nextBoolean(),
                freshRole(), freshRole(), null,
                null, null, false);
    }

    private GeneratedAxiom generateRA() {
        return new GeneratedAxiom(freshAxiomId(),
                AxiomKind.ROLE_ASSERTION, new Random().nextBoolean(),
                freshIndividual(), freshRole(), freshIndividual(),
                null, null, false);
    }

    private GeneratedAxiom generateRT() {
        return new GeneratedAxiom(freshAxiomId(),
                AxiomKind.ROLE_TRANSITIVITY, new Random().nextBoolean(),
                freshRole(), null, null,
                null, null, false);
    }

    // ── OWL axiom builder — no string splitting ───────────────────────────────

    private OWLAxiom buildOWLAxiom(GeneratedAxiom ax,
                                   OWLDataFactory df, String base) {
        switch (ax.kind) {
            case CONCEPT_INCLUSION: {
                OWLClass sub = df.getOWLClass(IRI.create(base + ax.nameA));
                OWLClass sup = df.getOWLClass(IRI.create(base + ax.nameB));
                return df.getOWLSubClassOfAxiom(sub, sup);
            }
            case CONCEPT_ASSERTION: {
                OWLNamedIndividual ind =
                        df.getOWLNamedIndividual(IRI.create(base + ax.nameA));
                OWLClass cls = df.getOWLClass(IRI.create(base + ax.nameB));
                return df.getOWLClassAssertionAxiom(cls, ind);
            }
            case ROLE_INCLUSION: {
                OWLObjectProperty S =
                        df.getOWLObjectProperty(IRI.create(base + ax.nameA));
                OWLObjectProperty R =
                        df.getOWLObjectProperty(IRI.create(base + ax.nameB));
                return df.getOWLSubObjectPropertyOfAxiom(S, R);
            }
            case ROLE_ASSERTION: {
                OWLNamedIndividual a =
                        df.getOWLNamedIndividual(IRI.create(base + ax.nameA));
                OWLObjectProperty r =
                        df.getOWLObjectProperty(IRI.create(base + ax.nameB));
                OWLNamedIndividual b =
                        df.getOWLNamedIndividual(IRI.create(base + ax.nameC));
                return df.getOWLObjectPropertyAssertionAxiom(r, a, b);
            }
            case ROLE_TRANSITIVITY: {
                OWLObjectProperty r =
                        df.getOWLObjectProperty(IRI.create(base + ax.nameA));
                return df.getOWLTransitiveObjectPropertyAxiom(r);
            }
            default: throw new IllegalArgumentException("Unknown kind: " + ax.kind);
        }
    }

    // ── Annotation string builder — assembled from stored names ───────────────

    private String buildAxiomAnnotation(GeneratedAxiom ax) {
        StringBuilder body = new StringBuilder();
        switch (ax.kind) {
            case CONCEPT_INCLUSION:
                if (ax.hasModal()) {
                    String inner = ax.modalInnerNeg
                            ? " not ( " + ax.nameA + " ) " : ax.nameA;
                    body.append("<modal op=\"").append(ax.modalOp)
                            .append("\" standpoint=\"").append(ax.modalStandpoint)
                            .append("\">").append(inner).append("</modal>")
                            .append(" SubClassOf: ").append(ax.nameB);
                } else {
                    body.append(ax.nameA).append(" SubClassOf: ").append(ax.nameB);
                }
                break;
            case CONCEPT_ASSERTION:
                body.append(ax.nameA).append(" Type: ").append(ax.nameB);
                break;
            case ROLE_INCLUSION:
                body.append(ax.nameA).append(" SubPropertyOf: ").append(ax.nameB);
                break;
            case ROLE_ASSERTION:
                body.append("Individual: ").append(ax.nameA)
                        .append(" Facts: ").append(ax.nameB)
                        .append(" ").append(ax.nameC);
                break;
            case ROLE_TRANSITIVITY:
                body.append("Transitive ").append(ax.nameA);
                break;
        }
        return "<axiom id=\"" + ax.id + "\">" + body + "</axiom>";
    }

    // =========================================================================
    // Shared infrastructure
    // =========================================================================

    private List<String> extractStandpoints(List<GeneratedFormula> formulas) {
        List<String> out = new ArrayList<>();
        for (GeneratedFormula f : formulas) out.add(f.standpoint);
        return out;
    }

    private int countFreshConcepts(StandpointKnowledgeBase result) {
        Set<String> found = new HashSet<>();
        for (NormalisedAxiom na : result.owlMap.values()) {
            Set<OWLClass> classes = na.isRoot && na.owlAxiom != null
                    ? na.owlAxiom.getClassesInSignature()
                    : na.owlTree != null
                    ? na.owlTree.getClassesInSignature()
                    : Collections.emptySet();
            for (OWLClass cls : classes) {
                String s = cls.getIRI().getShortForm();
                if (s.startsWith(PlaceholderType.FRESH_CONCEPT.prefix)) found.add(s);
            }
        }
        return found.size();
    }

    private int countFreshRoles(StandpointKnowledgeBase result) {
        Set<String> found = new HashSet<>();
        for (NormalisedAxiom na : result.owlMap.values()) {
            if (na.owlAxiom == null) continue;
            for (OWLObjectProperty p :
                    na.owlAxiom.getObjectPropertiesInSignature()) {
                String s = p.getIRI().getShortForm();
                if (s.startsWith(PlaceholderType.FRESH_ROLE.prefix)) found.add(s);
            }
        }
        return found.size();
    }

    // ── Formula / sharpening generators ──────────────────────────────────────

    private static class GeneratedFormula {
        public final String operator, standpoint;
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
            int remainF   = numFormulas - i;
            int maxCount  = remaining - (remainF - 1);
            int count     = maxCount <= 1 ? 1 : 1 + rand.nextInt(maxCount);
            List<GeneratedAxiom> lits = new ArrayList<>();
            for (int j = 0; j < count && idx < shuffled.size(); j++, idx++)
                lits.add(shuffled.get(idx));
            formulas.add(new GeneratedFormula(op, sp, lits));
        }
        return formulas;
    }

    private enum SharpeningKind { NORMAL, ZERO, NEGATED }

    private static class GeneratedSharpening {
        public final List<String> lhs;
        public final String rhs;
        public final SharpeningKind kind;

        public GeneratedSharpening(List<String> lhs, String rhs,
                                   SharpeningKind kind) {
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
        Set<String> used = new HashSet<>();
        for (int i = 0; i < numNormal && pool.size() >= 2; i++)
            tryAddSharpening(pool, rand, used, result, "N", false, false);
        for (int i = 0; i < numZero && pool.size() >= 1; i++)
            tryAddSharpening(pool, rand, used, result, "Z", true, false);
        for (int i = 0; i < numNegated && pool.size() >= 2; i++)
            tryAddSharpening(pool, rand, used, result, "NG", false, true);
        return result;
    }

    private void tryAddSharpening(List<String> pool, Random rand,
                                  Set<String> used,
                                  List<GeneratedSharpening> result,
                                  String prefix,
                                  boolean isZero, boolean isNegated) {
        for (int attempt = 0; attempt < 10; attempt++) {
            Collections.shuffle(pool, rand);
            int maxLhs = isZero ? pool.size() : pool.size() - 1;
            if (maxLhs < 1) return;
            int n = 1 + rand.nextInt(Math.min(3, maxLhs));
            List<String> lhs = new ArrayList<>(pool.subList(0, n));
            String rhs = isZero ? "0" : pool.get(n);
            String key = prefix + lhs + rhs;
            if (!used.contains(key)) {
                used.add(key);
                SharpeningKind kind = isNegated ? SharpeningKind.NEGATED
                        : isZero ? SharpeningKind.ZERO : SharpeningKind.NORMAL;
                result.add(new GeneratedSharpening(lhs, rhs, kind));
                return;
            }
        }
    }

    // ── Expected count computation (Pipeline 1) ───────────────────────────────

    private static class ExpectedNormalisationCounts {
        public int axiomCount = 0, sharpeningCount = 0,
                freshConceptCount = 0, freshRoleCount = 0;
    }

    private ExpectedNormalisationCounts computeExpectedNormalisation(
            List<GeneratedAxiom> allAxioms,
            List<GeneratedFormula> formulas,
            List<GeneratedSharpening> sharpenings) {

        ExpectedNormalisationCounts counts = new ExpectedNormalisationCounts();

        Set<String> referenced = new HashSet<>();
        for (GeneratedFormula f : formulas)
            for (GeneratedAxiom a : f.literals)
                referenced.add(a.id);

        for (GeneratedAxiom a : allAxioms)
            if (!referenced.contains(a.id))
                counts.axiomCount++;

        for (GeneratedFormula formula : formulas) {
            if ("diamond".equals(formula.operator)) counts.sharpeningCount++;
            for (GeneratedAxiom ax : formula.literals) {
                if (ax.negated) {
                    switch (ax.kind) {
                        case CONCEPT_INCLUSION:
                            counts.axiomCount += 3;
                            counts.freshConceptCount++;
                            counts.freshRoleCount++;
                            break;
                        case CONCEPT_ASSERTION:
                            counts.axiomCount++;
                            break;
                        case ROLE_INCLUSION:
                            counts.axiomCount += 3;
                            counts.freshConceptCount += 2;
                            counts.freshRoleCount++;
                            break;
                        case ROLE_ASSERTION:
                            counts.axiomCount += 3;
                            counts.freshConceptCount += 2;
                            break;
                        case ROLE_TRANSITIVITY:
                            counts.axiomCount += 3;
                            counts.freshConceptCount += 2;
                            counts.freshRoleCount++;
                            break;
                    }
                } else {
                    counts.axiomCount++;
                }
            }
        }

        for (GeneratedSharpening s : sharpenings) {
            int n = s.lhs.size();
            switch (s.kind) {
                case NORMAL:  counts.sharpeningCount++;                       break;
                case ZERO:    counts.axiomCount += n + 1;
                    counts.freshConceptCount += n;                  break;
                case NEGATED: counts.sharpeningCount += n;
                    counts.axiomCount += 3;
                    counts.freshConceptCount += 2;                  break;
            }
        }
        return counts;
    }

    // ── Ontology builder ─────────────────────────────────────────────────────

    private OWLOntology buildOntology(
            List<GeneratedAxiom> axioms,
            List<GeneratedFormula> formulas,
            List<GeneratedSharpening> sharpenings) throws Exception {

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(
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

        for (GeneratedAxiom ax : axioms) {
            OWLAxiom owlAxiom = buildOWLAxiom(ax, df, base);
            manager.addAxiom(ontology,
                    owlAxiom.getAnnotatedAxiom(Collections.singleton(
                            df.getOWLAnnotation(axiomProp,
                                    df.getOWLLiteral(buildAxiomAnnotation(ax))))));
        }
        for (GeneratedFormula f : formulas)
            manager.applyChange(new AddOntologyAnnotation(ontology,
                    df.getOWLAnnotation(formulaProp,
                            df.getOWLLiteral(buildFormulaXml(f)))));
        for (GeneratedSharpening s : sharpenings)
            manager.applyChange(new AddOntologyAnnotation(ontology,
                    df.getOWLAnnotation(sharpeningProp,
                            df.getOWLLiteral(buildSharpeningXml(s)))));
        return ontology;
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
        sb.append(s.kind == SharpeningKind.NEGATED
                ? "<sharpening negated=\"true\">" : "<sharpening>");
        sb.append("<lhs>");
        if (s.lhs.size() == 1)
            sb.append("<standpoint>").append(s.lhs.get(0)).append("</standpoint>");
        else {
            sb.append("<intersection>");
            for (String sp : s.lhs)
                sb.append("<standpoint>").append(sp).append("</standpoint>");
            sb.append("</intersection>");
        }
        sb.append("</lhs><rhs>");
        if ("0".equals(s.rhs)) sb.append("<zero/>");
        else sb.append("<standpoint>").append(s.rhs).append("</standpoint>");
        sb.append("</rhs></sharpening>");
        return sb.toString();
    }
}
