package io.reggraeton.translate;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.UnionType;
import io.reggraeton.model.CallGraph.ApiUsage;
import io.reggraeton.scan.java.ApiClassifier;
import io.reggraeton.scan.java.Expressions;
import io.reggraeton.scan.java.JavaSourceIndex;
import io.reggraeton.scan.java.SeleniumApiCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Translates one compilation unit from Selenium to Playwright, in two passes: pass 1 resolves and
 * plans every call on the untouched AST (so the symbol solver sees the original code); pass 2
 * applies the plans. Anything without a rule becomes a {@code TODO(reggraeton)} stub that throws.
 */
final class SourceTranslator {

    private enum Plan { REWRITE, CONSUMED, REMOVE_STATEMENT, MANUAL, DRIVER_PAGE, URL_CONFIG }

    private record CallInfo(String category, String api, boolean auto, String note) {
    }

    /** API names with a rewrite rule (the catalogue's {@code auto} entries must all be here). */
    static final Set<String> RULES = Set.of(
            "WebDriver.get", "WebDriver.getTitle", "WebDriver.getCurrentUrl", "WebDriver.getPageSource",
            "WebDriver.findElement", "WebDriver.findElements", "WebDriver.quit", "WebDriver.close",
            "WebDriver.navigate", "WebDriver.manage", "WebDriver.switchTo",
            "SearchContext.findElement", "SearchContext.findElements",
            "WebElement.findElement", "WebElement.findElements", "WebElement.click", "WebElement.submit",
            "WebElement.sendKeys", "WebElement.clear", "WebElement.getText", "WebElement.getAttribute",
            "WebElement.getDomAttribute", "WebElement.getDomProperty", "WebElement.getCssValue",
            "WebElement.isDisplayed", "WebElement.isEnabled", "WebElement.isSelected", "WebElement.getTagName",
            "WebElement.getScreenshotAs",
            "By.id", "By.cssSelector", "By.xpath", "By.name", "By.className", "By.linkText", "By.partialLinkText",
            "By.tagName", "@FindBy", "@FindBys", "@FindAll", "@CacheLookup", "PageFactory.initElements",
            "WebDriver.Navigation.to", "WebDriver.Navigation.back", "WebDriver.Navigation.forward",
            "WebDriver.Navigation.refresh", "WebDriver.Options.window", "WebDriver.Options.timeouts",
            "WebDriver.Options.deleteAllCookies", "WebDriver.Window.maximize", "WebDriver.Window.fullscreen",
            "WebDriver.Window.setSize", "WebDriver.Timeouts.implicitlyWait", "WebDriver.Timeouts.pageLoadTimeout",
            "WebDriver.Timeouts.scriptTimeout", "WebDriver.Timeouts.setScriptTimeout",
            "WebDriver.TargetLocator.activeElement", "JavascriptExecutor.executeScript", "TakesScreenshot.getScreenshotAs",
            "Select.<init>", "Select.selectByVisibleText", "Select.selectByValue", "Select.selectByIndex",
            "Actions.<init>", "Actions.moveToElement", "Actions.click", "Actions.doubleClick", "Actions.contextClick",
            "Actions.dragAndDrop", "Actions.sendKeys", "Actions.scrollToElement", "Actions.perform", "Actions.build",
            "Action.perform",
            "WebDriverWait.<init>", "FluentWait.<init>", "FluentWait.withTimeout", "FluentWait.pollingEvery",
            "FluentWait.ignoring", "FluentWait.withMessage", "FluentWait.until",
            "ExpectedConditions.visibilityOfElementLocated", "ExpectedConditions.visibilityOf",
            "ExpectedConditions.elementToBeClickable", "ExpectedConditions.presenceOfElementLocated",
            "ExpectedConditions.invisibilityOfElementLocated", "ExpectedConditions.invisibilityOf",
            "ExpectedConditions.textToBePresentInElement", "ExpectedConditions.textToBePresentInElementLocated",
            "ExpectedConditions.textToBe", "ExpectedConditions.titleIs", "ExpectedConditions.titleContains",
            "ExpectedConditions.urlContains", "ExpectedConditions.urlToBe", "ExpectedConditions.urlMatches",
            "ExpectedConditions.numberOfElementsToBe", "ExpectedConditions.elementToBeSelected",
            "ExpectedConditions.attributeToBe", "ExpectedConditions.visibilityOfAllElementsLocatedBy",
            "ExpectedConditions.presenceOfAllElementsLocatedBy");

    /** ExpectedConditions name to {@code Waits} method. */
    private static final Map<String, String> CONDITIONS = Map.ofEntries(
            Map.entry("visibilityOfElementLocated", "visible"), Map.entry("visibilityOf", "visible"),
            Map.entry("elementToBeClickable", "clickable"), Map.entry("presenceOfElementLocated", "present"),
            Map.entry("invisibilityOfElementLocated", "hidden"), Map.entry("invisibilityOf", "hidden"),
            Map.entry("textToBePresentInElement", "text"), Map.entry("textToBePresentInElementLocated", "text"),
            Map.entry("textToBe", "exactText"), Map.entry("titleIs", "titleIs"),
            Map.entry("titleContains", "titleContains"), Map.entry("urlContains", "urlContains"),
            Map.entry("urlToBe", "urlIs"), Map.entry("urlMatches", "urlMatches"),
            Map.entry("numberOfElementsToBe", "count"), Map.entry("elementToBeSelected", "selected"),
            Map.entry("attributeToBe", "attribute"), Map.entry("visibilityOfAllElementsLocatedBy", "allVisible"),
            Map.entry("presenceOfAllElementsLocatedBy", "allPresent"));

    /** Selenium {@code Keys} constants with a direct Playwright key name. */
    private static final Map<String, String> KEYS = Map.ofEntries(
            Map.entry("ENTER", "Enter"), Map.entry("RETURN", "Enter"), Map.entry("TAB", "Tab"),
            Map.entry("ESCAPE", "Escape"), Map.entry("BACK_SPACE", "Backspace"), Map.entry("DELETE", "Delete"),
            Map.entry("SPACE", " "), Map.entry("HOME", "Home"), Map.entry("END", "End"),
            Map.entry("PAGE_UP", "PageUp"), Map.entry("PAGE_DOWN", "PageDown"),
            Map.entry("ARROW_UP", "ArrowUp"), Map.entry("UP", "ArrowUp"), Map.entry("ARROW_DOWN", "ArrowDown"),
            Map.entry("DOWN", "ArrowDown"), Map.entry("ARROW_LEFT", "ArrowLeft"), Map.entry("LEFT", "ArrowLeft"),
            Map.entry("ARROW_RIGHT", "ArrowRight"), Map.entry("RIGHT", "ArrowRight"));

    /** Selenium types and what they become. */
    private static final Map<String, String> TYPE_MAP = new LinkedHashMap<>();

