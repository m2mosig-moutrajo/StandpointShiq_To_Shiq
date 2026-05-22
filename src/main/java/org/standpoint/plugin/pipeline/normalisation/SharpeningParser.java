package org.standpoint.plugin.pipeline.normalisation;

import org.standpoint.plugin.model.Sharpening;
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

public class SharpeningParser {

    public Sharpening parse(String xml) {
        try {
            String wrapped = "<root>" + xml.trim() + "</root>";

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(wrapped)));

            Node sharpening = doc.getDocumentElement().getFirstChild();

            NamedNodeMap attrs = sharpening.getAttributes();
            Node negatedAttr = attrs.getNamedItem("negated");

            boolean isNegated = negatedAttr != null
                    && "true".equals(negatedAttr.getNodeValue());

            List<String> lhs = parseLHS(sharpening);
            String rhs = parseRHS(sharpening);

            return new Sharpening(lhs, rhs, isNegated);

        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid sharpening XML", e);
        }
    }

    private List<String> parseLHS(Node sharpening) {
        List<String> lhs = new ArrayList<>();

        Node lhsNode = getChildByName(sharpening, "lhs");
        Node intersectionNode = getChildByName(lhsNode, "intersection");

        if (intersectionNode != null) {
            NodeList children = intersectionNode.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() != Node.ELEMENT_NODE) continue;

                if ("standpoint".equals(child.getNodeName())) {
                    lhs.add(child.getTextContent().trim());
                } else if ("zero".equals(child.getNodeName())) {
                    lhs.add("0");
                }
            }
        } else {
            Node sp = getChildByName(lhsNode, "standpoint");
            Node z = getChildByName(lhsNode, "zero");

            if (sp != null) lhs.add(sp.getTextContent().trim());
            else if (z != null) lhs.add("0");
        }

        return lhs;
    }

    private String parseRHS(Node sharpening) {
        Node rhsNode = getChildByName(sharpening, "rhs");
        Node zero = getChildByName(rhsNode, "zero");

        if (zero != null) return "0";

        Node sp = getChildByName(rhsNode, "standpoint");
        return sp.getTextContent().trim();
    }

    private Node getChildByName(Node parent, String name) {
        if (parent == null) return null;

        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c.getNodeType() == Node.ELEMENT_NODE
                    && c.getNodeName().equals(name)) {
                return c;
            }
        }
        return null;
    }
}