package io.reggraeton.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** {@code reggraeton} command line entry point. */
@Command(
        name = "reggraeton",
        mixinStandardHelpOptions = true,
        versionProvider = Main.Version.class,
        description = "Converts Cucumber-JVM / Selenium BDD suites into Playwright for Java regression tests.",
        subcommands = {AssessCommand.class, ConvertCommand.class, CommandLine.HelpCommand.class})
public final class Main implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    /** Runs the CLI and returns the exit code (used by tests). */
    public static int execute(String... args) {
        return new CommandLine(new Main())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = Main.class.getPackage().getImplementationVersion();
            return new String[] {"reggraeton " + (version == null ? "dev" : version)};
        }
    }
}