    static {
        for (String t : List.of("org.openqa.selenium.WebDriver", "org.openqa.selenium.JavascriptExecutor",
                "org.openqa.selenium.TakesScreenshot", "org.openqa.selenium.SearchContext",
                "org.openqa.selenium.remote.RemoteWebDriver", "org.openqa.selenium.chrome.ChromeDriver",
                "org.openqa.selenium.edge.EdgeDriver", "org.openqa.selenium.firefox.FirefoxDriver",
                "org.openqa.selenium.safari.SafariDriver", "org.openqa.selenium.chromium.ChromiumDriver",
                "org.openqa.selenium.ie.InternetExplorerDriver")) {
            TYPE_MAP.put(t, "Page");
        }
        TYPE_MAP.put("org.openqa.selenium.WebElement", "Locator");
        TYPE_MAP.put("org.openqa.selenium.remote.RemoteWebElement", "Locator");
        TYPE_MAP.put("org.openqa.selenium.support.ui.Select", "Locator");
        TYPE_MAP.put("org.openqa.selenium.By", "String");
        TYPE_MAP.put("org.openqa.selenium.support.ui.WebDriverWait", "Waits");
        TYPE_MAP.put("org.openqa.selenium.support.ui.FluentWait", "Waits");
        TYPE_MAP.put("org.openqa.selenium.support.ui.Wait", "Waits");
        TYPE_MAP.put("org.openqa.selenium.Keys", "Keys");
        TYPE_MAP.put("org.openqa.selenium.TimeoutException", "TimeoutError");
        for (String e : List.of("NoSuchElementException", "StaleElementReferenceException", "WebDriverException",
                "ElementNotInteractableException", "ElementClickInterceptedException", "InvalidElementStateException",
                "ElementNotVisibleException", "NoAlertPresentException", "NoSuchFrameException", "NoSuchWindowException",
                "InvalidSelectorException", "JavascriptException")) {
            TYPE_MAP.put("org.openqa.selenium." + e, "PlaywrightException");
        }
    }

    private static final Pattern URL_KEY = Pattern.compile(
            "(?i)(base[._-]?url|(app|application|web|site|ui|frontend|test|env)?[._-]?url)");

    private final CompilationUnit unit;
    private final String path;
    private final JavaSourceIndex index;
    private final SupportNames support;
    private final Set<String> projectMethodNames;
    private final JavaTypes types;

    private final Map<Node, CallInfo> infos = new IdentityHashMap<>();
    private final Map<Node, Plan> plans = new IdentityHashMap<>();
    private final Map<Node, String> manualNotes = new IdentityHashMap<>();
    private final Set<Node> chainMembers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Node> stringArguments = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, Integer> changes = new TreeMap<>();
    private final List<TranslatedSource.Todo> todos = new ArrayList<>();

    SourceTranslator(CompilationUnit unit, String path, JavaSourceIndex index, SupportNames support,
                     Set<String> projectMethodNames) {
        this.unit = unit;
        this.path = path;
        this.index = index;
        this.support = support;
        this.projectMethodNames = projectMethodNames;
        this.types = new JavaTypes(unit);
    }

    TranslatedSource translate() {
        List<Node> postOrder = new ArrayList<>();
        unit.walk(Node.TreeTraversal.POSTORDER, postOrder::add);

        // ---- pass 1: analyse the untouched tree ---------------------------------------------
        for (Node node : postOrder) {
            if (node instanceof MethodCallExpr call) {
                classify(call);
            } else if (node instanceof ObjectCreationExpr creation) {
                classify(creation);
            }
        }
        for (Node node : postOrder) {
            CallInfo info = infos.get(node);
            if (info != null && (info.api().equals("Actions.perform") || info.api().equals("Action.perform"))) {
                analyseActionChain((MethodCallExpr) node);
            }
        }
        for (Node node : postOrder) {
            if (infos.containsKey(node)) {
                plan(node);
            }
        }
        Map<Node, String> fieldPages = new IdentityHashMap<>();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            boolean hasLocatorFields = type.getFields().stream().anyMatch(f -> f.getAnnotations().stream()
                    .anyMatch(a -> Expressions.qualifiedAnnotationName(a).startsWith("org.openqa.selenium.support.FindB")
                            || Expressions.qualifiedAnnotationName(a).startsWith("org.openqa.selenium.support.FindAll")));
            if (hasLocatorFields) {
                fieldPages.put(type, pageExpressionFor(type));
            }
        }

        // ---- pass 2: apply ----------------------------------------------------------------
        applyManualStubs();
        convertFindByFields(fieldPages);
        for (Node node : postOrder) {
            Plan plan = plans.get(node);
            if (plan != null && plan != Plan.CONSUMED && plan != Plan.MANUAL && attached(node)) {
                apply(node, plan);
            }
        }
        mapTypes();
        fixCatchClauses();
        fixImports();

        String source = unit.toString().lines()
                .filter(l -> !l.trim().equals(";"))
                .reduce((a, b) -> a + "\n" + b).orElse("") + "\n";
        String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        String primary = unit.getPrimaryTypeName().orElse(unit.getTypes().isEmpty() ? "Unknown"
                : unit.getType(0).getNameAsString());
        String relative = (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/") + primary + ".java";
        return new TranslatedSource(path, relative, pkg, primary, source, changes, todos);
    }

    // =========================================================================================
    // Pass 1
    // =========================================================================================

    private void classify(MethodCallExpr call) {
        String name = call.getNameAsString();
        Optional<String> declaring = types.declaringType(call);
        if (declaring.isEmpty()) {
            boolean projectMethod = projectMethodNames.contains(name);
            if (!projectMethod && SeleniumApiCatalog.isKnownMethodName(name)
                    && !Set.of("get", "to", "build", "perform", "until", "close").contains(name)) {
                infos.put(call, new CallInfo(ApiUsage.SELENIUM, "?." + name, false,
                        "receiver type could not be resolved; looks like Selenium, convert manually"));
            }
            return;
        }
        String type = declaring.get();
        if ((type.equals("java.lang.System")) && (name.equals("getProperty") || name.equals("getenv"))
                && !call.getArguments().isEmpty()) {
            String key = Expressions.constantString(call.getArgument(0));
            if (key != null && URL_KEY.matcher(key).matches()) {
                infos.put(call, new CallInfo("config", "System." + name + "(url)", true, "base URL"));
            }
            return;
        }
        if (index.isProjectType(type)) {
            return;
        }
        ApiUsage usage = ApiClassifier.classify(type, name, JavaTypes.code(call), JavaSourceIndex.lineOf(call), call);
        if (usage == null) {
            return;
        }
        infos.put(call, new CallInfo(usage.category(), usage.api(), ApiUsage.AUTO.equals(usage.support()), usage.note()));
        if (usage.api().equals("WebElement.sendKeys") && call.getArguments().size() == 1
                && JavaTypes.isString(call.getArgument(0))) {
            stringArguments.add(call);
        }
    }

