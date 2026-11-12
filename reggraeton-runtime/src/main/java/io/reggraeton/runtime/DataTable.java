package io.reggraeton.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * native mode: stands in for {@code io.cucumber.datatable.DataTable} with its commonly used,
 * string-based API. Tables are created by the generated tests from the feature file.
 */
public final class DataTable {

    private final List<List<String>> cells;

    private DataTable(List<List<String>> cells) {
        List<List<String>> copy = new ArrayList<>();
        for (List<String> row : cells) {
            copy.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        this.cells = Collections.unmodifiableList(copy);
    }

    public static DataTable of(String[][] rows) {
        List<List<String>> cells = new ArrayList<>();
        for (String[] row : rows) {
            cells.add(List.of(row));
        }
        return new DataTable(cells);
    }

    public static DataTable create(List<List<String>> cells) {
        return new DataTable(cells);
    }

    public List<List<String>> cells() {
        return cells;
    }

    public List<List<String>> asLists() {
        return cells;
    }

    public List<List<String>> asLists(Class<?> type) {
        return cells;
    }

    /** All cells, row by row. */
    public List<String> asList() {
        List<String> all = new ArrayList<>();
        cells.forEach(all::addAll);
        return Collections.unmodifiableList(all);
    }

    public List<String> asList(Class<?> type) {
        return asList();
    }

    /** One map per data row, keyed by the header row. */
    public List<Map<String, String>> asMaps() {
        List<Map<String, String>> maps = new ArrayList<>();
        if (cells.isEmpty()) {
            return maps;
        }
        List<String> header = cells.get(0);
        for (List<String> row : cells.subList(1, cells.size())) {
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                map.put(header.get(i), i < row.size() ? row.get(i) : null);
            }
            maps.add(Collections.unmodifiableMap(map));
        }
        return Collections.unmodifiableList(maps);
    }

    public List<Map<String, String>> asMaps(Class<?> keyType, Class<?> valueType) {
        return asMaps();
    }

    /** Two-column table as key to value. */
    public Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (List<String> row : cells) {
            if (row.size() != 2) {
                throw new IllegalStateException("asMap() needs a table with two columns, row was " + row);
            }
            map.put(row.get(0), row.get(1));
        }
        return Collections.unmodifiableMap(map);
    }

    public Map<String, String> asMap(Class<?> keyType, Class<?> valueType) {
        return asMap();
    }

    public List<String> row(int row) {
        return cells.get(row);
    }

    public List<String> column(int column) {
        List<String> values = new ArrayList<>();
        cells.forEach(r -> values.add(r.get(column)));
        return Collections.unmodifiableList(values);
    }

    public String cell(int row, int column) {
        return cells.get(row).get(column);
    }

    public int height() {
        return cells.size();
    }

    public int width() {
        return cells.isEmpty() ? 0 : cells.get(0).size();
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    public DataTable transpose() {
        List<List<String>> transposed = new ArrayList<>();
        for (int c = 0; c < width(); c++) {
            transposed.add(column(c));
        }
        return new DataTable(transposed);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : cells) {
            sb.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        return sb.toString();
    }
}
