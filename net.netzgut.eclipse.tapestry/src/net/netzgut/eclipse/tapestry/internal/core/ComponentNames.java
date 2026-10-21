package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;

/// All components, mixins or pages a template can use, with the names Tapestry knows them by. The
/// reverse of [ComponentResolver].
///
/// Classes come from `*.components[.sub]` (`mixins`, `pages`) packages that are either in the
/// workspace (the application and its sibling projects) or in a library known from a
/// [LibraryMapping][LibraryMappings]. Other jars are left out: their `components` packages are
/// rarely Tapestry's.
public final class ComponentNames {

    /// @param name the logical name, with the library prefix (`frpc2/Bar`) except for Tapestry's
    ///            own components, which templates use without (`Form` rather than `core/Form`)
    public record Name(String name, //
                       IType type) {
    }

    private static final String CORE = "core";

    /// Sorted by name
    public static List<Name> all(IJavaProject project,
                                 ComponentResolver.Kind kind,
                                 IProgressMonitor monitor) throws CoreException {

        Map<String, String> prefixesByPackage = new HashMap<>();
        Map<String, List<String>> libraryMappings = LibraryMappings.cached(project, false, monitor);
        libraryMappings.forEach((prefix,
                                 packages) -> packages.forEach(root -> prefixesByPackage.putIfAbsent(root, prefix)));

        List<Name> names = new ArrayList<>();
        TypeNameMatchRequestor requestor = new TypeNameMatchRequestor() {

            @Override
            public void acceptTypeNameMatch(TypeNameMatch match) {
                IType type = match.getType();
                String packageName = match.getPackageName();

                int folder = folderIndex(packageName, kind.folder);
                if (folder < 0 || type.getDeclaringType() != null || Flags.isAbstract(match.getModifiers())) {
                    return;
                }

                String root = packageName.substring(0, folder);
                String pathPrefix = prefixesByPackage.get(root);
                if (pathPrefix == null && !isSource(match.getPackageFragmentRoot())) {
                    return;
                }

                if (pathPrefix == null || pathPrefix.equals(CORE)) {
                    pathPrefix = "";
                }

                String relativeClassName = type.getFullyQualifiedName().substring(folder + kind.folder.length() + 2);
                boolean isPage = kind == ComponentResolver.Kind.PAGE;
                Set<String> logicalNames = TapestryConventions.logicalNames(isPage, pathPrefix, relativeClassName);
                String preferredName = preferred(logicalNames);
                names.add(new Name(preferredName, type));
            }
        };

        List<String> packagePatterns = List.of("*." + kind.folder, "*." + kind.folder + ".*");
        for (String packagePattern : packagePatterns) {
            char[] packageNamePattern = packagePattern.toCharArray();
            IJavaSearchScope scope = SearchEngine.createJavaSearchScope(new IJavaElement[] { project });
            new SearchEngine().searchAllTypeNames(packageNamePattern,
                                                  SearchPattern.R_PATTERN_MATCH,
                                                  null,
                                                  SearchPattern.R_PATTERN_MATCH,
                                                  IJavaSearchConstants.CLASS,
                                                  scope,
                                                  requestor,
                                                  IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                                                  monitor);
        }

        names.sort(Comparator.comparing(name -> name.name().toLowerCase(Locale.ROOT)));
        return names;
    }

    /// The shortest name in the class's own folder: `admin/Users` for `admin.AdminUsers`, `Product`
    /// for `ProductPage`, but `admin/Index` rather than its alias `admin`.
    static String preferred(Set<String> logicalNames) {
        String first = logicalNames.iterator().next();

        long depth = Strings.count(first, '/');

        return logicalNames.stream() //
                           .filter(name -> Strings.count(name, '/') == depth)
                           .min(Comparator.comparingInt(String::length)) //
                           .orElse(first);
    }

    /// Index of the dot before the `.folder` segment in a package name (`com.example.pages.admin`),
    /// or -1.
    static int folderIndex(String packageName, String folder) {
        int index = 0;
        for (String segment : packageName.split("\\.")) {
            if (segment.equals(folder) && index > 0) {
                return index - 1;
            }

            index += segment.length() + 1;
        }

        return -1;
    }

    private static boolean isSource(IPackageFragmentRoot root) {
        try {
            return root != null && root.getKind() == IPackageFragmentRoot.K_SOURCE;
        }
        catch (CoreException _) {
            return false;
        }
    }

    private ComponentNames() {
    }
}