    private void classify(ObjectCreationExpr creation) {
        Optional<String> type = types.createdType(creation);
        if (type.isEmpty() || index.isProjectType(type.get())) {
            return;
        }
        ApiUsage usage = ApiClassifier.classify(type.get(), "<init>", JavaTypes.code(creation),
                JavaSourceIndex.lineOf(creation), null);
        if (usage != null) {
            infos.put(creation, new CallInfo(usage.category(), usage.api(), ApiUsage.AUTO.equals(usage.support()), usage.note()));
        }
    }

    /** Marks the members of a supported {@code new Actions(d)...perform()} chain. */
    private void analyseActionChain(MethodCallExpr perform) {
        List<Node> members = new ArrayList<>();
        Expression current = perform.getScope().orElse(null);
        boolean hasTarget = false;
        List<MethodCallExpr> steps = new ArrayList<>();
        while (current instanceof MethodCallExpr call) {
            CallInfo info = infos.get(call);
            if (info == null || !info.api().startsWith("Actions.") || !info.auto()) {
                return;
            }
            members.add(call);
            steps.add(0, call);
            current = call.getScope().orElse(null);
        }
        if (!(current instanceof ObjectCreationExpr creation) || infos.get(creation) == null
                || !infos.get(creation).api().equals("Actions.<init>") || creation.getArguments().size() != 1) {
            return;
        }
        for (MethodCallExpr step : steps) {
            String name = step.getNameAsString();
            int args = step.getArguments().size();
            switch (name) {
                case "moveToElement", "scrollToElement" -> {
                    if (args != 1) {
                        return;
                    }
                    hasTarget = true;
                }
                case "click", "doubleClick", "contextClick" -> {
                    if (args == 0 && !hasTarget) {
                        return;
                    }
                    hasTarget = true;
                }
                case "dragAndDrop" -> {
                    if (args != 2) {
                        return;
                    }
                }
                case "sendKeys", "build" -> {
                    // always fine
                }
                default -> {
                    return;
                }
            }
        }
        if (!(perform.getParentNode().orElse(null) instanceof ExpressionStmt)) {
            return;
        }
        members.add(creation);
        chainMembers.addAll(members);
        chainMembers.add(perform);
    }

    private void plan(Node node) {
        CallInfo info = infos.get(node);
        String api = info.api();
        if ("config".equals(info.category())) {
            plans.put(node, Plan.URL_CONFIG);
            return;
        }
        if (ApiUsage.ASSERTION.equals(info.category()) || ApiUsage.CUCUMBER.equals(info.category())
                || "timing".equals(info.category())) {
            return;
        }
        if (ApiUsage.EXTERNAL.equals(info.category()) || !info.auto() && !ApiUsage.DRIVER_SETUP.equals(info.category())) {
            manual(node, info.note());
            return;
        }
        if (ApiUsage.DRIVER_SETUP.equals(info.category())) {
            planDriverSetup(node, api);
            return;
        }
        // Selenium with a catalogue rule
        if (!RULES.contains(api)) {
            manual(node, "no translation rule implemented for " + api);
            return;
        }
        switch (api) {
            case "WebDriver.navigate" -> consumedIf(node, parentApiStartsWith(node, "WebDriver.Navigation."));
            case "WebDriver.manage" -> consumedIf(node, parentApiStartsWith(node, "WebDriver.Options."));
            case "WebDriver.Options.window" -> consumedIf(node, parentApiStartsWith(node, "WebDriver.Window."));
            case "WebDriver.Options.timeouts" -> consumedIf(node, parentApiStartsWith(node, "WebDriver.Timeouts."));
            case "WebDriver.switchTo" -> consumedIf(node, parentApiStartsWith(node, "WebDriver.TargetLocator."));
            case "Actions.<init>", "Actions.moveToElement", "Actions.click", "Actions.doubleClick", "Actions.contextClick",
                 "Actions.dragAndDrop", "Actions.sendKeys", "Actions.scrollToElement", "Actions.build" ->
                    consumedIf(node, chainMembers.contains(node));
            case "Actions.perform", "Action.perform" -> {
                if (chainMembers.contains(node)) {
                    plans.put(node, Plan.REWRITE);
                } else {
                    manual(node, "action chain not supported automatically (stored Actions/Action, or unsupported step)");
                }
            }
            case "WebDriver.quit", "WebDriver.Window.maximize", "WebDriver.Window.fullscreen",
                 "WebDriver.Timeouts.implicitlyWait", "WebDriver.Timeouts.scriptTimeout",
                 "WebDriver.Timeouts.setScriptTimeout", "PageFactory.initElements" -> {
                if (isWholeStatement(node)) {
                    plans.put(node, Plan.REMOVE_STATEMENT);
                } else {
                    manual(node, api + " used as a value");
                }
            }
            case "WebDriver.Window.setSize" -> {
                MethodCallExpr call = (MethodCallExpr) node;
                boolean dimension = call.getArguments().size() == 1
                        && call.getArgument(0) instanceof ObjectCreationExpr c && c.getArguments().size() == 2;
                if (dimension) {
                    plans.put(node, Plan.REWRITE);
                    plans.put(call.getArgument(0), Plan.CONSUMED);
                    manualNotes.remove(call.getArgument(0));
                } else {
                    manual(node, "setSize without new Dimension(w, h): use page.setViewportSize(w, h)");
                }
            }
            case "TakesScreenshot.getScreenshotAs", "WebElement.getScreenshotAs" -> {
                MethodCallExpr call = (MethodCallExpr) node;
                String kind = call.getArguments().size() == 1 && call.getArgument(0) instanceof FieldAccessExpr f
                        ? f.getNameAsString() : "";
                if (Set.of("BYTES", "FILE", "BASE64").contains(kind)) {
                    plans.put(node, Plan.REWRITE);
                } else {
                    manual(node, "getScreenshotAs with an unsupported OutputType");
                }
            }
            case "FluentWait.until" -> {
                MethodCallExpr call = (MethodCallExpr) node;
                if (call.getArguments().size() == 1 && call.getArgument(0) instanceof MethodCallExpr condition
                        && CONDITIONS.containsKey(condition.getNameAsString())) {
                    plans.put(node, Plan.REWRITE);
                } else {
                    manual(node, "custom wait condition: rewrite with locator.waitFor(...) or assertThat(...)");
                }
            }
            default -> {
                if (api.startsWith("ExpectedConditions.")) {
                    consumedIf(node, node.getParentNode().orElse(null) instanceof MethodCallExpr parent
                            && infos.get(parent) != null && infos.get(parent).api().equals("FluentWait.until"));
                } else if (api.equals("Dimension.<init>")) {
                    if (!plans.containsKey(node)) {
                        manual(node, "Dimension outside setSize");
                    }
                } else {
                    plans.put(node, Plan.REWRITE);
                }
            }
        }
    }

