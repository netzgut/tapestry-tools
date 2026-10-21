package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.Signature;

/// Finds the files belonging to a Tapestry class purely by naming convention:
///
/// - `com/example/pages/Index.*` (plus locale variants like `Index_de.*`) in any source folder of
///   the class's project, e.g. `src/main/resources/com/example/pages/Index.tml`
/// - `.../overrides/Index.*` or `.../overrides/com.example.pages.Index.*` in any open Java project
///   (folder names configurable, see [TapestryPreferences#overrideFolders()])
///
/// No knowledge of the Tapestry registry, app package or modules is required.
public final class RelatedFiles {

    /// Templates, messages, scripts, stylesheets… — everything named like the class except Java
    /// files and [ignored extensions][TapestryPreferences#ignoredExtensions()].
    public static boolean isCompanion(IFile file) {
        String extension = file.getFileExtension();
        return !TapestryConventions.JAVA_EXTENSIONS.contains(extension)
               && !TapestryPreferences.ignoredExtensions().contains(extension);
    }

    public static boolean isTemplate(IFile file) {
        return TapestryConventions.TEMPLATE_EXTENSION.equals(file.getFileExtension());
    }

    public static List<IFile> find(IType type, Predicate<IFile> filter) throws CoreException {
        IType topLevel = topLevel(type);
        String packageName = topLevel.getPackageFragment().getElementName();
        String simpleName = topLevel.getElementName();
        String qualifiedName = topLevel.getFullyQualifiedName();

        Set<IFile> files = new LinkedHashSet<>();

        // For binary types this is the project whose classpath contains the jar, which is exactly where
        // a same-package file would override the one in the jar.
        IJavaProject javaProject = topLevel.getJavaProject();
        if (javaProject != null && javaProject.exists()) {
            String packagePath = packageName.replace('.', '/');
            for (IContainer root : sourceFolders(javaProject)) {
                IResource folder;
                if (packageName.isEmpty()) {
                    folder = root;
                }
                else {
                    folder = root.findMember(packagePath);
                }

                if (folder instanceof IContainer container) {
                    addMatching(container, topLevel, simpleName, filter, files);
                }
            }
        }

        for (IContainer overrides : OverrideFolders.all()) {
            addMatching(overrides, topLevel, simpleName, filter, files);
            addMatching(overrides, topLevel, qualifiedName, filter, files);
        }

        return new ArrayList<>(files);
    }

    public static List<IContainer> sourceFolders(IJavaProject javaProject) throws CoreException {
        List<IContainer> result = new ArrayList<>();
        for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
            // Resource folders (src/main/resources) are source entries too, often with excluding="**".
            // We only use the folder itself, so exclusion filters don't matter.
            if (root.getKind() == IPackageFragmentRoot.K_SOURCE && root.getResource() instanceof IContainer folder) {
                result.add(folder);
            }
        }

        return result;
    }

    /// The innermost source folder containing `resource`, or `null`.
    public static IContainer sourceFolderOf(IResource resource) throws CoreException {
        IJavaProject javaProject = JavaCore.create(resource.getProject());
        if (!javaProject.exists()) {
            return null;
        }

        IContainer best = null;
        for (IContainer root : sourceFolders(javaProject)) {
            IPath rootPath = root.getFullPath();
            if (rootPath.isPrefixOf(resource.getFullPath())
                && (best == null || rootPath.segmentCount() > best.getFullPath().segmentCount())) {
                best = root;
            }
        }

        return best;
    }

    /// True if any folder between `root` and `file` is an override folder.
    public static boolean isInOverrideFolder(IContainer root, IFile file) {
        List<String> overrideFolders = TapestryPreferences.overrideFolders();
        IPath parentPath = file.getParent().getFullPath();
        IPath relativePath = parentPath.makeRelativeTo(root.getFullPath());
        for (String folder : relativePath.segments()) {
            if (overrideFolders.contains(folder)) {
                return true;
            }
        }
        return false;
    }

    private static void addMatching(IContainer folder,
                                    IType type,
                                    String className,
                                    Predicate<IFile> filter,
                                    Set<IFile> files) throws CoreException {
        for (IResource member : folder.members()) {
            if (member instanceof IFile file && filter.test(file)) {
                String baseName = file.getFullPath().removeFileExtension().lastSegment();
                if (TapestryConventions.belongsTo(baseName, className)
                    && !ownedByOtherClass(type, baseName, className)) {
                    files.add(file);
                }
            }
        }
    }

    /// `Index_Print.tml` looks like a variant of `Index`, but belongs to class `Index_Print` if
    /// that exists next to `Index`.
    private static boolean ownedByOtherClass(IType type, String baseName, String className) {
        IPackageFragment fragment = type.getPackageFragment();
        for (String candidate : TapestryConventions.candidateClassNames(baseName)) {
            if (candidate.length() <= className.length()) {
                return false;
            }
            String simpleName = Signature.getSimpleName(candidate);
            if (fragment.getCompilationUnit(simpleName + ".java").exists()
                || fragment.getOrdinaryClassFile(simpleName + ".class").exists()) {
                return true;
            }
        }
        return false;
    }

    static IType topLevel(IType type) {
        IType current = type;
        while (current.getDeclaringType() != null) {
            current = current.getDeclaringType();
        }
        return current;
    }

    private RelatedFiles() {
    }
}
