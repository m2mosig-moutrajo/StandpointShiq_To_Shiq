package org.standpoint.plugin.normaliser;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.standpoint.plugin.model.Operator;
import org.standpoint.plugin.model.ModalPlaceholder;
import org.standpoint.plugin.model.PlaceholderType;
import org.standpoint.plugin.model.StandpointAxiomType;
import org.standpoint.plugin.util.PlaceholderCounter;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import org.xml.sax.InputSource;
import java.io.StringReader;
import java.util.*;

public class ModalExpressionDecomposer {

    private final PlaceholderCounter placeholderCounter;

    private final Map<String, ModalPlaceholder> placeholderMap = new LinkedHashMap<>();

    public ModalExpressionDecomposer(PlaceholderCounter placeholderCounter) {  // ADD
        this.placeholderCounter = placeholderCounter;
    }

    public Map<String, ModalPlaceholder> getMap() { return placeholderMap; }

    public String substitute(String standpointLabelXml, StandpointAxiomType rootAxiomType, OWLAxiom originalOwlAxiom) {
        try {
            standpointLabelXml = standpointLabelXml.trim();
            String wrappedXml = "<root>" + standpointLabelXml + "</root>";
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setIgnoringElementContentWhitespace(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(wrappedXml)));

            Node rootNode = doc.getDocumentElement();
            // pass rootAxiomType for root node — nested modals are always NONE
            String rootPlaceholderKey = processNode(rootNode.getFirstChild(), rootAxiomType, originalOwlAxiom);

            ModalPlaceholder rootEntry = placeholderMap.get(rootPlaceholderKey);
            if (rootEntry != null) rootEntry.isRoot = true;

            return rootPlaceholderKey;

        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid modal XML: " + e.getMessage(), e);
        }
    }

    private String processNode(Node node, StandpointAxiomType axiomType, OWLAxiom originalOwlAxiom) {
        if (node == null) return "";

        if (node.getNodeType() == Node.TEXT_NODE) {
            return node.getTextContent().trim();
        }

        if (node.getNodeType() != Node.ELEMENT_NODE
                || !node.getNodeName().equals("modal")) {
            return node.getTextContent().trim();
        }

        NamedNodeMap attrs = node.getAttributes();
        String modalOp = attrs.getNamedItem("op").getNodeValue();
        String standpoint = attrs.getNamedItem("standpoint").getNodeValue();
        Node negatedAttr = attrs.getNamedItem("negated");
        Node negatedInnerAttr = attrs.getNamedItem("negatedInner");
        boolean isNegated = negatedAttr != null && "true".equals(negatedAttr.getNodeValue());
        boolean isNegatedInner = negatedInnerAttr != null && "true".equals(negatedInnerAttr.getNodeValue());

        StringBuilder innerManchester = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                innerManchester.append(child.getTextContent());
            } else if (child.getNodeType() == Node.ELEMENT_NODE
                    && child.getNodeName().equals("modal")) {
                // nested modals are always NONE — they are concept-level sub-expressions
                innerManchester.append(processNode(child, StandpointAxiomType.NONE, null));
            }
        }

        String processedInner = innerManchester.toString().trim();

        try {
            validateParentBalance(processedInner);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Malformed content in standpoint annotation: " + e.getMessage());
        }

        Operator operator;
        ModalPlaceholder entry;
        if (isNegated && isNegatedInner) {
            // ¬□_s[¬X] → ◇_s[X],  ¬◇_s[¬X] → □_s[X]
            operator = Operator.BOX.toString().toLowerCase().equals(modalOp) ? Operator.DIAMOND : Operator.BOX;
            entry = buildPlaceholder(operator, standpoint, processedInner, originalOwlAxiom);

        } else if (isNegated) {
            operator = Operator.BOX.toString().toLowerCase().equals(modalOp) ? Operator.DIAMOND : Operator.BOX;
            // use type — NONE means concept-level, anything else is axiom-level
            boolean isAxiomContent = axiomType != StandpointAxiomType.NONE;

            if (isAxiomContent) {
                entry = buildPlaceholder(operator, standpoint, processedInner, originalOwlAxiom);
                entry.isNegatedInner = true;
            } else {
                // ¬□_s[C] = ◇_s[¬C],  ¬◇_s[C] = □_s[¬C]
                String manchesterExpression = "not (" + processedInner + ")";
                entry = new ModalPlaceholder(operator, standpoint, manchesterExpression);
            }

        } else if (isNegatedInner) {
            operator = Operator.BOX.toString().toLowerCase().equals(modalOp) ? Operator.BOX : Operator.DIAMOND;
            entry = buildPlaceholder(operator, standpoint, processedInner, originalOwlAxiom);
            entry.isNegatedInner = true;

        } else {
            operator = Operator.BOX.toString().toLowerCase().equals(modalOp) ? Operator.BOX : Operator.DIAMOND;
            entry = buildPlaceholder(operator, standpoint, processedInner, originalOwlAxiom);
        }

        String placeholderKey = placeholderCounter.generate(PlaceholderType.MODAL_PLACEHOLDER);
        placeholderMap.put(placeholderKey, entry);
        return placeholderKey;
    }

    private ModalPlaceholder buildPlaceholder(Operator operator, String standpoint, String manchesterExpression, OWLAxiom originalOwlAxiom) {
        if ((manchesterExpression == null || manchesterExpression.isEmpty()) && originalOwlAxiom != null) {
                return new ModalPlaceholder(operator, standpoint, originalOwlAxiom);
        }
        return new ModalPlaceholder(operator, standpoint, manchesterExpression);
    }

    private void validateParentBalance(String expr) {
        int depth = 0;
        for (char c : expr.toCharArray()) {
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (depth < 0) throw new IllegalArgumentException(
                    "Unmatched closing parenthesis: '" + expr + "'");
        }
        if (depth != 0) throw new IllegalArgumentException(
                "Unclosed parenthesis: '" + expr + "'");
    }
}