    private void planDriverSetup(Node node, String api) {
        if (api.startsWith("WebDriverManager.") && api.endsWith(".create")) {
            plans.put(node, Plan.DRIVER_PAGE);
            return;
        }
        if (node instanceof ObjectCreationExpr creation) {
            String simple = api.substring(0, api.indexOf('.'));
            if (simple.endsWith("Driver")) {
                plans.put(node, Plan.DRIVER_PAGE);
                return;
            }
            if (insideDriverSetupArguments(creation)) {
                plans.put(node, Plan.CONSUMED);
                return;
            }
            if (isLocalDeclarationInitializer(creation)) {
                plans.put(node, Plan.REMOVE_STATEMENT);
                return;
            }
            manual(node, "browser options/capabilities used as a value; configure the browser with RunConfig instead");
            return;
        }
        if (isWholeStatement(node)) {
            plans.put(node, Plan.REMOVE_STATEMENT);
        } else if (isDriverSetupScope(node) || insideDriverSetupArguments(node)) {
            plans.put(node, Plan.CONSUMED);
        } else {
            manual(node, "browser setup used as a value; configure the browser with RunConfig instead");
        }
    }

    private void consumedIf(Node node, boolean consumed) {
        if (consumed) {
            plans.put(node, Plan.CONSUMED);
        } else {
            manual(node, infos.get(node).api() + " used on its own");
        }
    }

    private void manual(Node node, String note) {
        plans.put(node, Plan.MANUAL);
        manualNotes.put(node, note);
    }

    private boolean parentApiStartsWith(Node node, String prefix) {
        return node.getParentNode().orElse(null) instanceof MethodCallExpr parent
                && parent.getScope().map(s -> s == node).orElse(false)
                && infos.get(parent) != null && infos.get(parent).api().startsWith(prefix);
    }

    private static boolean isWholeStatement(Node node) {
        return node.getParentNode().orElse(null) instanceof ExpressionStmt;
    }

    private boolean isDriverSetupScope(Node node) {
        return node.getParentNode().orElse(null) instanceof MethodCallExpr parent
                && parent.getScope().map(s -> s == node).orElse(false)
                && infos.get(parent) != null && ApiUsage.DRIVER_SETUP.equals(infos.get(parent).category());
    }

