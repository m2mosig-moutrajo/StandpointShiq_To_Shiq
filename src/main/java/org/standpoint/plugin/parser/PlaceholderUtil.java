package org.standpoint.plugin.parser;

import java.util.UUID;

public class PlaceholderUtil {

    public static final String PREFIX = "SP_";
    private static int counter = 1;

    public static String generate() {
        //return PREFIX + UUID.randomUUID().toString().replace("-", "");
        return PREFIX + counter++;
    }
    public static String generateWithoutPrefix() {
        //return PREFIX + UUID.randomUUID().toString().replace("-", "");
        return "" + counter++;
    }

    public static boolean isPlaceholder(String name) {
        return name.matches(PREFIX + "[a-f0-9]+");
    }
}