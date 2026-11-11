package io.reggraeton.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code reggraeton doctor}: checks that this machine can run generated projects without
 * downloading anything: Java, Maven, and an installed Chromium-based browser.
 */
@Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Checks Java, Maven and the installed browsers that generated tests can use (nothing is downloaded or launched).")
public final class DoctorCommand implements Callable<Integer> {

    @Option(names = "--channel", defaultValue = "msedge",
            description = "Browser channel the tests will use: msedge (default), chrome or chromium.")
    String channel;

    @picocli.CommandLine.Spec
    picocli.CommandLine.Model.CommandSpec spec;

    record Candidate(String channel, Path path) {
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean ok = true;

        int javaVersion = Runtime.version().feature();
        out.printf("%s Java %s (%s)%n", javaVersion >= 17 ? "[ok]  " : "[fail]", System.getProperty("java.version"),
                javaVersion >= 17 ? "17+ required" : "generated projects need Java 17 or newer");
        ok &= javaVersion >= 17;

        Path maven = onPath(os.contains("win") ? List.of("mvn.cmd", "mvn.bat", "mvn") : List.of("mvn"));
        out.printf("%s Maven %s%n", maven != null ? "[ok]  " : "[warn]", maven != null ? maven : "not found on PATH (needed to run the generated projects)");

        List<Candidate> found = new ArrayList<>();
        for (Candidate candidate : candidates(os)) {
            if (Files.isRegularFile(candidate.path()) || Files.isSymbolicLink(candidate.path())) {
                found.add(candidate);
            }
        }
        for (Candidate candidate : found) {
            out.printf("[ok]   browser %-9s %s%n", candidate.channel(), candidate.path());
        }
        boolean selected = found.stream().anyMatch(c -> c.channel().equals(channel));
        if (selected) {
            out.printf("[ok]   tests will use -Dbrowser.channel=%s%n", channel);
        } else {
            ok = false;
            out.printf("[fail] no installed '%s' browser found.%n", channel);
            if (!found.isEmpty()) {
                Candidate other = found.get(0);
                out.printf("       Use -Dbrowser.channel=%s, or -Dbrowser.executable=%s%n", other.channel(), other.path());
            } else {
                out.println("       Install Microsoft Edge, or pass -Dbrowser.executable=<path to a Chromium-based browser>.");
            }
        }
        out.println("[info] generated projects set PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1: browsers are never downloaded.");
        return ok ? 0 : 1;
    }

    static List<Candidate> candidates(String os) {
        List<Candidate> list = new ArrayList<>();
        Map<String, String> env = System.getenv();
        if (os.contains("win")) {
            for (String base : Stream.of(env.getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)"),
                            env.getOrDefault("ProgramFiles", "C:\\Program Files"), env.get("LOCALAPPDATA"))
                    .filter(b -> b != null && !b.isBlank()).distinct().toList()) {
                list.add(new Candidate("msedge", Path.of(base, "Microsoft", "Edge", "Application", "msedge.exe")));
                list.add(new Candidate("chrome", Path.of(base, "Google", "Chrome", "Application", "chrome.exe")));
            }
        } else if (os.contains("mac")) {
            list.add(new Candidate("msedge", Path.of("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge")));
            list.add(new Candidate("chrome", Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")));
        } else {
            list.add(new Candidate("msedge", Path.of("/opt/microsoft/msedge/msedge")));
            list.add(new Candidate("msedge", Path.of("/usr/bin/microsoft-edge-stable")));
            list.add(new Candidate("msedge", Path.of("/usr/bin/microsoft-edge")));
            list.add(new Candidate("chrome", Path.of("/opt/google/chrome/chrome")));
            list.add(new Candidate("chrome", Path.of("/usr/bin/google-chrome")));
        }
        String browsersPath = env.get("PLAYWRIGHT_BROWSERS_PATH");
        Path playwrightDir = browsersPath != null && !browsersPath.isBlank() ? Path.of(browsersPath)
                : os.contains("win") && env.get("LOCALAPPDATA") != null ? Path.of(env.get("LOCALAPPDATA"), "ms-playwright")
                : os.contains("mac") ? Path.of(System.getProperty("user.home"), "Library", "Caches", "ms-playwright")
                : Path.of(System.getProperty("user.home"), ".cache", "ms-playwright");
        if (Files.isDirectory(playwrightDir)) {
            try (Stream<Path> dirs = Files.list(playwrightDir)) {
                dirs.filter(d -> d.getFileName().toString().matches("chromium-\\d+")).sorted().forEach(d -> {
                    for (Path exe : List.of(d.resolve("chrome-linux64/chrome"), d.resolve("chrome-linux/chrome"),
                            d.resolve("chrome-win/chrome.exe"), d.resolve("chrome-win64/chrome.exe"),
                            d.resolve("chrome-mac/Chromium.app/Contents/MacOS/Chromium"),
                            d.resolve("chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"))) {
                        list.add(new Candidate("chromium", exe));
                    }
                });
            } catch (java.io.IOException e) {
                // unreadable: skip
            }
        }
        return list;
    }

    private static Path onPath(List<String> names) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            for (String name : names) {
                Path candidate = Path.of(dir, name);
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
