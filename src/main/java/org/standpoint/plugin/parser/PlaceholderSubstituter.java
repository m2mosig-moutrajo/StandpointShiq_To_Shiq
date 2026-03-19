package org.standpoint.plugin.parser;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import org.xml.sax.InputSource;
import java.io.StringReader;
import java.util.*;

public class PlaceholderSubstituter {

    public enum Operator { BOX, DIAMOND }

    public static class PlaceholderEntry {
        public Operator operator;
        public String standpoint;
        public String manchester;
        public boolean isRoot = false;

        public PlaceholderEntry(Operator operator, String standpoint, String manchesterExpression) {
            this.operator = operator;
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

            // Wrap in root so XML parser can handle single root element
            String wrappedXml = "<root>" + standpointLabelXml + "</root>";
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setIgnoringElementContentWhitespace(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(wrappedXml)));

            Node rootNode = doc.getDocumentElement(); // <root>

            // Process the single child — the outermost <modal>
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

        // Text node — return as plain Manchester
        if (node.getNodeType() == Node.TEXT_NODE) {
            return node.getTextContent().trim();
        }

        // Not a modal element — return text content
        if (node.getNodeType() != Node.ELEMENT_NODE
                || !node.getNodeName().equals("modal")) {
            return node.getTextContent().trim();
        }

        // Extract modal attributes
        NamedNodeMap attrs = node.getAttributes();
        String modalOp    = attrs.getNamedItem("op").getNodeValue();
        String standpoint = attrs.getNamedItem("standpoint").getNodeValue();
        Node negatedAttr  = attrs.getNamedItem("negated");
        boolean isNegated = negatedAttr != null && "true".equals(negatedAttr.getNodeValue());

        // Process children — mix of text nodes and nested modal elements
        StringBuilder innerManchester = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                innerManchester.append(child.getTextContent());
            } else if (child.getNodeType() == Node.ELEMENT_NODE
                    && child.getNodeName().equals("modal")) {
                // Nested modal — recurse and get placeholder key
                innerManchester.append(processNode(child));
            }
        }

        String processedInner = innerManchester.toString().trim();

        // Apply modal duality if negated: ¬box → diamond(¬inner), ¬diamond → box(¬inner)
        Operator operator;
        String manchesterExpression;
        if (isNegated) {
            operator = "box".equals(modalOp) ? Operator.DIAMOND : Operator.BOX;
            manchesterExpression = "not (" + processedInner + ")";
        } else {
            operator = "box".equals(modalOp) ? Operator.BOX : Operator.DIAMOND;
            manchesterExpression = processedInner;
        }

        String placeholderKey = PlaceholderUtil.generate();
        placeholderMap.put(placeholderKey,
                new PlaceholderEntry(operator, standpoint, manchesterExpression));

        return placeholderKey;
    }
}