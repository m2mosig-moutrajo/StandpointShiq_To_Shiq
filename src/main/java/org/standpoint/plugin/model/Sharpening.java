package org.standpoint.plugin.model;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

public class Sharpening {
    public final List<String> lhsStandpoints;  // s1, s2, ..., sn
    public final String rhsStandpoint;          // s, "0",
    public final boolean isNegated;             // true for Rule (8) — ¬(s1 ∩ ... ∩ sn ⪯ u)

    public Sharpening(List<String> lhsStandpoints, String rhsStandpoint) {
        this.lhsStandpoints = lhsStandpoints;
        this.rhsStandpoint  = rhsStandpoint;
        this.isNegated      = false;
    }

    public Sharpening(List<String> lhsStandpoints, String rhsStandpoint,
                      boolean isNegated) {
        this.lhsStandpoints = lhsStandpoints;
        this.rhsStandpoint  = rhsStandpoint;
        this.isNegated      = isNegated;
    }

    public boolean isZero() {
        return "0".equals(rhsStandpoint);
    }

    public static Sharpening parse(String xml) {
        try {
            String wrapped = "<root>" + xml.trim() + "</root>";
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(wrapped)));

            Node sharpening = doc.getDocumentElement().getFirstChild();

            // Check negated attribute
            NamedNodeMap attrs = sharpening.getAttributes();
            Node negatedAttr = attrs.getNamedItem("negated");
            boolean isNegated = negatedAttr != null
                    && "true".equals(negatedAttr.getNodeValue());

            // Parse LHS
            List<String> lhsStandpoints = new ArrayList<>();
            Node lhsNode = getChildByName(sharpening, "lhs");
            Node intersectionNode = getChildByName(lhsNode, "intersection");

            if (intersectionNode != null) {
                NodeList standpoints = intersectionNode.getChildNodes();
                for (int i = 0; i < standpoints.getLength(); i++) {
                    Node child = standpoints.item(i);
                    if (child.getNodeType() != Node.ELEMENT_NODE) continue;

                    if (child.getNodeName().equals("standpoint")) {
                        lhsStandpoints.add(child.getTextContent().trim());
                    } else if (child.getNodeName().equals("zero")) {
                        lhsStandpoints.add("0");   // ← add zero as "0"
                    }
                }
            } else {
            // Single standpoint or zero
            Node standpointNode = getChildByName(lhsNode, "standpoint");
            Node zeroNodeLhs   = getChildByName(lhsNode, "zero");
            if (standpointNode != null) {
                lhsStandpoints.add(standpointNode.getTextContent().trim());
            } else if (zeroNodeLhs != null) {
                lhsStandpoints.add("0");
            }
        }

            // Parse RHS
            Node rhsNode = getChildByName(sharpening, "rhs");
            Node zeroNode = getChildByName(rhsNode, "zero");
            String rhs;
            if (zeroNode != null) {
                rhs = "0";
            } else {
                Node standpointNode = getChildByName(rhsNode, "standpoint");
                rhs = standpointNode.getTextContent().trim();
            }

            return new Sharpening(lhsStandpoints, rhs, isNegated);

        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid sharpening XML: " + e.getMessage(), e);
        }
    }

    // Helper to get first child element by name
    private static Node getChildByName(Node parent, String name) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && child.getNodeName().equals(name)) {
                return child;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        String lhs = lhsStandpoints.size() == 1
                ? lhsStandpoints.get(0)
                : String.join(" ∩ ", lhsStandpoints);
        String base = lhs + " ⪯ " + rhsStandpoint;
        return isNegated ? "¬(" + base + ")" : base;
    }
}