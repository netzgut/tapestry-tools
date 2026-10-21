package net.netzgut.eclipse.tapestry.internal.core;

/// Small String helpers shared by all features. Plain Java, no Eclipse APIs.
public final class Strings {

    /// `null` for `null`, empty and whitespace-only values, otherwise the value as is.
    public static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value;
    }

    /// `null` for `null`, empty and whitespace-only values, otherwise the value without leading and
    /// trailing whitespace.
    public static String stripToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.strip();
    }

    /// `"success"` -> `"Success"`.
    public static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }

        char firstLetter = Character.toUpperCase(value.charAt(0));
        return firstLetter + value.substring(1);
    }

    /// Like `java.beans.Introspector`: `UserDetails` -> `userDetails`, but `URL` stays.
    public static String decapitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }

        if (value.length() > 1 && Character.isUpperCase(value.charAt(1))) {
            return value;
        }

        char firstLetter = Character.toLowerCase(value.charAt(0));
        return firstLetter + value.substring(1);
    }

    /// `"\"text\""` -> `"text"`; values without surrounding double quotes are returned as is.
    public static String unquote(String value) {
        if (value.length() < 2 //
            || value.startsWith("\"") == false
            || value.endsWith("\"") == false) {
            return value;
        }

        return value.substring(1, value.length() - 1);
    }

    /// Escapes text for HTML content: `&`, `<` and `>`.
    public static String escapeHtml(String text) {
        return text.replace("&", "&amp;") //
                   .replace("<", "&lt;") //
                   .replace(">", "&gt;");
    }

    /// The reverse of [#escapeHtml(String)], plus `&quot;` and `&nbsp;` (as a plain space), as
    /// found in Javadoc HTML.
    public static String unescapeHtml(String html) {
        return html.replace("&nbsp;", " ") //
                   .replace("&lt;", "<") //
                   .replace("&gt;", ">") //
                   .replace("&quot;", "\"") //
                   .replace("&amp;", "&");
    }

    /// Counts the occurences of a character.
    public static long count(String value, char characterToCount) {
        return value.chars() //
                    .filter(c -> c == characterToCount) //
                    .count();
    }

    private Strings() {
    }
}
