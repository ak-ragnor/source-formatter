package com.codeformatter.api;

/**
 * Represents a code refactoring that was applied.
 */
public class Refactoring {
    private final String type;
    private final int startLine;
    private final int endLine;
    private final String description;
    
    public Refactoring(String type, int startLine, int endLine, String description) {
        this.type = type;
        this.startLine = startLine;
        this.endLine = endLine;
        this.description = description;
    }
    
    // Getters
    public String getType() { return type; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public String getDescription() { return description; }
}
