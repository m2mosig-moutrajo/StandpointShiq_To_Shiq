package org.standpoint.plugin.normalisation;

import org.standpoint.plugin.parser.PlaceholderSubstituter.PlaceholderEntry;
import org.standpoint.plugin.parser.PlaceholderSubstituter.Operator;
import org.standpoint.plugin.parser.PlaceholderUtil;

import java.util.Map;

public class PlaceholderRestorer {

    private final Map<String, PlaceholderEntry> placeholderMap;

    public PlaceholderRestorer(Map<String, PlaceholderEntry> placeholderMap) {
        this.placeholderMap = placeholderMap;
    }
    // Scans all entries and resolves any not(SP_x) patterns by applying modal duality
    public boolean restoreModalDuality() {
        boolean anyChanged = false;
        for (PlaceholderEntry entry : placeholderMap.values()) {
            String restored = resolveDualityInExpression(entry.manchester);
            if (!restored.equals(entry.manchester)) {
                entry.manchester = restored;
                anyChanged = true;
            }
        }
        return anyChanged;
    }
    // Scans a Manchester expression for not(SP_x) and resolves duality
    private String resolveDualityInExpression(String manchesterExpr) {
        StringBuilder result = new StringBuilder();
        int i = 0;

        while (i < manchesterExpr.length()) {
            // Look for "not (SP_...)" pattern
            if (manchesterExpr.startsWith("not (" + PlaceholderUtil.PREFIX, i)) {
                int parenOpen  = manchesterExpr.indexOf('(', i + 4);
                int parenClose = findMatchingParen(manchesterExpr, parenOpen);
                String innerToken = manchesterExpr.substring(parenOpen + 1, parenClose).trim();

                if (PlaceholderUtil.isPlaceholder(innerToken) && placeholderMap.containsKey(innerToken)) {
                    // Apply duality: flip operator of target entry, push not inside
                    PlaceholderEntry targetEntry = placeholderMap.get(innerToken);
                    applyModalDuality(targetEntry);
                    // Replace "not (SP_x)" with just "SP_x"
                    result.append(innerToken);
                    i = parenClose + 1;
                } else {
                    // Inner token is not a placeholder — keep as is
                    result.append(manchesterExpr.charAt(i));
                    i++;
                }
            } else {
                result.append(manchesterExpr.charAt(i));
                i++;
            }
        }

        return result.toString();
    }
    // Flips box↔diamond and pushes negation inside (modal duality law)
    private void applyModalDuality(PlaceholderEntry entry) {
        Operator dualOperator = entry.operator == Operator.BOX
                ? Operator.DIAMOND
                : Operator.BOX;

        String dualManchester;
        if (entry.manchester.startsWith("not (")) {
            // Double negation elimination: not(not(X)) → X
            int open  = entry.manchester.indexOf('(');
            int close = findMatchingParen(entry.manchester, open);
            dualManchester = entry.manchester.substring(open + 1, close).trim();
        } else {
            dualManchester = "not (" + entry.manchester + ")";
        }

        entry.operator   = dualOperator;
        entry.manchester = dualManchester;
    }

    private int findMatchingParen(String input, int openPos) {
        int depth = 0;
        for (int i = openPos; i < input.length(); i++) {
            if (input.charAt(i) == '(') depth++;
            else if (input.charAt(i) == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        throw new IllegalArgumentException(
                "No matching closing parenthesis from position " + openPos);
    }
}