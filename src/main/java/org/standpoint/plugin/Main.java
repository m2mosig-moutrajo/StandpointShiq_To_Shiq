package org.standpoint.plugin;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.pipeline.*;
import org.standpoint.plugin.translation.*;
import org.standpoint.plugin.util.PipelineLogger;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class Main {

    public static void main(String[] args) throws Exception {
        File inputFile  = new File("C:\\Users\\Omar\\Downloads\\OwlTest\\test2.rdf");
        File outputFile = new File("C:\\Users\\Omar\\Downloads\\OwlTest\\test2Translated.rdf");

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(inputFile);

        // Step 1 — Run pipeline
        NormalisedKnowledgeBase kb = new StandpointPipeline(ontology, PipelineLogger.Level.ON).run();
        if (kb == null) return;

        // Step 2 — Convert to OWL-native representation
        ManchesterToOWLConverter conv = new ManchesterToOWLConverter(kb);
        conv.convert();

        // Step 2b — Deduplicate placeholder map
        OWLDataFactory df = kb.sourceOntology.getOWLOntologyManager().getOWLDataFactory();
        PlaceholderDeduplicator deduplicator = new PlaceholderDeduplicator(kb.owlMap, df);
        kb.canonicalKey = deduplicator.deduplicate();

        // Step 3 — Collect standpoints and diamonds
        PrecisificationCollector collector = new PrecisificationCollector(kb);
        Set<String> standpoints  = collector.collectStandpoints();
        Set<DiamondSubterm> diamonds = collector.collectDiamondSubterms();

        // Step 3b — Canonicalise placeholder IRIs inside each DiamondSubterm's concept.
        deduplicator.resolveDiamondConcepts(diamonds);

        // Step 4 — Build concept map for ALL canonical non-root entries
        // (BOX and DIAMOND) — deduplicates by concept using OWL API equals()
        ConceptMap conceptMap = new ConceptMap();

        for (Map.Entry<String, NormalisedAxiom> e : kb.owlMap.entrySet()) {
            String key         = e.getKey();
            NormalisedAxiom ax = e.getValue();

            // only non-root entries with owlTree
            if (ax.isRoot || ax.owlTree == null) continue;

            // skip duplicates — only process canonical entries
            String canonical = kb.canonicalKey.getOrDefault(key, key);
            if (!canonical.equals(key)) continue;

            // resolve child placeholder IRIs to canonical before comparing
            OWLClassExpression resolved = deduplicator.resolveTree(ax.owlTree, kb.canonicalKey);

            conceptMap.addEntry(key, resolved);
        }

        // assign diamondId on each DiamondSubterm
        for (DiamondSubterm d : diamonds) {
            String dn = conceptMap.getIdForSp(d.placeholderKey);
            if (dn != null) d.diamondId = dn;
        }

        // Step 5 — Compute standpoint closures
        SharpeningClosure closureCalc = new SharpeningClosure(kb.sharpenings, standpoints);
        Map<String, Set<String>> closures = closureCalc.computeAllClosures();

        // Step 6 — Build precisification set
        Set<OWLNamedIndividual> individuals = kb.sourceOntology.getIndividualsInSignature();
        PrecisificationSet precSet = PrecisificationSet.build(standpoints, diamonds, individuals, closures);

        // Step 7 — Build sp_n → D_n flat map (BOX and DIAMOND)
        Map<String, String> spToDiamondId = new LinkedHashMap<>();

        // canonical entries from conceptMap
        kb.owlMap.forEach((key, ax) -> {
            if (ax.isRoot || ax.owlTree == null) return;
            String canonical = kb.canonicalKey.getOrDefault(key, key);
            if (!canonical.equals(key)) return;
            String dn = conceptMap.getIdForSp(key);
            if (dn != null) spToDiamondId.put(key, dn);
        });

        // duplicate entries — map through canonicalKey
        kb.canonicalKey.forEach((spn, canonical) -> {
            if (!spn.equals(canonical) && spToDiamondId.containsKey(canonical)) {
                spToDiamondId.put(spn, spToDiamondId.get(canonical));
            }
        });

        // Step 8 — Run Trans(K)
        AuxiliaryNames aux = new AuxiliaryNames(kb, spToDiamondId, df);
        ConceptTranslator conceptTranslator = new ConceptTranslator(kb, aux, precSet);
        TransK transK = new TransK(kb, precSet, aux, conceptTranslator);
        OWLOntology translated = transK.translate();

        // Step 9 — Save translated ontology to RDF/XML file
        OWLOntologyManager translatedManager = translated.getOWLOntologyManager();
        try {
            // Use RDF/XML format to match the input file format
            translatedManager.saveOntology(translated, new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat(), IRI.create(outputFile.toURI())
            );
            System.out.println("\n Translated ontology saved to: " + outputFile.getAbsolutePath());
            System.out.println("   Axiom count: " + translated.getAxiomCount());
        } catch (OWLOntologyStorageException e) {
            System.err.println("Failed to save translated ontology: " + e.getMessage());
            e.printStackTrace();
        }


        // Print
        // ── CANONICAL KEY MAP ──────────────────────────────────────────────
        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.println("║          CANONICAL KEY MAP               ║");
        System.out.println("╚══════════════════════════════════════════╝");
        long dupCount = kb.canonicalKey.values().stream()
                .filter(v -> kb.canonicalKey.containsKey(v)
                        && !v.equals(kb.canonicalKey.get(v))).count();
        if (kb.canonicalKey.entrySet().stream().noneMatch(e -> !e.getKey().equals(e.getValue()))) {
            System.out.println("  (no duplicates)");
        } else {
            kb.canonicalKey.forEach((k, v) -> {
                if (!k.equals(v))
                    System.out.println("  " + k + " → " + v + "  [duplicate]");
            });
        }

        // ── CONCEPT MAP ────────────────────────────────────────────────────
        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.println("║            CONCEPT MAP (D_n)             ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println("  D_n   op       standpoint   concept");
        System.out.println("  ────────────────────────────────────────────");
        kb.owlMap.forEach((key, ax) -> {
            if (ax.isRoot || ax.owlTree == null) return;
            String canonical = kb.canonicalKey.getOrDefault(key, key);
            if (!canonical.equals(key)) return;
            String dn  = conceptMap.getIdForSp(key);
            String op  = ax.operator == org.standpoint.plugin.model.Operator.BOX
                    ? "□" : "◇";
            System.out.printf("  %-6s %-8s %-12s %s%n",
                    dn, op, ax.standpoint, ax.owlTree);
        });

        // ── DIAMOND SUBTERMS ───────────────────────────────────────────────
        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.println("║         DIAMOND SUBTERMS ST(K)           ║");
        System.out.println("╚══════════════════════════════════════════╝");
        if (diamonds.isEmpty()) {
            System.out.println("  (none)");
        } else {
            System.out.println("  SP_n    D_n    standpoint   concept");
            System.out.println("  ────────────────────────────────────────────");
            diamonds.forEach(d -> System.out.printf("  %-8s %-6s %-12s %s%n",
                    d.placeholderKey, d.diamondId, d.standpoint, d.concept));
        }

        // ── STANDPOINTS ────────────────────────────────────────────────────
        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.println("║           STANDPOINTS NS(K)              ║");
        System.out.println("╚══════════════════════════════════════════╝");
        standpoints.forEach(s -> System.out.println("  " + s));

        // ── CLOSURES ───────────────────────────────────────────────────────
        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.println("║        STANDPOINT CLOSURES t^K           ║");
        System.out.println("╚══════════════════════════════════════════╝");
        closures.forEach((t, c) -> System.out.println("  " + t + "^K = " + c));

        // ── PRECISIFICATIONS ───────────────────────────────────────────────
        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.println("║     PRECISIFICATIONS Π_K  (" + precSet.size() + ")");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println("  STANDPOINT worlds:");
        precSet.getAllPrecisifications().stream()
                .filter(p -> p.type == org.standpoint.plugin.model.PrecisificationType.STANDPOINT)
                .forEach(p -> System.out.println("    " + p));
        System.out.println("  ANONYMOUS worlds:");
        precSet.getAllPrecisifications().stream()
                .filter(p -> p.type == org.standpoint.plugin.model.PrecisificationType.ANONYMOUS_0
                        || p.type == org.standpoint.plugin.model.PrecisificationType.ANONYMOUS_1)
                .forEach(p -> System.out.println("    " + p));
        System.out.println("  NAMED worlds:");
        precSet.getAllPrecisifications().stream()
                .filter(p -> p.type == org.standpoint.plugin.model.PrecisificationType.NAMED)
                .forEach(p -> System.out.println("    " + p));

        // ── SIGMA ──────────────────────────────────────────────────────────
        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.println("║           σ PER STANDPOINT               ║");
        System.out.println("╚══════════════════════════════════════════╝");
        standpoints.forEach(s ->
                System.out.println("  σ(" + s + ") = " + precSet.sigma(s)));

        // ── SP_N → D_N MAP ─────────────────────────────────────────────────
        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.println("║          SP_n → D_n RESOLUTION           ║");
        System.out.println("╚══════════════════════════════════════════╝");
        spToDiamondId.forEach((sp, dn) ->
                System.out.println("  " + sp + " → " + dn));

        // ── TRANS(K) RESULT ────────────────────────────────────────────────
        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.println("║            TRANS(K) RESULT               ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println("  Total axioms: " + translated.getAxiomCount());
        System.out.println("\n  -- Type (1): Auxiliary definitions --");
        translated.getAxioms().stream()
                .filter(a -> a instanceof org.semanticweb.owlapi.model.OWLSubClassOfAxiom)
                .map(a -> (org.semanticweb.owlapi.model.OWLSubClassOfAxiom) a)
                .filter(a -> a.getSubClass() instanceof org.semanticweb.owlapi.model.OWLClass
                        && a.getSubClass().asOWLClass().getIRI().getShortForm().startsWith("AUX_"))
                .forEach(a -> System.out.println("    " + a));
        System.out.println("\n  -- Type (2)-(6): Root axiom translations --");
        translated.getAxioms().stream()
                .filter(a -> a instanceof org.semanticweb.owlapi.model.OWLSubClassOfAxiom)
                .map(a -> (org.semanticweb.owlapi.model.OWLSubClassOfAxiom) a)
                .filter(a -> a.getSubClass().isOWLThing())
                .forEach(a -> System.out.println("    " + a));
        translated.getAxioms().stream()
                .filter(a -> !(a instanceof org.semanticweb.owlapi.model.OWLSubClassOfAxiom))
                .forEach(a -> System.out.println("    " + a));
    }
}