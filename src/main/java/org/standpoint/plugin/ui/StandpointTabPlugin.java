package org.standpoint.plugin.ui;

import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.normalisation.Normaliser;
import org.standpoint.plugin.parser.PlaceholderSubstituter;
import org.standpoint.plugin.translation.OntologyExporter;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Map;
import java.util.Set;

public class StandpointTabPlugin extends AbstractOWLViewComponent {

    private JLabel statusLabel;
    private JTextArea resultArea;

    @Override
    protected void initialiseOWLView() throws Exception {
        setLayout(new BorderLayout());

        JLabel title = new JLabel("Standpoint SHIQ Translator", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 16));

        JButton translateButton = new JButton("Translate to SHIQ");
        translateButton.addActionListener(e -> onTranslateClicked());

        JButton clearButton = new JButton("Clear");
        clearButton.addActionListener(e -> {
            resultArea.setText("");
            statusLabel.setText("Ready.");
        });

        statusLabel = new JLabel("Ready. Test45", SwingConstants.CENTER);

        resultArea = new JTextArea();
        resultArea.setEditable(false);
        resultArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(resultArea);

        JPanel topPanel = new JPanel(new GridLayout(4, 1, 5, 5));
        topPanel.add(title);
        topPanel.add(translateButton);
        topPanel.add(clearButton);
        topPanel.add(statusLabel);

        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    private void onTranslateClicked() {
        try {
            OWLOntology ontology = getOWLModelManager().getActiveOntology();
            OWLDataFactory df = getOWLModelManager().getOWLDataFactory();

            // Normalise
            Normaliser normaliser = new Normaliser(df);
            Set<OWLSubClassOfAxiom> original = ontology.getAxioms(AxiomType.SUBCLASS_OF);
            Set<OWLSubClassOfAxiom> normalised = normaliser.normaliseAllGCIs(original);

            // Export
            OntologyExporter exporter = new OntologyExporter();
            File outputFile = exporter.export(ontology, normalised);

            // Display
            resultArea.setText(buildReport(original, normalised, outputFile));
            statusLabel.setText("Done — " + outputFile.getName());

//            String formula = "box[s1]( A or not_diamond[s2]( (B and C) or (v only I) ) SubClassOf E or not_box[s2]( (not K) and diamond[s3](r some G) ) or (h max 3 diamond[s4](M)))";
//
//            PlaceholderSubstituter sub = new PlaceholderSubstituter();
//            String substituted = sub.substitute(formula);
//
//            StringBuilder sb = new StringBuilder();
//            sb.append("=== SUBSTITUTED ===\n\n").append(substituted).append("\n\n");
//            sb.append("=== MAP ===\n\n");
//            for (Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry> e : sub.getMap().entrySet()) {
//                sb.append(e.getKey()).append(" → ").append(e.getValue()).append("\n");
//            }
//            resultArea.setText(sb.toString());

        } catch (Exception ex) {
            statusLabel.setText("Error: " + ex.getMessage());
            resultArea.setText("Error:\n" + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private String buildReport(Set<OWLSubClassOfAxiom> original,
                               Set<OWLSubClassOfAxiom> normalised,
                               File outputFile) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== ORIGINAL ===\n\n");
        for (OWLSubClassOfAxiom ax : original) {
            sb.append(ax.getSubClass())
                    .append("\n  ⊑  ")
                    .append(ax.getSuperClass())
                    .append("\n\n");
        }

        sb.append("=== NORMALISED ===\n\n");
        for (OWLSubClassOfAxiom ax : normalised) {
            sb.append("⊤  ⊑  ")
                    .append(ax.getSuperClass())
                    .append("\n\n");
        }

        sb.append("=== SAVED TO ===\n\n")
                .append(outputFile.getAbsolutePath());

        return sb.toString();
    }

    @Override
    protected void disposeOWLView() {
    }
}