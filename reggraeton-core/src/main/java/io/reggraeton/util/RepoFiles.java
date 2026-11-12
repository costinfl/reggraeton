package io.reggraeton.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.MalformedInputException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** File-system helpers for walking a repository while skipping build output and VCS folders. */
public final class RepoFiles {

    /** Directory names never descended into. */
    public static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", ".svn", ".hg", ".idea", ".vscode", ".gradle", ".mvn", "node_modules", ".reggraeton");

    /**
     * Build-output directory names, skipped only next to a build file (or at the root), so that a
     * Java package that happens to be called {@code build} or {@code out} is still scanned.
     */
    public static final Set<String> BUILD_OUTPUT_DIRECTORIES = Set.of("target", "build", "out", "bin");

    private static final List<String> BUILD_FILES = List.of("pom.xml", "build.gradle", "build.gradle.kts");

    private RepoFiles() {
    }

    /** All regular files below {@code root} accepted by {@code filter}, in a stable (sorted) order. */
    public static List<Path> find(Path root, Predicate<Path> filter) {
        List<Path> result = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && isSkipped(root, dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && filter.test(file)) {
                        result.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        result.sort(null);
        return result;
    }

    private static boolean isSkipped(Path root, Path dir) {
        String name = dir.getFileName().toString();
        if (SKIPPED_DIRECTORIES.contains(name)) {
            return true;
        }
        if (BUILD_OUTPUT_DIRECTORIES.contains(name)) {
            Path parent = dir.getParent();
            return parent.equals(root) || BUILD_FILES.stream().anyMatch(f -> Files.exists(parent.resolve(f)));
        }
        return false;
    }

    public static List<Path> findByExtension(Path root, String extension) {
        return find(root, p -> p.getFileName().toString().endsWith(extension));
    }

    /** Repo-relative path with {@code /} separators regardless of OS. */
    public static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** Reads a text file as UTF-8, falling back to ISO-8859-1 for legacy encodings. */
    public static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            try {
                return Files.readString(file, StandardCharsets.ISO_8859_1);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
