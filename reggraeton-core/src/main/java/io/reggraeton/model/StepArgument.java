package io.reggraeton.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * The multi-line argument attached to a step: a data table or a doc string.
 *
 * @param type      {@link #DATA_TABLE} or {@link #DOC_STRING}
 * @param rows      table rows (data table only)
 * @param content   text (doc string only)
 * @param mediaType content type after the opening delimiter (doc string only, may be null)
 */
public record StepArgument(String type, List<List<String>> rows, String content, String mediaType) {

    public static final String DATA_TABLE = "DataTable";
    public static final String DOC_STRING = "DocString";

    public static StepArgument dataTable(List<List<String>> rows) {
        return new StepArgument(DATA_TABLE, rows, null, null);
    }

    public static StepArgument docString(String content, String mediaType) {
        return new StepArgument(DOC_STRING, null, content, mediaType);
    }

    @JsonIgnore
    public boolean isDataTable() {
        return DATA_TABLE.equals(type);
    }

    @JsonIgnore
    public boolean isDocString() {
        return DOC_STRING.equals(type);
    }
}
