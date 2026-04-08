package org.standpoint.plugin.util;

public class PlaceholderCounter {

    public static final String PREFIX = "SP_";
    private int counter = 1;

    public String generate() {
     // return PREFIX + UUID.randomUUID().toString().replace("-", "");
        return PREFIX + counter++;
    }

    public String generateWithoutPrefix() {
     // return UUID.randomUUID().toString().replace("-", "");
        return String.valueOf(counter++);
    }

    public boolean isPlaceholder(String name) {
        return name.startsWith(PREFIX);
    }

    public void reset() {
        counter = 1;
    }
}