    private boolean insideDriverSetupArguments(Node node) {
        for (Node p = node.getParentNode().orElse(null); p != null; p = p.getParentNode().orElse(null)) {
            if (p instanceof Statement) {
                return false;
            }
            CallInfo info = infos.get(p);
            if (info != null && ApiUsage.DRIVER_SETUP.equals(info.category())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLocalDeclarationInitializer(Node node) {
        return node.getParentNode().orElse(null) instanceof VariableDeclarator v
                && v.getParentNode().orElse(null) instanceof VariableDeclarationExpr decl
                && decl.getVariables().size() == 1
                && decl.getParentNode().orElse(null) instanceof ExpressionStmt;
    }

    // =========================================================================================
    // Pass 2: manual stubs
    // =========================================================================================

    private void applyManualStubs() {
        Map<Node, List<Node>> byTarget = new LinkedHashMap<>();
        for (Map.Entry<Node, String> manual : manualNotes.entrySet()) {
            Node target = stubTarget(manual.getKey());
            byTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(manual.getKey());
        }
        List<Node> targets = new ArrayList<>(byTarget.keySet());
        targets.sort(Comparator.comparingInt(SourceTranslator::depth));
        for (Node target : targets) {
            List<Node> nodes = byTarget.get(target);
            nodes.sort(Comparator.comparingInt(JavaSourceIndex::lineOf));
            StringBuilder message = new StringBuilder();
            List<String> commentLines = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Node node : nodes) {
                CallInfo info = infos.get(node);
                String note = manualNotes.get(node);
                int line = JavaSourceIndex.lineOf(node);
                if (!seen.add(line + "|" + info.api())) {
                    continue; // same API twice on one line: one TODO is enough
                }
                todos.add(new TranslatedSource.Todo(path, line, info.api(), note, JavaTypes.code(node)));
                commentLines.add("TODO(reggraeton) " + info.api() + ": " + note);
                if (message.length() > 0) {
                    message.append("; ");
                }
                message.append(fileName()).append(':').append(line).append(' ').append(info.api()).append(": ").append(note);
            }
            if (!attached(target)) {
                continue; // inside a statement that was already replaced by an outer stub
            }
            commentLines.add("original (" + fileName() + ":" + JavaSourceIndex.lineOf(target) + "): "
                    + JavaTypes.code(target));
            Node stub = replaceWithStub(target, message.toString(), commentLines, nodes);
            indentComment(stub);
        }
    }

    /** The statement (or field initializer) replaced by a stub when {@code node} cannot be translated. */
    private static Node stubTarget(Node node) {
        for (Node p = node; p != null; p = p.getParentNode().orElse(null)) {
            if (p instanceof Statement s && !(s instanceof BlockStmt)) {
                return s;
            }
            if (p instanceof VariableDeclarator v && v.getParentNode().orElse(null) instanceof FieldDeclaration) {
                return v;
            }
            if (p instanceof FieldDeclaration || p instanceof AnnotationExpr) {
                return p;
            }
        }
        return node;
    }

    private Node replaceWithStub(Node target, String message, List<String> commentLines, List<Node> nodes) {
        MethodCallExpr fail = new MethodCallExpr(new NameExpr("PendingConversion"), "fail",
                new NodeList<>(Selectors.literal(message)));
        BlockComment comment = new BlockComment(blockComment(commentLines));
        if (target instanceof VariableDeclarator v) {
            v.setInitializer(fail);
            v.getParentNode().ifPresent(f -> ((Node) f).setComment(comment));
        } else if (target instanceof ExpressionStmt stmt && stmt.getExpression() instanceof VariableDeclarationExpr decl) {
            for (VariableDeclarator v : decl.getVariables()) {
                v.setInitializer(fail.clone());
            }
            stmt.setComment(comment);
        } else if (target instanceof ReturnStmt ret && ret.getExpression().isPresent()) {
            ReturnStmt replacement = new ReturnStmt(fail);
            replacement.setComment(comment);
            ret.replace(replacement);
            target = replacement;
        } else if (target instanceof ExplicitConstructorInvocationStmt) {
            for (Node node : nodes) {
                if (attached(node)) {
                    node.replace(fail.clone());
                }
            }
            target.setComment(comment);
        } else if (target instanceof Statement stmt) {
            boolean needsReturn = containsReturn(stmt) && isLastStatementOfValueMethod(stmt);
            Statement replacement = needsReturn ? new ReturnStmt(fail) : new ExpressionStmt(fail);
            replacement.setComment(comment);
            stmt.replace(replacement);
            target = replacement;
        } else {
            target.setComment(comment);
        }
        changes.merge("TODO(reggraeton) stub", 1, Integer::sum);
        return target;
    }

    /**
     * Line comments read better than a block comment (the printer does not indent block comment
     * lines): inside a block, each line becomes an empty statement carrying a line comment; the
     * stray {@code ;} lines are dropped when printing.
     */
    private static void indentComment(Node stub) {
        if (!(stub instanceof Statement stmt) || !(stmt.getParentNode().orElse(null) instanceof BlockStmt block)
                || stmt.getComment().isEmpty() || !(stmt.getComment().get() instanceof BlockComment comment)) {
            return;
        }
        int i = block.getStatements().indexOf(stmt);
        List<Statement> lines = new ArrayList<>();
        for (String line : comment.getContent().split("\n")) {
            String text = line.strip();
            if (text.startsWith("*")) {
                text = text.substring(1).strip();
            }
            if (!text.isEmpty()) {
                EmptyStmt empty = new EmptyStmt();
                empty.setComment(new LineComment(" " + text));
                lines.add(empty);
            }
        }
        stmt.removeComment();
        block.getStatements().addAll(i, lines);
    }

    private static boolean containsReturn(Statement stmt) {
        return stmt.findAll(ReturnStmt.class).stream()
                .anyMatch(r -> r.findAncestor(LambdaExpr.class).map(l -> !stmt.isAncestorOf(l)).orElse(true));
    }

    private static boolean isLastStatementOfValueMethod(Statement stmt) {
        if (!(stmt.getParentNode().orElse(null) instanceof BlockStmt block)) {
            return false;
        }
        if (!(block.getParentNode().orElse(null) instanceof MethodDeclaration method) || method.getType().isVoidType()) {
            return false;
        }
        return block.getStatements().getLast().map(s -> s == stmt).orElse(false);
    }

    private static String blockComment(List<String> lines) {
        StringBuilder sb = new StringBuilder("\n");
        for (String line : lines) {
            sb.append(" * ").append(line.replace("*/", "* /")).append('\n');
        }
        return sb.append(' ').toString();
    }

    // =========================================================================================
    // Pass 2: @FindBy fields
    // =========================================================================================

    private void convertFindByFields(Map<Node, String> pageExpressions) {
        for (FieldDeclaration field : unit.findAll(FieldDeclaration.class)) {
            List<AnnotationExpr> selenium = field.getAnnotations().stream()
                    .filter(a -> Expressions.qualifiedAnnotationName(a).startsWith("org.openqa.selenium.support."))
                    .toList();
            if (selenium.isEmpty()) {
                continue;
            }
            Optional<AnnotationExpr> locatorAnnotation = selenium.stream()
                    .filter(a -> Set.of("FindBy", "FindBys", "FindAll").contains(simpleName(a)))
                    .findFirst();
            selenium.forEach(AnnotationExpr::remove);
            if (locatorAnnotation.isEmpty()) {
                continue;
            }
            TypeDeclaration<?> owner = field.findAncestor(TypeDeclaration.class).orElse(null);
            String page = pageExpressions.getOrDefault(owner, "PlaywrightSession.current().page()");
            boolean list = field.getElementType() instanceof ClassOrInterfaceType t && t.getNameAsString().equals("List");
            AnnotationExpr annotation = locatorAnnotation.get();
            Expression locator = locatorFor(annotation, page);
            String lambdaBody;
            if (locator == null) {
                int line = JavaSourceIndex.lineOf(field);
                String note = "unsupported locator annotation " + JavaTypes.code(annotation);
                todos.add(new TranslatedSource.Todo(path, line, "@" + simpleName(annotation), note, JavaTypes.code(field)));
                lambdaBody = "PendingConversion.fail(" + Selectors.literal(fileName() + ":" + line + " " + note) + ")";
                field.setComment(new BlockComment(blockComment(List.of("TODO(reggraeton) " + note))));
            } else {
                boolean single = !list && !"FindAll".equals(simpleName(annotation));
                lambdaBody = locator + (single && !uniqueLocator(locator) ? ".first()" : "");
                changes.merge("@" + simpleName(annotation) + " -> lazy Locator field", 1, Integer::sum);
            }
            String initializer = (list ? "Locators.lazyAll(() -> " : "Locators.lazy(() -> ") + lambdaBody + ")";
            for (VariableDeclarator v : field.getVariables()) {
                v.setInitializer(com.github.javaparser.StaticJavaParser.parseExpression(initializer));
            }
        }
    }

    private static boolean uniqueLocator(Expression locator) {
        return locator instanceof MethodCallExpr call && call.getArguments().size() == 1
                && Selectors.isUnique(call.getArgument(0)) && call.getNameAsString().equals("locator");
    }

    private Expression locatorFor(AnnotationExpr annotation, String page) {
        switch (simpleName(annotation)) {
            case "FindBy" -> {
                Expression selector = findBySelector(annotation);
                return selector == null ? null : parse(page + ".locator(" + selector + ")");
            }
            case "FindBys", "FindAll" -> {
                Optional<Expression> value = Expressions.member(annotation, "value");
                if (value.isEmpty() || !(value.get() instanceof ArrayInitializerExpr array) || array.getValues().isEmpty()) {
                    return null;
                }
                List<Expression> selectors = new ArrayList<>();
                for (Expression e : array.getValues()) {
                    Expression s = e instanceof AnnotationExpr inner ? findBySelector(inner) : null;
                    if (s == null) {
                        return null;
                    }
                    selectors.add(s);
                }
                StringBuilder code = new StringBuilder(page + ".locator(" + selectors.get(0) + ")");
                for (int i = 1; i < selectors.size(); i++) {
                    code.append(simpleName(annotation).equals("FindBys")
                            ? ".locator(" + selectors.get(i) + ")"
                            : ".or(" + page + ".locator(" + selectors.get(i) + "))");
                }
                return parse(code.toString());
            }
            default -> {
                return null;
            }
        }
    }

    private static Expression findBySelector(AnnotationExpr annotation) {
        for (String member : List.of("id", "name", "className", "css", "tagName", "linkText", "partialLinkText", "xpath")) {
            Optional<Expression> value = Expressions.member(annotation, member);
            if (value.isPresent()) {
                return Selectors.selector(member, value.get());
            }
        }
        Optional<Expression> how = Expressions.member(annotation, "how");
        Optional<Expression> using = Expressions.member(annotation, "using");
        if (how.isPresent() && using.isPresent()) {
            String strategy = how.get() instanceof FieldAccessExpr f ? f.getNameAsString() : how.get().toString();
            return Selectors.selector(strategy, using.get());
        }
        return null;
    }

    /** {@code this.driver}, {@code this.getDriver()} or the session page, for lazy locator fields. */
    private String pageExpressionFor(TypeDeclaration<?> type) {
        Set<String> visited = new HashSet<>();
        TypeDeclaration<?> current = type;
        boolean sameClass = true;
        while (current != null && visited.add(JavaSourceIndex.qualifiedName(current))) {
            JavaTypes currentTypes = current.findCompilationUnit().map(JavaTypes::new).orElse(types);
            for (FieldDeclaration field : current.getFields()) {
                if (field.isStatic() || !(field.getElementType() instanceof ClassOrInterfaceType t)) {
                    continue;
                }
                boolean page = currentTypes.qualify(t).map(SourceTranslator::isPageType).orElse(false);
                if (page && (sameClass || !field.isPrivate())) {
                    if (sameClass && field.isFinal()) {
                        // a blank final field cannot be read in a field initializer's lambda
                        field.removeModifier(Modifier.Keyword.FINAL);
                    }
                    return "this." + field.getVariable(0).getNameAsString();
                }
            }
            for (MethodDeclaration method : current.getMethods()) {
                if (method.getParameters().isEmpty() && !method.isStatic() && (sameClass || !method.isPrivate())
                        && method.getType() instanceof ClassOrInterfaceType t
                        && currentTypes.qualify(t).map(SourceTranslator::isPageType).orElse(false)) {
                    return "this." + method.getNameAsString() + "()";
                }
            }
            current = superType(current);
            sameClass = false;
        }
        return "PlaywrightSession.current().page()";
    }

    /** Selenium driver types, or Playwright's Page when the declaring file was already translated. */
    private static boolean isPageType(String qualifiedName) {
        return "Page".equals(TYPE_MAP.get(qualifiedName)) || "com.microsoft.playwright.Page".equals(qualifiedName);
    }

    private TypeDeclaration<?> superType(TypeDeclaration<?> type) {
        if (!(type instanceof ClassOrInterfaceDeclaration cls) || cls.getExtendedTypes().isEmpty()) {
            return null;
        }
        try {
            String name = cls.getExtendedTypes(0).resolve().asReferenceType().getQualifiedName();
            return index.types().get(name);
        } catch (RuntimeException | StackOverflowError e) {
            String simple = cls.getExtendedTypes(0).getNameAsString();
            return index.types().values().stream().filter(t -> t.getNameAsString().equals(simple)).findFirst().orElse(null);
        }
    }

    // =========================================================================================
    // Pass 2: expression rewrites
    // =========================================================================================

    private void apply(Node node, Plan plan) {
        CallInfo info = infos.get(node);
        switch (plan) {
            case REMOVE_STATEMENT -> {
                Statement stmt = node.findAncestor(Statement.class).orElseThrow();
                EmptyStmt empty = new EmptyStmt();
                empty.setComment(new LineComment(" reggraeton: removed " + JavaTypes.code(stmt) + " (" + info.note() + ")"));
                stmt.replace(empty);
                changes.merge(info.api() + " -> removed", 1, Integer::sum);
            }
            case DRIVER_PAGE -> {
                node.replace(parse("PlaywrightSession.current().page()"));
                changes.merge(info.api() + " -> PlaywrightSession.current().page()", 1, Integer::sum);
            }
            case URL_CONFIG -> {
                node.replace(parse("RunConfig.baseUrl()"));
                changes.merge(JavaTypes.code(node) + " -> RunConfig.baseUrl()", 1, Integer::sum);
            }
            case REWRITE -> {
                Node replacement = node instanceof MethodCallExpr call ? rewrite(call, info.api()) : rewrite((ObjectCreationExpr) node, info.api());
                if (replacement != null && replacement != node) {
                    node.replace(replacement);
                }
                changes.merge(info.api(), 1, Integer::sum);
            }
            default -> {
            }
        }
    }

    private Node rewrite(ObjectCreationExpr creation, String api) {
        return switch (api) {
            case "Select.<init>" -> creation.getArgument(0);
            case "WebDriverWait.<init>", "FluentWait.<init>" -> {
                NodeList<Expression> args = new NodeList<>();
                args.add(creation.getArgument(0));
                if (creation.getArguments().size() >= 2) {
                    args.add(creation.getArgument(1));
                }
                yield new ObjectCreationExpr(null, new ClassOrInterfaceType(null, "Waits"), args);
            }
            default -> creation;
        };
    }

    private Node rewrite(MethodCallExpr call, String api) {
        Expression s = call.getScope().map(SourceTranslator::stripCasts).orElse(null);
        NodeList<Expression> args = call.getArguments();
        switch (api) {
            case "WebDriver.get":
                return call(s, "navigate", args.get(0));
            case "WebDriver.getTitle":
                return call(s, "title");
            case "WebDriver.getCurrentUrl":
                return call(s, "url");
            case "WebDriver.getPageSource":
                return call(s, "content");
            case "WebDriver.close":
                return call(s, "close");
            case "WebDriver.findElement", "SearchContext.findElement", "WebElement.findElement": {
                MethodCallExpr locator = call(s, "locator", args.get(0));
                return Selectors.isUnique(args.get(0)) ? locator : call(locator, "first");
            }
            case "WebDriver.findElements", "SearchContext.findElements", "WebElement.findElements":
                return new MethodCallExpr(new NameExpr("Elements"), "findAll", new NodeList<>(call(s, "locator", args.get(0))));
            case "WebDriver.Navigation.to":
                return call(scopeOf(s), "navigate", args.get(0));
            case "WebDriver.Navigation.back":
                return call(scopeOf(s), "goBack");
            case "WebDriver.Navigation.forward":
                return call(scopeOf(s), "goForward");
            case "WebDriver.Navigation.refresh":
                return call(scopeOf(s), "reload");
            case "WebDriver.Options.deleteAllCookies":
                return call(call(scopeOf(s), "context"), "clearCookies");
            case "WebDriver.Window.setSize": {
                ObjectCreationExpr dimension = (ObjectCreationExpr) args.get(0);
                return call(scopeOf(scopeOf(s)), "setViewportSize", dimension.getArgument(0), dimension.getArgument(1));
            }
            case "WebDriver.Timeouts.pageLoadTimeout": {
                Expression millis = args.size() == 1 ? call(args.get(0), "toMillis") : call(args.get(1), "toMillis", args.get(0));
                return call(scopeOf(scopeOf(s)), "setDefaultNavigationTimeout", millis);
            }
            case "WebDriver.TargetLocator.activeElement":
                return call(scopeOf(s), "locator", Selectors.literal("*:focus"));
            case "WebElement.click":
                return call(s, "click");
            case "WebElement.submit":
                return call(s, "press", Selectors.literal("Enter"));
            case "WebElement.clear":
                return call(s, "clear");
            case "WebElement.sendKeys":
                return sendKeys(call, s, args);
            case "WebElement.getText":
                return call(s, "innerText");
            case "WebElement.getAttribute": {
                String constant = Expressions.constantString(args.get(0));
                return "value".equals(constant) ? call(s, "inputValue") : call(s, "getAttribute", args.get(0));
            }
            case "WebElement.getDomAttribute":
                return call(s, "getAttribute", args.get(0));
            case "WebElement.getDomProperty":
                return valueOf(call(s, "evaluate", Selectors.literal("(e, p) => e[p]"), args.get(0)));
            case "WebElement.getCssValue":
                return valueOf(call(s, "evaluate", Selectors.literal("(e, p) => getComputedStyle(e).getPropertyValue(p)"), args.get(0)));
            case "WebElement.isDisplayed":
                return call(s, "isVisible");
            case "WebElement.isEnabled":
                return call(s, "isEnabled");
            case "WebElement.isSelected":
                return call(s, "isChecked");
            case "WebElement.getTagName":
                return valueOf(call(s, "evaluate", Selectors.literal("e => e.tagName.toLowerCase()")));
            case "JavascriptExecutor.executeScript":
                return call(s, "evaluate", wrapScript(args.get(0)));
            case "TakesScreenshot.getScreenshotAs", "WebElement.getScreenshotAs": {
                MethodCallExpr shot = call(s, "screenshot");
                String kind = ((FieldAccessExpr) args.get(0)).getNameAsString();
                return switch (kind) {
                    case "FILE" -> new MethodCallExpr(new NameExpr("Screenshots"), "toFile", new NodeList<>(shot));
                    case "BASE64" -> new MethodCallExpr(new NameExpr("Screenshots"), "toBase64", new NodeList<>(shot));
                    default -> shot;
                };
            }
            case "Select.selectByVisibleText":
                return call(s, "selectOption", parse("new SelectOption().setLabel(" + args.get(0) + ")"));
            case "Select.selectByValue":
                return call(s, "selectOption", args.get(0));
            case "Select.selectByIndex":
                return call(s, "selectOption", parse("new SelectOption().setIndex(" + args.get(0) + ")"));
            case "FluentWait.until": {
                MethodCallExpr condition = (MethodCallExpr) args.get(0);
                MethodCallExpr wait = new MethodCallExpr(s, CONDITIONS.get(condition.getNameAsString()));
                condition.getArguments().forEach(a -> wait.addArgument(a.clone()));
                return wait;
            }
            case "FluentWait.withTimeout", "FluentWait.pollingEvery", "FluentWait.ignoring", "FluentWait.withMessage":
                return call;
            case "Actions.perform", "Action.perform":
                rewriteActionChain(call);
                return null;
            default:
                if (api.startsWith("By.")) {
                    Expression selector = Selectors.selector(call.getNameAsString(), args.get(0));
                    return selector == null ? call : selector;
                }
                return call;
        }
    }

    private Node sendKeys(MethodCallExpr call, Expression element, NodeList<Expression> args) {
        if (args.size() == 1) {
            Expression arg = args.get(0);
            if (arg instanceof FieldAccessExpr f && f.getScope() instanceof NameExpr n && n.getNameAsString().equals("Keys")
                    && KEYS.containsKey(f.getNameAsString())) {
                return call(element, "press", Selectors.literal(KEYS.get(f.getNameAsString())));
            }
            if (stringArguments.contains(call) && previousStatementClears(call, element)) {
                return call(element, "fill", arg);
            }
        }
        MethodCallExpr helper = new MethodCallExpr(new NameExpr("Elements"), "sendKeys");
        helper.addArgument(element.clone());
        args.forEach(a -> helper.addArgument(a.clone()));
        return helper;
    }

    /** True when the statement before {@code call}'s statement is {@code <same element>.clear();}. */
    private static boolean previousStatementClears(MethodCallExpr call, Expression element) {
        Optional<Statement> stmt = call.findAncestor(Statement.class);
        if (stmt.isEmpty() || !(stmt.get().getParentNode().orElse(null) instanceof BlockStmt block)) {
            return false;
        }
        int i = block.getStatements().indexOf(stmt.get());
        if (i <= 0) {
            return false;
        }
        Statement previous = block.getStatement(i - 1);
        return previous instanceof ExpressionStmt e && e.getExpression() instanceof MethodCallExpr m
                && m.getNameAsString().equals("clear") && m.getArguments().isEmpty()
                && m.getScope().map(sc -> stripCasts(sc).toString().equals(element.toString())).orElse(false);
    }

    private void rewriteActionChain(MethodCallExpr perform) {
        List<MethodCallExpr> steps = new ArrayList<>();
        Expression current = perform.getScope().orElse(null);
        while (current instanceof MethodCallExpr call) {
            steps.add(0, call);
            current = call.getScope().orElse(null);
        }
        Expression page = ((ObjectCreationExpr) current).getArgument(0);
        List<Statement> statements = new ArrayList<>();
        Expression target = null;
        for (MethodCallExpr step : steps) {
            NodeList<Expression> a = step.getArguments();
            switch (step.getNameAsString()) {
                case "moveToElement" -> {
                    target = a.get(0);
                    statements.add(stmt(call(target, "hover")));
                }
                case "scrollToElement" -> {
                    target = a.get(0);
                    statements.add(stmt(call(target, "scrollIntoViewIfNeeded")));
                }
                case "click" -> {
                    target = a.isEmpty() ? target : a.get(0);
                    statements.add(stmt(call(target, "click")));
                }
                case "doubleClick" -> {
                    target = a.isEmpty() ? target : a.get(0);
                    statements.add(stmt(call(target, "dblclick")));
                }
                case "contextClick" -> {
                    target = a.isEmpty() ? target : a.get(0);
                    statements.add(stmt(call(target, "click",
                            parse("new Locator.ClickOptions().setButton(MouseButton.RIGHT)"))));
                }
                case "dragAndDrop" -> statements.add(stmt(call(a.get(0), "dragTo", a.get(1))));
                case "sendKeys" -> {
                    boolean withTarget = a.size() >= 2 && !JavaTypes.isString(a.get(0)) && !(a.get(0) instanceof FieldAccessExpr);
                    if (withTarget) {
                        MethodCallExpr helper = new MethodCallExpr(new NameExpr("Elements"), "sendKeys");
                        a.forEach(x -> helper.addArgument(x.clone()));
                        statements.add(stmt(helper));
                    } else {
                        for (Expression x : a) {
                            if (x instanceof FieldAccessExpr f && f.getScope() instanceof NameExpr n
                                    && n.getNameAsString().equals("Keys") && KEYS.containsKey(f.getNameAsString())) {
                                statements.add(stmt(call(call(page, "keyboard"), "press", Selectors.literal(KEYS.get(f.getNameAsString())))));
                            } else {
                                Expression text = JavaTypes.isString(x) ? x.clone()
                                        : new MethodCallExpr(new NameExpr("String"), "valueOf", new NodeList<>(x.clone()));
                                statements.add(stmt(call(call(page, "keyboard"), "type", text)));
                            }
                        }
                    }
                }
                default -> {
                    // build(): nothing to do
                }
            }
        }
        Statement original = perform.findAncestor(Statement.class).orElseThrow();
        if (original.getParentNode().orElse(null) instanceof BlockStmt block) {
            int i = block.getStatements().indexOf(original);
            if (!statements.isEmpty()) {
                statements.get(0).setComment(new LineComment(" reggraeton: action chain " + JavaTypes.code(perform)));
            }
            block.getStatements().remove(i);
            block.getStatements().addAll(i, statements);
        } else {
            original.replace(new BlockStmt(new NodeList<>(statements)));
        }
    }

    private static Expression wrapScript(Expression script) {
        String constant = Expressions.constantString(script);
        if (constant != null) {
            return Selectors.literal("() => { " + constant + " }");
        }
        return new BinaryExpr(new BinaryExpr(Selectors.literal("() => { "), script.clone(), BinaryExpr.Operator.PLUS),
                Selectors.literal(" }"), BinaryExpr.Operator.PLUS);
    }

    private static Expression valueOf(Expression e) {
        return new MethodCallExpr(new NameExpr("String"), "valueOf", new NodeList<>(e));
    }

    private static MethodCallExpr call(Expression scope, String name, Expression... args) {
        NodeList<Expression> list = new NodeList<>();
        for (Expression a : args) {
            list.add(a.clone());
        }
        return new MethodCallExpr(scope == null ? null : scope.clone(), name, list);
    }

    private static Statement stmt(Expression e) {
        return new ExpressionStmt(e);
    }

    /** {@code ((JavascriptExecutor) driver)} becomes {@code driver}. */
    private static Expression stripCasts(Expression e) {
        Expression current = e;
        while (true) {
            if (current instanceof EnclosedExpr enclosed && (enclosed.getInner() instanceof CastExpr || enclosed.getInner() instanceof EnclosedExpr)) {
                current = enclosed.getInner();
            } else if (current instanceof CastExpr cast && cast.getType() instanceof ClassOrInterfaceType t
                    && Set.of("JavascriptExecutor", "TakesScreenshot", "WebDriver", "RemoteWebDriver").contains(t.getNameAsString())) {
                current = cast.getExpression();
            } else {
                return current;
            }
        }
    }

    private static Expression scopeOf(Expression e) {
        return e instanceof MethodCallExpr m && m.getScope().isPresent() ? stripCasts(m.getScope().get()) : e;
    }

    private static Expression parse(String code) {
        return com.github.javaparser.StaticJavaParser.parseExpression(code);
    }

    // =========================================================================================
    // Pass 2: types, catch clauses, imports
    // =========================================================================================

    private void mapTypes() {
        List<ClassOrInterfaceType> all = unit.findAll(ClassOrInterfaceType.class).stream()
                .filter(t -> !(t.getParentNode().orElse(null) instanceof ClassOrInterfaceType p && p.getScope().map(sc -> sc == t).orElse(false)))
                .toList();
        for (ClassOrInterfaceType type : all) {
            if (!attached(type)) {
                continue;
            }
            Optional<String> qualified = types.qualify(type);
            if (qualified.isEmpty()) {
                continue;
            }
            String fqn = qualified.get();
            String replacement = TYPE_MAP.get(fqn);
            if (replacement == null && (fqn.startsWith("org.openqa.selenium.") || fqn.startsWith("io.github.bonigarcia."))) {
                replacement = "Object";
            }
            if (replacement == null) {
                continue;
            }
            type.replace(new ClassOrInterfaceType(null, replacement));
            changes.merge("type " + JavaSourceIndex.withoutPackage(fqn) + " -> " + replacement, 1, Integer::sum);
        }
    }

    private void fixCatchClauses() {
        for (CatchClause clause : unit.findAll(CatchClause.class)) {
            Parameter parameter = clause.getParameter();
            if (!(parameter.getType() instanceof UnionType union)) {
                continue;
            }
            Set<String> names = new LinkedHashSet<>();
            union.getElements().forEach(t -> names.add(t.asString()));
            if (names.contains("PlaywrightException")) {
                names.remove("TimeoutError");
            }
            if (names.size() == union.getElements().size()) {
                continue;
            }
            if (names.size() == 1) {
                parameter.setType(new ClassOrInterfaceType(null, names.iterator().next()));
            } else {
                NodeList<com.github.javaparser.ast.type.ReferenceType> elements = new NodeList<>();
                names.forEach(n -> elements.add(new ClassOrInterfaceType(null, n)));
                parameter.setType(new UnionType(elements));
            }
        }
    }

    private void fixImports() {
        NodeList<ImportDeclaration> imports = unit.getImports();
        imports.removeIf(i -> i.getNameAsString().startsWith("org.openqa.selenium") || i.getNameAsString().startsWith("io.github.bonigarcia"));
        Set<String> used = new HashSet<>();
        unit.findAll(ClassOrInterfaceType.class).forEach(t -> used.add(outermost(t)));
        unit.findAll(NameExpr.class).forEach(n -> used.add(n.getNameAsString()));
        Set<String> declared = new HashSet<>();
        unit.findAll(TypeDeclaration.class).forEach(t -> declared.add(t.getNameAsString()));
        Set<String> imported = new HashSet<>();
        imports.forEach(i -> imported.add(i.getName().getIdentifier()));
        List<String> toAdd = new ArrayList<>();
        for (String name : used) {
            String fqn = support.importFor(name);
            if (fqn != null && !declared.contains(name) && !imported.contains(name)
                    && !fqn.startsWith(unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("#"))) {
                toAdd.add(fqn);
            }
        }
        Collections.sort(toAdd);
        for (String fqn : toAdd) {
            unit.addImport(fqn);
        }
    }

    private static String outermost(ClassOrInterfaceType type) {
        ClassOrInterfaceType t = type;
        while (t.getScope().isPresent()) {
            t = t.getScope().get();
        }
        return t.getNameAsString();
    }

    // =========================================================================================

    private boolean attached(Node node) {
        Node current = node;
        while (current.getParentNode().isPresent()) {
            current = current.getParentNode().get();
        }
        return current == unit;
    }

    private static int depth(Node node) {
        int depth = 0;
        for (Node p = node; p.getParentNode().isPresent(); p = p.getParentNode().get()) {
            depth++;
        }
        return depth;
    }

    private String fileName() {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String simpleName(AnnotationExpr annotation) {
        String name = annotation.getNameAsString();
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
