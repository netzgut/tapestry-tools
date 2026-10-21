package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;

/// Resolves component, mixin and page names as written in templates (`layout/Header`, `frpc2/Bar`,
/// `core/Form`) to classes, without the Tapestry registry:
///
/// 1. If the name starts with a known library prefix ([LibraryMappings]), the class must be in
///    `<library package>.components[.sub]`.
/// 2. Otherwise, or if that finds nothing, any class with that simple name in a `*.components[.sub]`
///    package matches, with or without the first segment as (unknown) library prefix.
/// 3. If neither finds a class named like the last segment, the shortened names Tapestry also
///    accepts are tried (`admin/Users` for `pages.admin.AdminUsers`, `admin` for
///    `pages.admin.Index`, see [TapestryConventions#logicalNames]).
///
/// Names are case-insensitive, like in Tapestry.
///
/// Results are cached across lookups and reused as long as all resolved classes still exist;
/// otherwise (deleted, renamed, moved) the search runs again. Empty results are not cached, so new
/// classes are found right away.
public final class ComponentResolver {

    public enum Kind {

        COMPONENT("components"),
        MIXIN("mixins"),
        PAGE("pages");

        final String folder;

        Kind(String folder) {
            this.folder = folder;
        }
    }

    private static final Map<String, List<IType>> CACHE = new ConcurrentHashMap<>();

    private final IJavaProject        project;
    private final IProgressMonitor    monitor;
    private Map<String, List<String>> libraries;
    private boolean                   librariesRefreshed;

    public ComponentResolver(IJavaProject project, IProgressMonitor monitor) {
        this.project = project;
        this.monitor = monitor;
    }

    public List<IType> resolve(Kind kind, String name) throws CoreException {
        String key = this.project.getElementName() + ":" + kind + ":" + name.toLowerCase(Locale.ROOT);
        List<IType> cached = CACHE.get(key);
        if (cached != null && cached.stream().allMatch(IType::exists)) {
            return cached;
        }

        String slashedName = name.replace('.', '/');
        List<IType> found = find(kind, slashedName);
        if (found.isEmpty()) {
            CACHE.remove(key);
        }
        else {
            CACHE.put(key, found);
        }

        return found;
    }

    private List<IType> find(Kind kind, String name) throws CoreException {
        Lookup lookup = lookup(kind, name);
        if (lookup.prefixKnown() && lookup.exact().isEmpty() && !this.librariesRefreshed) {
            // A mapping led nowhere: maybe the library moved, re-read the mappings once
            this.libraries = null;
            this.librariesRefreshed = true;
            lookup = lookup(kind, name);
        }

        if (!lookup.exact().isEmpty()) {
            return List.copyOf(lookup.exact());
        }

        if (!lookup.byConvention().isEmpty()) {
            return List.copyOf(lookup.byConvention());
        }

        return byLogicalName(kind, name);
    }

    /// Compares `name` with the logical names of all classes in `*.<folder>[.*]` packages. Slower
    /// than the simple name search, so only used when that finds nothing.
    private List<IType> byLogicalName(Kind kind, String name) throws CoreException {
        int slash = name.indexOf('/');
        String withoutPrefix;
        if (slash < 0) {
            withoutPrefix = null;
        }
        else {
            withoutPrefix = name.substring(slash + 1);
        }

        Map<String, String> prefixesByPackage = new HashMap<>();
        if (slash >= 0) {
            libraries().forEach((prefix, packages) -> packages.forEach(root -> prefixesByPackage.put(root, prefix)));
        }

        List<IType> exact = new ArrayList<>();
        List<IType> byConvention = new ArrayList<>();
        TypeNameMatchRequestor requestor = new TypeNameMatchRequestor() {

            @Override
            public void acceptTypeNameMatch(TypeNameMatch match) {
                IType type = match.getType();
                String packageName = match.getPackageName();
                int folder = ComponentNames.folderIndex(packageName, kind.folder);
                if (type.getDeclaringType() != null || folder < 0) {
                    return;
                }

                String root = packageName.substring(0, folder);
                String relative = type.getFullyQualifiedName().substring(folder + kind.folder.length() + 2);
                String prefix = prefixesByPackage.getOrDefault(root, "");
                for (String logicalName : TapestryConventions.logicalNames(kind == Kind.PAGE, prefix, relative)) {
                    if (logicalName.equalsIgnoreCase(name)) {
                        exact.add(type);
                        return;
                    }

                    if (prefix.isEmpty() && logicalName.equalsIgnoreCase(withoutPrefix)) {
                        byConvention.add(type); // first segment is an unknown library prefix
                        return;
                    }
                }
            }
        };

        IJavaSearchScope scope = SearchEngine.createJavaSearchScope(new IJavaElement[] { this.project });
        List<String> packagePatterns = List.of("*." + kind.folder, "*." + kind.folder + ".*");
        for (String packagePattern : packagePatterns) {
            new SearchEngine().searchAllTypeNames(packagePattern.toCharArray(),
                                                  SearchPattern.R_PATTERN_MATCH,
                                                  null,
                                                  SearchPattern.R_PATTERN_MATCH,
                                                  IJavaSearchConstants.CLASS,
                                                  scope,
                                                  requestor,
                                                  IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                                                  this.monitor);
        }
        if (exact.isEmpty()) {
            return List.copyOf(byConvention);
        }

        return List.copyOf(exact);
    }

