package net.netzgut.eclipse.tapestry.internal.core;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;

/// A template or message catalog for a class that doesn't have one yet.
///
/// It goes next to the class in the resources folder: `src/main/java/com/example/pages/Index.java`
/// gets `src/main/resources/com/example/pages/Index.tml`. Without a matching resources folder, it
/// goes next to the class itself, which Tapestry finds as well.
public final class NewResource {

    public enum Kind {

        /// Pages and components.
        TEMPLATE(TapestryConventions.TEMPLATE_EXTENSION, List.of("pages", "components"), false),

        /// Also mixins and base classes, whose catalogs Tapestry merges into those of
        /// their subclasses.
        MESSAGES("properties", List.of("pages", "components", "mixins", "base"), true);

        public final String        extension;
        private final List<String> packages;
        private final boolean      allowAbstract;

        Kind(String extension, List<String> packages, boolean allowAbstract) {
            this.extension = extension;
            this.packages = packages;
            this.allowAbstract = allowAbstract;
        }
    }

    static final String NAMESPACE = "http://tapestry.apache.org/schema/tapestry_5_4.xsd";

    /// Whether `type` gets a file of this kind: a class from source in one of the kind's packages,
    /// not a library class.
    public static boolean isApplicable(IType type, Kind kind) throws CoreException {
        IType topLevel = RelatedFiles.topLevel(type);
        if (topLevel.isBinary()
            || !topLevel.isClass()
            || (!kind.allowAbstract && Flags.isAbstract(topLevel.getFlags()))) {
            return false;
        }

        List<String> segments = packageSegments(topLevel);
        return kind.packages.stream().anyMatch(segments::contains);
    }

    /// The kinds `type` could have but has no plain file of yet (`Index.tml`; locale variants like
    /// `Index_de.tml` don't count), in [Kind] order.
    public static List<Kind> missing(IType type) throws CoreException {
        IType topLevel = RelatedFiles.topLevel(type);
        List<IFile> related = null;
        List<Kind> missing = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            if (!isApplicable(topLevel, kind)) {
                continue;
            }

            if (related == null) {
                related = RelatedFiles.find(topLevel, RelatedFiles::isCompanion);
            }

            String simple = (topLevel.getElementName() + "." + kind.extension).toLowerCase(Locale.ROOT);
            String qualified = (topLevel.getFullyQualifiedName() + "." + kind.extension).toLowerCase(Locale.ROOT);
            boolean exists = related.stream() //
                                    .map(file -> file.getName().toLowerCase(Locale.ROOT)) //
                                    .anyMatch(name -> name.equals(simple) || name.equals(qualified));
            if (!exists && !location(topLevel, kind).exists()) {
                missing.add(kind);
            }
        }

        return missing;
    }

    /// Where the file of `type` should be created.
    public static IFile location(IType type, Kind kind) throws CoreException {
        IType topLevel = RelatedFiles.topLevel(type);
        IResource source = topLevel.getResource();
        IContainer sourceFolder = RelatedFiles.sourceFolderOf(source);
        IContainer root = resourcesFolder(sourceFolder);
        String packageName = topLevel.getPackageFragment().getElementName();
        String portablePackagePath = packageName.replace('.', '/');
        IPath packagePath = IPath.fromPortableString(portablePackagePath);
        String fileName = topLevel.getElementName() + "." + kind.extension;
        IPath filePath = packagePath.append(fileName);
        return root.getFile(filePath);
    }

    /// `src/main/resources` for `src/main/java`, if it is a source folder; otherwise
    /// `sourceFolder` itself.
    static IContainer resourcesFolder(IContainer sourceFolder) throws CoreException {
        if (sourceFolder.getName().equals("java")) {
            IResource sibling = sourceFolder.getParent().findMember("resources");
            if (sibling instanceof IContainer resources) {
                IJavaProject javaProject = JavaCore.create(sourceFolder.getProject());
                List<IContainer> sourceFolders = RelatedFiles.sourceFolders(javaProject);
                if (sourceFolders.contains(resources)) {
                    return resources;
                }
            }
        }

        return sourceFolder;
    }

    /// Pages start empty, as their root element depends on the app's layout; components get an
    /// empty `<t:container>` with the Tapestry namespaces.
    public static String templateContent(boolean page, String lineDelimiter) {
        if (page) {
            return "";
        }

        return "<t:container xmlns:t=\""
               + NAMESPACE
               + "\" xmlns:p=\"tapestry:parameter\">"
               + lineDelimiter
               + lineDelimiter
               + "</t:container>"
               + lineDelimiter;
    }

    /// Creates the file of `type`, including missing folders. Message catalogs start empty.
    public static IFile create(IType type, Kind kind, IProgressMonitor monitor) throws CoreException {
        SubMonitor progress = SubMonitor.convert(monitor, 2);
        IFile file = location(type, kind);

        IProgressMonitor foldersProgress = progress.split(1);
        createFolders(file.getParent(), foldersProgress);

        String content = switch (kind) {
            case TEMPLATE -> {
                IType topLevel = RelatedFiles.topLevel(type);
                boolean page = packageSegments(topLevel).contains("pages");
                String lineDelimiter = lineDelimiter(file);
                yield templateContent(page, lineDelimiter);
            }
            case MESSAGES -> "";
        };

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        IProgressMonitor createProgress = progress.split(1);
        file.create(new ByteArrayInputStream(bytes), IResource.NONE, createProgress);

        return file;
    }

    private static List<String> packageSegments(IType type) {
        String packageName = type.getPackageFragment().getElementName();
        String[] segments = packageName.split("\\.");
        return List.of(segments);
    }

    private static void createFolders(IContainer container, IProgressMonitor monitor) throws CoreException {
        if (container instanceof IFolder folder && !folder.exists()) {
            createFolders(folder.getParent(), monitor);
            folder.create(IResource.NONE, true, monitor);
        }
    }

    private static String lineDelimiter(IFile file) {
        IScopeContext[] scopes = { new ProjectScope(file.getProject()), InstanceScope.INSTANCE };
        return Platform.getPreferencesService()
                       .getString(Platform.PI_RUNTIME, Platform.PREF_LINE_SEPARATOR, System.lineSeparator(), scopes);
    }

    private NewResource() {
    }
}
