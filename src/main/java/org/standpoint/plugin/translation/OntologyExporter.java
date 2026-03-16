package org.standpoint.plugin.translation;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.*;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class OntologyExporter {

    public File export(OWLOntology original,
                       Set<OWLSubClassOfAxiom> normalisedAxioms)
            throws OWLOntologyCreationException,
            OWLOntologyStorageException {

        OWLOntologyManager newManager = OWLManager.createOWLOntologyManager();
        OWLOntology newOntology = newManager.createOntology(
                IRI.create("http://standpoint.org/normalised")
        );

        // Copy everything except SubClassOf axioms
        Set<OWLAxiom> axiomsToKeep = new HashSet<>();
        for (OWLAxiom ax : original.getAxioms()) {
            if (!(ax instanceof OWLSubClassOfAxiom)) {
                axiomsToKeep.add(ax);
            }
        }
        newManager.addAxioms(newOntology, axiomsToKeep);
        newManager.addAxioms(newOntology, normalisedAxioms);

        File outputFile = resolveOutputFile(original);

        newManager.saveOntology(
                newOntology,
                new RDFXMLDocumentFormat(),
                IRI.create(outputFile.toURI())
        );

        return outputFile;
    }

    private File resolveOutputFile(OWLOntology original) {
        try {
            // Try to get the physical document IRI (actual file location)
            IRI docIRI = original.getOWLOntologyManager()
                    .getOntologyDocumentIRI(original);

            if (docIRI != null && docIRI.toString().startsWith("file:")) {
                File originalFile = new File(docIRI.toURI());
                String name = originalFile.getName()
                        .replace(".owl", "_normalised.owl")
                        .replace(".rdf", "_normalised.rdf");
                if (!name.contains("_normalised")) {
                    name = name + "_normalised.owl";
                }
                return new File(originalFile.getParent(), name);
            }
        } catch (Exception e) {
            // fall through to default
        }

        // Fallback — save to user home directory
        return new File(System.getProperty("user.home"), "normalised.owl");
    }
}