package com.magic76.crew.agent;

import java.util.Objects;

/** Provider-neutral tool declaration. The model adapter owns schema parsing. */
public final class ToolSpec {
    private final String name;
    private final String description;
    private final String inputSchemaJson;

    public ToolSpec(String name, String description, String inputSchemaJson) {
        this.name = requireText(name, "name");
        this.description = description == null ? "" : description;
        this.inputSchemaJson = inputSchemaJson == null || inputSchemaJson.trim().isEmpty()
                ? "{\"type\":\"object\"}"
                : inputSchemaJson;
    }

    public String name() { return name; }
    public String description() { return description; }
    public String inputSchemaJson() { return inputSchemaJson; }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.trim().isEmpty()) throw new IllegalArgumentException(field + " is empty");
        return value.trim();
    }
}
