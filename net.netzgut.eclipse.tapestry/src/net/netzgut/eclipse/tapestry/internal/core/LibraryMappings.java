package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;

/// Library prefixes (`frpc2/Bar`) and their root packages, read from `new LibraryMapping(...)`
/// calls in the sources visible to a project. Without the registry we can only read what is written
/// literally:
///
/// - `new LibraryMapping("frpc2", "com.example.commons")`
/// - `new LibraryMapping("frpc2", CommonsModule.class.getPackage().getName())` or `getPackageName()`
/// - String constants for either argument
///
/// Anything else (local variables, method calls) is skipped; [ComponentResolver] falls back
/// to conventions.
public final class LibraryMappings {

    static final String LIBRARY_MAPPING = "org.apache.tapestry5.services.LibraryMapping";

    private static final String  STRING        = "\"(?:[^\"\\\\]|\\\\.)*\"";
    private static final Pattern CALL          =
        Pattern.compile("(?:new\\s+)?(?:[\\w.]+\\.)?LibraryMapping\\s*\\(\\s*("
                        + STRING
                        + "|[\\w.]+)\\s*,\\s*("
                        + STRING
                        + "|[\\w.]+\\.class\\s*\\.\\s*getPackage(?:Name)?\\s*\\(\\s*\\)|[\\w.]+)");
    private static final Pattern CLASS_PACKAGE =
        Pattern.compile("([\\w.]+)\\.class\\s*\\.\\s*getPackage(?:Name)?.*", Pattern.DOTALL);

    /// Mappings rarely change; re-read them at least this often in case one was added.
    private static final long MAX_AGE_MILLIS = 5 * 60 * 1000;

    private record Cached(Map<String, List<String>> mappings, //
                          long readAt) {

        Cached {
            mappings = Map.copyOf(mappings);
        }

        Cached(Map<String, List<String>> mappings) {
            this(mappings, System.currentTimeMillis());
        }
    }

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    /// Like [#find], but cached per project for a few minutes. Pass `refresh` when a cached mapping
    /// led nowhere, e.g. because the library package was renamed.
    public static Map<String, List<String>> cached(IJavaProject project,
                                                   boolean refresh,
                                                   IProgressMonitor monitor) throws CoreException {
        String key = project.getElementName();
        Cached cached = CACHE.get(key);
        if (refresh || cached == null || System.currentTimeMillis() - cached.readAt() > MAX_AGE_MILLIS) {
            Map<String, List<String>> mappings = find(project, monitor);
            cached = new Cached(mappings);
            CACHE.put(key, cached);
        }
        return cached.mappings();
    }

    /// Prefix -> root packages, including Tapestry's own `core` library.
    public static Map<String, List<String>> find(IJavaProject project, //
                                                 IProgressMonitor monitor) throws CoreException {
        Map<String, List<String>> mappings = new LinkedHashMap<>();
        mappings.put("core", new ArrayList<>(List.of("org.apache.tapestry5.corelib")));

        IType libraryMapping = project.findType(LIBRARY_MAPPING);
        if (libraryMapping == null) {
            return mappings;
        }

        SearchPattern pattern = null;
        for (IMethod method : libraryMapping.getMethods()) {
            if (method.isConstructor()) {
                SearchPattern references = SearchPattern.createPattern(method, IJavaSearchConstants.REFERENCES);
                if (pattern == null) {
                    pattern = references;
                }
                else {
                    pattern = SearchPattern.createOrPattern(pattern, references);
                }
            }
        }
        if (pattern == null) {
            return mappings;
        }

        SearchParticipant defaultParticipant = SearchEngine.getDefaultSearchParticipant();
        SearchParticipant[] participants = new SearchParticipant[] { defaultParticipant };
        IJavaSearchScope scope = SearchEngine.createJavaSearchScope(new IJavaElement[] { project });
        SearchRequestor requestor = new SearchRequestor() {

            @Override
            public void acceptSearchMatch(SearchMatch match) throws CoreException {
                if (match.getElement() instanceof IJavaElement element) {
                    read(project, element, match.getOffset(), mappings);
                }
            }
        };
        new SearchEngine().search(pattern, participants, scope, requestor, monitor);
        return mappings;
    }

    private static void read(IJavaProject project,
                             IJavaElement element,
                             int offset,
                             Map<String, List<String>> mappings) throws CoreException {
        ITypeRoot root = (ITypeRoot) element.getAncestor(IJavaElement.COMPILATION_UNIT);
        if (root == null) {
            root = (ITypeRoot) element.getAncestor(IJavaElement.CLASS_FILE); // attached sources
        }

        IType context = (IType) element.getAncestor(IJavaElement.TYPE);
        String source = null;
        if (root != null) {
            source = root.getSource();
        }

        if (source == null || context == null || offset < 0 || offset >= source.length()) {
            return;
        }

        Matcher call = CALL.matcher(source);
        int regionEnd = Math.min(source.length(), offset + 500);
        call.region(offset, regionEnd);
        if (!call.lookingAt()) {
            return;
        }

        String prefix = value(project, context, call.group(1));
        String packageName = value(project, context, call.group(2));
        if (prefix != null && packageName != null) {
            List<String> packages = mappings.computeIfAbsent(prefix, _ -> new ArrayList<>());
            if (!packages.contains(packageName)) {
                packages.add(packageName);
            }
        }
    }

    private static String value(IJavaProject project, IType context, String expression) throws CoreException {
        if (expression.startsWith("\"")) {
            return Strings.unquote(expression);
        }

        Matcher classPackage = CLASS_PACKAGE.matcher(expression);
        if (classPackage.matches()) {
            IType type = JavaSources.resolveType(project, context, classPackage.group(1));
            if (type == null) {
                return null;
            }
            return type.getPackageFragment().getElementName();
        }

        return JavaSources.stringConstant(project, context, expression);
    }

    private LibraryMappings() {
    }
}
