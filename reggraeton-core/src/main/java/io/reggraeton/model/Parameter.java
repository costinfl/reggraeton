package io.reggraeton.model;

/** A method or lambda parameter. {@code type} is the source-level type, {@code ?} when not declared. */
public record Parameter(String name, String type) {
}
