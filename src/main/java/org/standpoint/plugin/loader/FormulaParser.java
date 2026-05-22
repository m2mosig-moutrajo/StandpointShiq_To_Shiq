package org.standpoint.plugin.loader;

import org.standpoint.plugin.model.ParsedFormula;
import org.standpoint.plugin.model.ParsedLiteral;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import javax.xml.parsers.*;
import java.io.StringReader;
import java.util.*;

public class FormulaParser {
    // Parses <formula op="box" standpoint="s1">...</formula>
    public ParsedFormula parse(String xml) {
        try {
            String wrapped = "<root>" + xml.trim() + "</root>";
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(wrapped)));

            Node formula = doc.getDocumentElement().getFirstChild();

            String operator   = formula.getAttributes().getNamedItem("op").getNodeValue();
            String standpoint = formula.getAttributes().getNamedItem("standpoint").getNodeValue();

            List<ParsedLiteral> literals = new ArrayList<>();

            // Check if direct literal or intersection
            NodeList children = formula.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() != Node.ELEMENT_NODE) continue;

                if (child.getNodeName().equals("literal")) {
                    // Single literal: <literal ref="F1" negated="true"/>
                    literals.add(parseLiteral(child));

                } else if (child.getNodeName().equals("intersection")) {
                    // Multiple literals: <intersection><literal ref="F1"/>...</intersection>
                    NodeList litChildren = child.getChildNodes();
                    for (int j = 0; j < litChildren.getLength(); j++) {
                        Node lit = litChildren.item(j);
                        if (lit.getNodeType() == Node.ELEMENT_NODE
                                && lit.getNodeName().equals("literal")) {
                            literals.add(parseLiteral(lit));
                        }
                    }
                }
            }

            return new ParsedFormula(operator, standpoint, literals);

        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid formula XML: " + e.getMessage(), e);
        }
    }

    private ParsedLiteral parseLiteral(Node node) {
        String ref = node.getAttributes().getNamedItem("ref").getNodeValue();
        Node negatedAttr = node.getAttributes().getNamedItem("negated");
        boolean negated = negatedAttr != null && "true".equals(negatedAttr.getNodeValue());
        return new ParsedLiteral(ref, negated);
    }
}