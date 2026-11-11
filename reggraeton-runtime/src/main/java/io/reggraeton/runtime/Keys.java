package io.reggraeton.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * Drop-in for Selenium's {@code org.openqa.selenium.Keys}: same constant names and the same
 * private-use characters, so expressions such as {@code "abc" + Keys.ENTER} and
 * {@code Keys.chord(Keys.CONTROL, "a")} keep working. {@link Elements#sendKeys} turns them into
 * Playwright key presses.
 */
public enum Keys implements CharSequence {
    NULL('', null),
    CANCEL('', "Cancel"),
    HELP('', "Help"),
    BACK_SPACE('', "Backspace"),
    TAB('', "Tab"),
    CLEAR('', "Clear"),
    RETURN('', "Enter"),
    ENTER('', "Enter"),
    SHIFT('', "Shift"),
    LEFT_SHIFT('', "Shift"),
    CONTROL('', "Control"),
    LEFT_CONTROL('', "Control"),
    ALT('', "Alt"),
    LEFT_ALT('', "Alt"),
    PAUSE('', "Pause"),
    ESCAPE('', "Escape"),
    SPACE('', " "),
    PAGE_UP('', "PageUp"),
    PAGE_DOWN('', "PageDown"),
    END('', "End"),
    HOME('', "Home"),
    LEFT('', "ArrowLeft"),
    ARROW_LEFT('', "ArrowLeft"),
    UP('', "ArrowUp"),
    ARROW_UP('', "ArrowUp"),
    RIGHT('', "ArrowRight"),
    ARROW_RIGHT('', "ArrowRight"),
    DOWN('', "ArrowDown"),
    ARROW_DOWN('', "ArrowDown"),
    INSERT('', "Insert"),
    DELETE('', "Delete"),
    SEMICOLON('', ";"),
    EQUALS('', "="),
    NUMPAD0('', "Numpad0"),
    NUMPAD1('', "Numpad1"),
    NUMPAD2('', "Numpad2"),
    NUMPAD3('', "Numpad3"),
    NUMPAD4('', "Numpad4"),
    NUMPAD5('', "Numpad5"),
    NUMPAD6('', "Numpad6"),
    NUMPAD7('', "Numpad7"),
    NUMPAD8('', "Numpad8"),
    NUMPAD9('', "Numpad9"),
    MULTIPLY('', "NumpadMultiply"),
    ADD('', "NumpadAdd"),
    SEPARATOR('', "NumpadDecimal"),
    SUBTRACT('', "NumpadSubtract"),
    DECIMAL('', "NumpadDecimal"),
    DIVIDE('', "NumpadDivide"),
    F1('', "F1"),
    F2('', "F2"),
    F3('', "F3"),
    F4('', "F4"),
    F5('', "F5"),
    F6('', "F6"),
    F7('', "F7"),
    F8('', "F8"),
    F9('', "F9"),
    F10('', "F10"),
    F11('', "F11"),
    F12('', "F12"),
    META('', "Meta"),
    COMMAND('', "Meta");

    private static final Map<Character, Keys> BY_CHAR = new HashMap<>();

    static {
        for (Keys key : values()) {
            BY_CHAR.putIfAbsent(key.code, key);
        }
    }

    private final char code;
    private final String playwrightKey;

    Keys(char code, String playwrightKey) {
        this.code = code;
        this.playwrightKey = playwrightKey;
    }

    /** Playwright key name ({@code Enter}, {@code ArrowDown}, ...); null for {@link #NULL}. */
    public String playwrightKey() {
        return playwrightKey;
    }

    public boolean isModifier() {
        return code == '' || code == '' || code == '' || code == '';
    }

    /** The key a private-use character stands for, or null for an ordinary character. */
    public static Keys of(char c) {
        return BY_CHAR.get(c);
    }

    /** Like Selenium: modifiers stay pressed for the rest of the chord. */
    public static String chord(CharSequence... value) {
        StringBuilder sb = new StringBuilder();
        for (CharSequence part : value) {
            sb.append(part);
        }
        return sb.append(NULL.code).toString();
    }

    @Override
    public int length() {
        return 1;
    }

    @Override
    public char charAt(int index) {
        if (index != 0) {
            throw new IndexOutOfBoundsException(index);
        }
        return code;
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return String.valueOf(code).subSequence(start, end);
    }

    @Override
    public String toString() {
        return String.valueOf(code);
    }
}
