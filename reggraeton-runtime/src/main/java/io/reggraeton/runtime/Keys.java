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
    NULL('\uE000', null),
    CANCEL('\uE001', "Cancel"),
    HELP('\uE002', "Help"),
    BACK_SPACE('\uE003', "Backspace"),
    TAB('\uE004', "Tab"),
    CLEAR('\uE005', "Clear"),
    RETURN('\uE006', "Enter"),
    ENTER('\uE007', "Enter"),
    SHIFT('\uE008', "Shift"),
    LEFT_SHIFT('\uE008', "Shift"),
    CONTROL('\uE009', "Control"),
    LEFT_CONTROL('\uE009', "Control"),
    ALT('\uE00A', "Alt"),
    LEFT_ALT('\uE00A', "Alt"),
    PAUSE('\uE00B', "Pause"),
    ESCAPE('\uE00C', "Escape"),
    SPACE('\uE00D', " "),
    PAGE_UP('\uE00E', "PageUp"),
    PAGE_DOWN('\uE00F', "PageDown"),
    END('\uE010', "End"),
    HOME('\uE011', "Home"),
    LEFT('\uE012', "ArrowLeft"),
    ARROW_LEFT('\uE012', "ArrowLeft"),
    UP('\uE013', "ArrowUp"),
    ARROW_UP('\uE013', "ArrowUp"),
    RIGHT('\uE014', "ArrowRight"),
    ARROW_RIGHT('\uE014', "ArrowRight"),
    DOWN('\uE015', "ArrowDown"),
    ARROW_DOWN('\uE015', "ArrowDown"),
    INSERT('\uE016', "Insert"),
    DELETE('\uE017', "Delete"),
    SEMICOLON('\uE018', ";"),
    EQUALS('\uE019', "="),
    NUMPAD0('\uE01A', "Numpad0"),
    NUMPAD1('\uE01B', "Numpad1"),
    NUMPAD2('\uE01C', "Numpad2"),
    NUMPAD3('\uE01D', "Numpad3"),
    NUMPAD4('\uE01E', "Numpad4"),
    NUMPAD5('\uE01F', "Numpad5"),
    NUMPAD6('\uE020', "Numpad6"),
    NUMPAD7('\uE021', "Numpad7"),
    NUMPAD8('\uE022', "Numpad8"),
    NUMPAD9('\uE023', "Numpad9"),
    MULTIPLY('\uE024', "NumpadMultiply"),
    ADD('\uE025', "NumpadAdd"),
    SEPARATOR('\uE026', "NumpadDecimal"),
    SUBTRACT('\uE027', "NumpadSubtract"),
    DECIMAL('\uE028', "NumpadDecimal"),
    DIVIDE('\uE029', "NumpadDivide"),
    F1('\uE031', "F1"),
    F2('\uE032', "F2"),
    F3('\uE033', "F3"),
    F4('\uE034', "F4"),
    F5('\uE035', "F5"),
    F6('\uE036', "F6"),
    F7('\uE037', "F7"),
    F8('\uE038', "F8"),
    F9('\uE039', "F9"),
    F10('\uE03A', "F10"),
    F11('\uE03B', "F11"),
    F12('\uE03C', "F12"),
    META('\uE03D', "Meta"),
    COMMAND('\uE03D', "Meta");

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
        return code == '\uE008' || code == '\uE009' || code == '\uE00A' || code == '\uE03D';
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
