package org.standpoint.plugin.util;

import org.standpoint.plugin.model.PlaceholderType;

public class PlaceholderCounter {

    private int counter = 1;

    public String generate(PlaceholderType type) {
        return type.prefix + counter++;
    }

}