    private record Lookup(boolean prefixKnown, List<IType> exact, List<IType> byConvention) {
    }

    private Lookup lookup(Kind kind, String name) throws CoreException {
        String[] segments = name.split("/");
        String simpleName = segments[segments.length - 1];
        List<String> path = List.of(segments).subList(0, segments.length - 1);

        // Exact packages from library mappings, longest prefix first ("a/b/Foo" with prefix "a/b" or "a")
        List<String> exactPackages = new ArrayList<>();
        for (int length = path.size(); length > 0 && exactPackages.isEmpty(); length--) {
            List<String> prefixPath = path.subList(0, length);
            String prefix = String.join("/", prefixPath).toLowerCase(Locale.ROOT);
            List<String> packages = libraries().get(prefix);
            if (packages != null) {
                for (String root : packages) {
                    List<String> subPath = path.subList(length, path.size());
                    String packageName = join(root + "." + kind.folder, subPath);
                    exactPackages.add(packageName);
                }
            }
        }

        // Package suffixes by convention, with and without the first segment as library prefix
        List<String> suffixes = new ArrayList<>();
        String suffix = join(kind.folder, path);
        suffixes.add(suffix);
        if (!path.isEmpty()) {
            List<String> pathWithoutPrefix = path.subList(1, path.size());
            String suffixWithoutPrefix = join(kind.folder, pathWithoutPrefix);
            suffixes.add(suffixWithoutPrefix);
        }

        List<IType> exact = new ArrayList<>();
        List<IType> byConvention = new ArrayList<>();
        IJavaSearchScope scope = SearchEngine.createJavaSearchScope(new IJavaElement[] { this.project });
        TypeNameMatchRequestor requestor = new TypeNameMatchRequestor() {

            @Override
            public void acceptTypeNameMatch(TypeNameMatch match) {
                String packageName = match.getPackageName();
                if (containsIgnoreCase(exactPackages, packageName)) {
                    exact.add(match.getType());
                }
                else if (endsWithAny(packageName, suffixes)) {
                    byConvention.add(match.getType());
                }
            }
        };

        new SearchEngine().searchAllTypeNames(null,
                                              SearchPattern.R_EXACT_MATCH,
                                              simpleName.toCharArray(),
                                              SearchPattern.R_EXACT_MATCH, // not case sensitive, like Tapestry
                                              IJavaSearchConstants.CLASS,
                                              scope,
                                              requestor,
                                              IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                                              this.monitor);

        boolean prefixKnown = !exactPackages.isEmpty();
        return new Lookup(prefixKnown, exact, byConvention);
    }

    /// Only needed when a name has a prefix, as reading them visits all
    /// `LibraryMapping` references.
    private Map<String, List<String>> libraries() throws CoreException {
        if (this.libraries == null) {
            this.libraries = new HashMap<>();
            Map<String, List<String>> mappings =
                LibraryMappings.cached(this.project, this.librariesRefreshed, this.monitor);
            mappings.forEach((prefix, packages) -> {
                String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
                this.libraries.put(lowerPrefix, packages);
            });
        }

        return this.libraries;
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        return values.stream().anyMatch(value::equalsIgnoreCase);
    }

    private static boolean endsWithAny(String packageName, List<String> suffixes) {
        String lower = packageName.toLowerCase(Locale.ROOT);
        for (String suffix : suffixes) {
            String lowerSuffix = suffix.toLowerCase(Locale.ROOT);
            if (lower.equals(lowerSuffix) || lower.endsWith("." + lowerSuffix)) {
                return true;
            }
        }
        return false;
    }

    private static String join(String base, List<String> path) {
        if (path.isEmpty()) {
            return base;
        }

        return base + "." + String.join(".", path);
    }
}
