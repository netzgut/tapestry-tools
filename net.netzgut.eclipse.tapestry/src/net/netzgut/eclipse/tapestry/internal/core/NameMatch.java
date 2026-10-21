package net.netzgut.eclipse.tapestry.internal.core;

import java.util.Locale;

import org.eclipse.jdt.core.compiler.CharOperation;

/// Filtering as users know it from Eclipse's own dialogs.
public final class NameMatch {

    /// Case-insensitive substring (`login`, `_de`) or camel case (`oSFL`) match.
    public static boolean matches(String filter, String name) {
        if (filter.isEmpty()) {
            return true;
        }

        String lowerCaseName = name.toLowerCase(Locale.ROOT);
        String lowerCaseFilter = filter.toLowerCase(Locale.ROOT);
        if (lowerCaseName.contains(lowerCaseFilter)) {
            return true;
        }

        return CharOperation.camelCaseMatch(filter.toCharArray(), name.toCharArray());
    }

    private NameMatch() {
    }

}
