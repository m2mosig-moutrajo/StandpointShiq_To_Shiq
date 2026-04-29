package org.standpoint.plugin.util;

public class PipelineLogger {

    public enum Level { OFF, ON }

    private static Level level = Level.ON; // default ON

    public PipelineLogger(Level level) {
        this.level = level;
    }

    public static void setLevel(Level l) { level = l; }

    public static void log(String msg) {
        if (level == Level.ON) System.out.println(msg);
    }
    public static void result(String msg) {
        System.out.println(msg);
    }
    public static Level getLevel() { return level; }
}