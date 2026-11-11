package io.reggraeton.scan;

import io.reggraeton.model.FeatureFile;
import io.reggraeton.model.Inventory;
import io.reggraeton.model.ProjectLayout;
import io.reggraeton.model.TestCase;
import io.reggraeton.util.RepoFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Entry point of the discovery phase: scans a repository read-only and builds the {@link Inventory}. */
public final class RepositoryScanner {

    private final ProjectLayoutDetector layoutDetector = new ProjectLayoutDetector();
    private final FeatureParser featureParser = new FeatureParser();

    public Inventory scan(Path repository) {
        Path root = repository.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + root);
        }
        ProjectLayout layout = layoutDetector.detect(root);

        List<FeatureFile> features = new ArrayList<>();
        List<TestCase> testCases = new ArrayList<>();
        for (Path file : RepoFiles.findByExtension(root, ".feature")) {
            FeatureParser.Parsed parsed = featureParser.parse(file, RepoFiles.relative(root, file));
            features.add(parsed.feature());
            testCases.addAll(parsed.testCases());
        }

        return new Inventory(
                Inventory.SCHEMA_VERSION,
                OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString(),
                layout,
                features,
                testCases);
    }
}
