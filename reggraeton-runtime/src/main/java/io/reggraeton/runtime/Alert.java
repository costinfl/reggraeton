package io.reggraeton.runtime;

/**
 * Stand-in for Selenium's {@code Alert}, returned by {@link Dialogs#alert}. The dialog was already
 * answered when it opened (see {@link Dialogs}); these methods check that the answer matches what the
 * test asks for, and mark the dialog as handled.
 */
public final class Alert {
    private final Dialogs.Log log;
    private final Dialogs.Recorded dialog;

    Alert(Dialogs.Log log, Dialogs.Recorded dialog) {
        this.log = log;
        this.dialog = dialog;
    }

    public String getText() {
        return dialog.message();
    }

    /** The dialog was accepted when it opened; this checks that and marks it handled. */
    public void accept() {
        if (dialog.answer() == Dialogs.Answer.DISMISS && !"alert".equals(dialog.type())) {
            throw mismatch("accept()", "it was dismissed", "Dialogs.expectAccept()");
        }
        consume();
    }

    /** Checks that the dialog was dismissed (plain alerts have only one button, so they always pass). */
    public void dismiss() {
        if (dialog.answer() != Dialogs.Answer.DISMISS && !"alert".equals(dialog.type())) {
            throw mismatch("dismiss()", "it was accepted", "Dialogs.expectDismiss()");
        }
        consume();
    }

    /** Checks that the prompt was answered with {@code text}. */
    public void sendKeys(String text) {
        if (dialog.answer() != Dialogs.Answer.PROMPT || !text.equals(dialog.promptText())) {
            throw mismatch("sendKeys(\"" + text + "\")", "it was answered "
                    + (dialog.answer() == Dialogs.Answer.PROMPT ? "with \"" + dialog.promptText() + "\"" : "without text"),
                    "Dialogs.expectPrompt(\"" + text + "\")");
        }
    }

    private void consume() {
        synchronized (log) {
            log.pending.remove(dialog);
        }
    }

    private IllegalStateException mismatch(String call, String what, String fix) {
        return new IllegalStateException("The " + dialog.type() + " \"" + dialog.message() + "\" cannot be handled with "
                + call + ": " + what + " as soon as it opened. Playwright answers dialogs while they are open, so call "
                + fix + " before the action that opens it.");
    }
}
