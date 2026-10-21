package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Naming conventions shared by all features. Deliberately free of Eclipse APIs so it can be tested
/// in isolation.
///
/// Files belong to a class if named like it, optionally followed by `_suffix`es: locales
/// (`Index_de`, `Index_de_AT`) or template variants picked by a custom resource locator
/// (`Index_B5`, `Index_B5_de`).
public final class TapestryConventions {

    public static final String TEMPLATE_EXTENSION = "tml";

    /// Files named like a class but not belonging to the class's resources.
    public static final Set<String> JAVA_EXTENSIONS = Set.of("java", //
                                                             "class");

    private static final String PAGE_SUFFIX = "page";

    /// The names Tapestry accepts for a page/component/mixin class, as computed by its
    /// `ComponentClassResolverImpl`: folder terms (including the library prefix) are stripped from
    /// the start or end of the class name (`admin.AdminUsers` -> `admin/Users`, the unstripped
    /// `admin/AdminUsers` stays valid); pages may also drop a `Page` suffix (`ProductPage` →
    /// `Product`), and index pages are reachable by their folder (`admin/Index` → `admin`).
    ///
    /// @param pathPrefix library prefix (`"frpc2"`), or `""` for the application
    /// @param relativeClassName class name relative to `<root>.pages`/`components`/`mixins`,
    ///            e.g. `"admin.AdminUsers"`
    public static Set<String> logicalNames(boolean page, String pathPrefix, String relativeClassName) {
        List<String> terms = new ArrayList<>();
        String[] prefixTerms = pathPrefix.split("/");
        addTerms(terms, prefixTerms);
        String[] classNameTerms = relativeClassName.split("\\.");
        addTerms(terms, classNameTerms);

        String logicalName = toLogicalName(terms, true);
        Set<String> names = new LinkedHashSet<>();
        names.add(logicalName);
        String unstrippedName = toLogicalName(terms, false);
        names.add(unstrippedName);

        if (page) {
            if (endsWithPage(logicalName)) {
                logicalName = logicalName.substring(0, logicalName.length() - PAGE_SUFFIX.length());
                names.add(logicalName);
            }

            int lastSlash = logicalName.lastIndexOf('/');
            String lastTerm = logicalName.substring(lastSlash + 1);
            if (lastTerm.equalsIgnoreCase("index") && lastSlash > 0) {
                String folderName = logicalName.substring(0, lastSlash);
                names.add(folderName);
            }
        }

        return names;
    }

    /// `ProductPage`, `admin/userpage`: ends with `page` in any case, right after a letter, digit or
    /// `_`. The same as Tapestry's `.*/?\w+page$` (case-insensitive), so `Page` and `admin/Page`
    /// keep their name.
    static boolean endsWithPage(String logicalName) {
        int suffixStart = logicalName.length() - PAGE_SUFFIX.length();
        if (suffixStart < 1) {
            return false;
        }

        if (!logicalName.regionMatches(true, suffixStart, PAGE_SUFFIX, 0, PAGE_SUFFIX.length())) {
            return false;
        }

        char before = logicalName.charAt(suffixStart - 1);
        return isWordChar(before);
    }

    /// Like `\w` in a regex: ASCII letters, digits and `_`.
    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static String toLogicalName(List<String> terms, boolean stripTerms) {
        String unstripped = terms.get(terms.size() - 1);
        String logicalName = unstripped;
        List<String> folders = terms.subList(0, terms.size() - 1);
        if (stripTerms) {
            for (String term : folders) {
                if (logicalName.regionMatches(true, 0, term, 0, term.length())) {
                    logicalName = logicalName.substring(term.length());
                }

                if (logicalName.regionMatches(true, logicalName.length() - term.length(), term, 0, term.length())) {
                    logicalName = logicalName.substring(0, logicalName.length() - term.length());
                }
            }
        }

        if (logicalName.isEmpty()) {
            logicalName = unstripped;
        }

        if (folders.isEmpty()) {
            return logicalName;
        }

        return String.join("/", folders) + "/" + logicalName;
    }

    private static void addTerms(List<String> terms, String[] values) {
        for (String value : values) {
            if (!value.isEmpty()) {
                terms.add(value);
            }
        }
    }

    /// True if a file base name (without extension), possibly with suffixes, belongs to the given
    /// class name.
    public static boolean belongsTo(String fileBaseName, String className) {
        return fileBaseName.equals(className) || fileBaseName.startsWith(className + "_");
    }

    /// Class names a file base name may belong to, most specific first: `"Index_B5_de"` →
    /// `[Index_B5_de, Index_B5, Index]`. Underscores in the package part of qualified names are
    /// not split.
    public static List<String> candidateClassNames(String fileBaseName) {
        List<String> names = new ArrayList<>();
        int lastDot = fileBaseName.lastIndexOf('.');
        String current = fileBaseName;
        names.add(current);

        int underscore;
        while ((underscore = current.lastIndexOf('_')) > lastDot + 1) {
            current = current.substring(0, underscore);
            names.add(current);
        }

        return names;
    }

    /// Computes the new name of a related file when its class is renamed, keeping suffixes and
    /// extension: `renameFile("Index_B5_de.tml", "Index", "Start")` → `"Start_B5_de.tml"`. Returns
    /// `null` if the file does not belong to `oldClassName`.
    public static String renameFile(String fileName, String oldClassName, String newClassName) {
        int dot = fileName.lastIndexOf('.');
        String baseName = fileName;
        String extension = "";
        if (dot >= 0) {
            baseName = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }

        if (!belongsTo(baseName, oldClassName)) {
            return null;
        }

        return newClassName + baseName.substring(oldClassName.length()) + extension;
    }

    /// Splits a comma/whitespace separated preference value: `" overrides, custom "` → `[overrides,
    /// custom]`. Leading dots are dropped so extensions may be entered as `.bak`.
    public static List<String> splitList(String value) {
        List<String> result = new ArrayList<>();
        for (String entry : value.split("[,\\s]+")) {
            String trimmed = entry.strip();
            while (trimmed.startsWith(".")) {
                trimmed = trimmed.substring(1);
            }

            if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }

        return result;
    }

    /// Folder and extension names are single path segments.
    public static boolean isValidName(String name) {
        return !name.isEmpty() && name.chars().noneMatch(c -> c == '/' || c == '\\' || c == ':' || c == '*');
    }

    private TapestryConventions() {
    }
}
