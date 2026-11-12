package io.reggraeton.cli;

import io.reggraeton.generate.ConvertOptions;
import io.reggraeton.generate.ProjectGenerator;
import io.reggraeton.naming.TestNaming;
import io.reggraeton.scan.RepositoryScanner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/** {@code reggraeton convert}: generates the Playwright regression project. */
@Command(
        name = "convert",
        mixinStandardHelpOptions = true,
        description = {
                "Generates a standalone Maven project with one Playwright test per existing scenario / Examples row.",
                "The scanned repository is only read; nothing in it is changed."})
public final class ConvertCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<repo>", description = "Root of the repository to convert.")
    Path repository;

    @Option(names = {"-o", "--out"}, paramLabel = "<dir>", required = true,
            description = "Directory of the generated project (must be empty or new, unless --force).")
    Path out;

    @Option(names = {"-m", "--mode"}, paramLabel = "<mode>", defaultValue = "bdd",
            description = "bdd: keep the feature files, run them with Cucumber on Playwright. "
                    + "native: plain JUnit tests, no Cucumber at run time. Default: ${DEFAULT-VALUE}.")
    String mode;

    @Option(names = "--base-package", paramLabel = "<package>", defaultValue = TestNaming.DEFAULT_BASE_PACKAGE,
            description = "Package of the generated tests and support classes (default: ${DEFAULT-VALUE}).")
    String basePackage;

    @Option(names = "--group-id", paramLabel = "<groupId>", description = "Maven groupId (default: the base package).")
    String groupId;

    @Option(names = "--artifact-id", paramLabel = "<artifactId>", defaultValue = "playwright-regression-tests",
            description = "Maven artifactId (default: ${DEFAULT-VALUE}).")
    String artifactId;

    @Option(names = "--classpath", paramLabel = "<file|entries>",
            description = "Jars of the scanned project, to improve type resolution (see `assess --help`).")
    String classpath;

    @Option(names = "--force", description = "Write into a non-empty output directory, overwriting generated files.")
    boolean force;

    @Option(names = "--config", paramLabel = "<file>",
            description = "reggraeton.properties with rules for external library calls (default: <repo>/reggraeton.properties, if present).")
    Path config;

    @picocli.CommandLine.Spec
    picocli.CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws IOException {
        PrintWriter console = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        ConvertOptions.Mode parsedMode;
        try {
            parsedMode = ConvertOptions.Mode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            err.println("Unknown --mode '" + mode + "': use bdd or native");
            return 2;
        }
        Path absoluteOut = out.toAbsolutePath().normalize();
        Path absoluteRepo = repository.toAbsolutePath().normalize();
        if (absoluteOut.startsWith(absoluteRepo) && !absoluteOut.equals(absoluteRepo)) {
            console.println("Note: the output directory is inside the scanned repository; it will be ignored by later scans only if named target/build/out.");
        }
        if (absoluteOut.equals(absoluteRepo)) {
            err.println("The output directory must not be the scanned repository itself.");
            return 2;
        }
        if (Files.isDirectory(absoluteOut) && !force) {
            try (Stream<Path> entries = Files.list(absoluteOut)) {
                if (entries.findAny().isPresent()) {
                    err.println("Output directory " + absoluteOut + " is not empty. Use --force to overwrite.");
                    return 2;
                }
            }
        }

        console.printf("Scanning %s ...%n", absoluteRepo);
        RepositoryScanner.Scan scan = new RepositoryScanner(Classpath.parse(classpath), config).scanWithSources(absoluteRepo);
        ConvertOptions options = new ConvertOptions(absoluteOut, parsedMode, basePackage, groupId, artifactId);
        ProjectGenerator.Result result = new ProjectGenerator(scan, options).generate();

        console.printf("%nGenerated %s project in %s%n", parsedMode.name().toLowerCase(), result.output());
        console.printf("  tests            %d for %d original test cases%n", result.generatedTests(), result.testCases());
        console.printf("  blocked tests    %d (undefined/ambiguous steps; generated as failing tests)%n", result.blockedTests());
        console.printf("  TODO(reggraeton) %d place(s) to finish by hand%n", result.todos().size());
        if (!result.problems().isEmpty()) {
            console.printf("  problems         %d (see conversion-report.md)%n", result.problems().size());
        }
        console.printf("%nNext: cd %s && mvn test -DbaseUrl=<your app url>   (add -Dheadless=false to watch)%n", result.output());
        console.printf("See README.md, conversion-report.md and traceability.csv in the generated project.%n");
        if (!result.complete()) {
            err.println("Completeness check failed: no test was generated for " + result.missingTestCases());
            return 3;
        }
        return 0;
    }
}
