package org.standpoint.plugin.parser;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import org.xml.sax.InputSource;
import java.io.StringReader;
import java.util.*;

public class PlaceholderSubstituter {

    public enum Operator { BOX, DIAMOND }

    public static class PlaceholderEntry {

        public enum StandpointAxiomType {
            NONE,
            GCI,
            ASSERTION,
            ROLE_INCLUSION,
            TRANSITIVITY
        }

        public Operator operator;
        public String standpoint;
        public String manchester;
        public boolean isRoot         = false;
        public boolean isNegatedAxiom = false;
        public StandpointAxiomType standpointAxiomType = StandpointAxiomType.NONE;

        public PlaceholderEntry(Operator operator, String standpoint, String manchesterExpression) {
            this.operator   = operator;
            this.standpoint = standpoint;
            this.manchester = manchesterExpression;
        }

        @Override
        public String toString() {
            String opName = operator == Operator.BOX ? "box" : "diamond";
            return "<modal op=\"" + opName + "\" standpoint=\"" + standpoint + "\">"
                    + manchester + "</modal>"
                    + (isRoot ? " [ROOT]" : "");
        }
    }

    private final Map<String, PlaceholderEntry> placeholderMap = new LinkedHashMap<>();

    public Map<String, PlaceholderEntry> getMap() { return placeholderMap; }

    public String substitute(String standpointLabelXml) {
        try {
            standpointLabelXml = standpointLabelXml.trim();

            String wrappedXml = "<root>" + standpointLabelXml + "</root>";
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setIgnoringElementContentWhitespace(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(wrappedXml)));

            Node rootNode = doc.getDocumentElement();
            String rootPlaceholderKey = processNode(rootNode.getFirstChild());

            PlaceholderEntry rootEntry = placeholderMap.get(rootPlaceholderKey);
            if (rootEntry != null) rootEntry.isRoot = true;

            return rootPlaceholderKey;

        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid modal XML: " + e.getMessage(), e);
        }
    }

    private String processNode(Node node) {
        if (node == null) return "";

        if (node.getNodeType() == Node.TEXT_NODE) {
            return node.getTextContent().trim();
        }

        if (node.getNodeType() != Node.ELEMENT_NODE
                || !node.getNodeName().equals("modal")) {
            return node.getTextContent().trim();
        }

        NamedNodeMap attrs      = node.getAttributes();
        String modalOp          = attrs.getNamedItem("op").getNodeValue();
        String standpoint       = attrs.getNamedItem("standpoint").getNodeValue();
        Node negatedAttr        = attrs.getNamedItem("negated");
        Node negatedInnerAttr   = attrs.getNamedItem("negatedInner");
        boolean isNegated       = negatedAttr != null
                && "true".equals(negatedAttr.getNodeValue());
        boolean isNegatedInner  = negatedInnerAttr != null
                && "true".equals(negatedInnerAttr.getNodeValue());

        StringBuilder innerManchester = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                innerManchester.append(child.getTextContent());
            } else if (child.getNodeType() == Node.ELEMENT_NODE
                    && child.getNodeName().equals("modal")) {
                innerManchester.append(processNode(child));
            }
        }

        String processedInner = innerManchester.toString().trim();

        // Detect axiom type
        PlaceholderEntry.StandpointAxiomType axiomType;
        if (processedInner.contains("SubClassOf")) {
            axiomType = PlaceholderEntry.StandpointAxiomType.GCI;
        } else if (processedInner.contains(" Type ")
                && !processedInner.contains("SubClassOf")) {
            axiomType = PlaceholderEntry.StandpointAxiomType.ASSERTION;
        } else if (processedInner.contains("SubPropertyOf")) {
            axiomType = PlaceholderEntry.StandpointAxiomType.ROLE_INCLUSION;
        } else {
            axiomType = PlaceholderEntry.StandpointAxiomType.NONE;
        }

        Operator operator;
        String manchesterExpression;
        PlaceholderEntry entry;

        if (isNegated && isNegatedInner) {
            // ¬□_s[¬X] → ◇_s[X], ¬◇_s[¬X] → □_s[X]  (double negation)
            operator = "box".equals(modalOp) ? Operator.DIAMOND : Operator.BOX;
            manchesterExpression = processedInner;
            entry = new PlaceholderEntry(operator, standpoint, manchesterExpression);
            entry.standpointAxiomType = axiomType;

        } else if (isNegated) {
            // ¬□_s[X] → ◇_s[...], ¬◇_s[X] → □_s[...]
            operator = "box".equals(modalOp) ? Operator.DIAMOND : Operator.BOX;
            if (axiomType == PlaceholderEntry.StandpointAxiomType.GCI
                    || axiomType == PlaceholderEntry.StandpointAxiomType.ASSERTION
                    || axiomType == PlaceholderEntry.StandpointAxiomType.ROLE_INCLUSION) {
                manchesterExpression = processedInner;
                entry = new PlaceholderEntry(operator, standpoint, manchesterExpression);
                entry.isNegatedAxiom      = true;
                entry.standpointAxiomType = axiomType;
            } else {
                manchesterExpression = "not (" + processedInner + ")";
                entry = new PlaceholderEntry(operator, standpoint, manchesterExpression);
            }

        } else if (isNegatedInner) {
            // □_s[¬X] or ◇_s[¬X]
            operator = "box".equals(modalOp) ? Operator.BOX : Operator.DIAMOND;
            if (axiomType == PlaceholderEntry.StandpointAxiomType.GCI
                    || axiomType == PlaceholderEntry.StandpointAxiomType.ASSERTION
                    || axiomType == PlaceholderEntry.StandpointAxiomType.ROLE_INCLUSION) {
                manchesterExpression = processedInner;
                entry = new PlaceholderEntry(operator, standpoint, manchesterExpression);
                entry.isNegatedAxiom      = true;
                entry.standpointAxiomType = axiomType;
            } else {
                manchesterExpression = "not (" + processedInner + ")";
                entry = new PlaceholderEntry(operator, standpoint, manchesterExpression);
            }

        } else {
            // No negation
            operator = "box".equals(modalOp) ? Operator.BOX : Operator.DIAMOND;
            manchesterExpression = processedInner;
            entry = new PlaceholderEntry(operator, standpoint, manchesterExpression);
            entry.standpointAxiomType = axiomType;
        }

        String placeholderKey = PlaceholderUtil.generate();
        placeholderMap.put(placeholderKey, entry);
        return placeholderKey;
    }
}