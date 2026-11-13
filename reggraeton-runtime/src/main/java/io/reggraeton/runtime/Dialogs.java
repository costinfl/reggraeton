package io.reggraeton.runtime;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * JavaScript dialogs ({@code alert}, {@code confirm}, {@code prompt}) with Selenium-like code.
 *
 * <p>Selenium handles a dialog after it opened ({@code driver.switchTo().alert().accept()}).
 * Playwright must decide while the dialog is open, and an open dialog blocks the page, so it
 * cannot wait for a later {@code accept()}. Every browser context therefore gets a handler that
 * answers each dialog immediately and records it:</p>
 * <ul>
 *   <li>by default it <b>accepts</b> (setting {@code -Ddialogs=dismiss} / {@code DIALOGS=dismiss} changes that);</li>
 *   <li>{@link #expectDismiss()}, {@link #expectAccept()} and {@link #expectPrompt(String)} set the answer for
 *       the next dialog, and must be called before the action that opens it;</li>
 *   <li>{@link #alert(Page)} returns the recorded dialog: {@code getText()} works as before, and
 *       {@code accept()}/{@code dismiss()}/{@code sendKeys()} check that the dialog was answered that way.</li>
 * </ul>
 */
public final class Dialogs {

    /** How a dialog was answered. */
    public enum Answer { ACCEPT, DISMISS, PROMPT }

    /** What happened to one dialog. */
    public record Recorded(String type, String message, Answer answer, String promptText) {
    }

    /** Per browser context: dialogs answered but not yet consumed by the test, and the next expectation. */
    static final class Log {
        final Deque<Recorded> pending = new ArrayDeque<>();
        Answer nextAnswer;
        String nextPromptText;
    }

    private static final Map<BrowserContext, Log> LOGS = new WeakHashMap<>();
    /** How long {@link #alert(Page)} waits for a dialog that is not there yet (Selenium does not wait). */
    private static final double GRACE_MS = 1000;

    private Dialogs() {
    }

    /** Installs the dialog handler on a new browser context; called by {@link PlaywrightSession}. */
    public static void attach(BrowserContext context) {
        Log log = new Log();
        synchronized (LOGS) {
            LOGS.put(context, log);
        }
        context.onDialog(dialog -> handle(log, dialog));
    }

    private static void handle(Log log, Dialog dialog) {
        Answer answer;
        String text = null;
        synchronized (log) {
            answer = log.nextAnswer != null ? log.nextAnswer : defaultAnswer();
            text = log.nextPromptText;
            log.nextAnswer = null;
            log.nextPromptText = null;
        }
        try {
            if ("beforeunload".equals(dialog.type())) {
                dialog.accept();
                answer = Answer.ACCEPT;
            } else if (answer == Answer.DISMISS) {
                dialog.dismiss();
            } else if (answer == Answer.PROMPT) {
                dialog.accept(text == null ? "" : text);
            } else {
                dialog.accept();
            }
        } catch (PlaywrightException e) {
            // page closed meanwhile: nothing to answer
        }
        synchronized (log) {
            log.pending.add(new Recorded(dialog.type(), dialog.message(), answer, text));
        }
    }

    private static Answer defaultAnswer() {
        return "dismiss".equalsIgnoreCase(RunConfig.get("dialogs", "DIALOGS", "accept")) ? Answer.DISMISS : Answer.ACCEPT;
    }

    /** Answer the next dialog by dismissing it (Selenium: {@code alert.dismiss()}). Call before the triggering action. */
    public static void expectDismiss() {
        expect(Answer.DISMISS, null);
    }

    /** Answer the next dialog by accepting it. Call before the triggering action. */
    public static void expectAccept() {
        expect(Answer.ACCEPT, null);
    }

    /** Answer the next prompt with {@code text} (Selenium: {@code alert.sendKeys(text); alert.accept()}). */
    public static void expectPrompt(String text) {
        expect(Answer.PROMPT, text);
    }

    private static void expect(Answer answer, String text) {
        Log log = log(PlaywrightSession.current().context());
        synchronized (log) {
            log.nextAnswer = answer;
            log.nextPromptText = text;
        }
    }

    /** Selenium {@code driver.switchTo().alert()}: the oldest dialog the test has not handled yet. */
    public static Alert alert(Page page) {
        return waitFor(page, GRACE_MS);
    }

    /** Selenium {@code ExpectedConditions.alertIsPresent()}: waits up to {@code timeoutMs} for a dialog. */
    public static Alert waitFor(Page page, double timeoutMs) {
        Log log = log(page.context());
        long deadline = System.nanoTime() + (long) (timeoutMs * 1_000_000);
        while (true) {
            synchronized (log) {
                Recorded first = log.pending.peekFirst();
                if (first != null) {
                    return new Alert(log, first);
                }
            }
            if (System.nanoTime() > deadline) {
                throw new PlaywrightException("No dialog was opened (Selenium: NoAlertPresentException). "
                        + "Note: dialogs are answered as soon as they open, see the Dialogs support class.");
            }
            page.waitForTimeout(50);
        }
    }

    private static Log log(BrowserContext context) {
        synchronized (LOGS) {
            Log log = LOGS.get(context);
            if (log == null) {
                throw new IllegalStateException("Dialog handling is not installed on this browser context");
            }
            return log;
        }
    }
}
