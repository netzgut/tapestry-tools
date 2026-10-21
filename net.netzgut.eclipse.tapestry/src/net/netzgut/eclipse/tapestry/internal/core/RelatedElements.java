package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IType;

/// Everything belonging to the same page/component as a class or file: the class itself plus its
/// templates, message catalogs, scripts, stylesheets and overrides. Used by the quick switch popup.
public final class RelatedElements {

    /// Display order by extension; unknown extensions come last.
    private static final List<String> EXTENSION_ORDER = List.of("tml", //
                                                                "properties",
                                                                "js",
                                                                "ts",
                                                                "coffee",
                                                                "css",
                                                                "scss",
                                                                "less");

    private RelatedElements() {
    }

    /// @param source an [IType] or [IFile]
    /// @return related classes ([IType]) and files ([IFile]) without `source`, classes first, then
    ///         by kind (templates, messages, scripts, styles), plain names before suffixed ones,
    ///         overrides last
    public static List<Object> collect(Object source, //
                                       IProgressMonitor monitor) throws CoreException {
        List<IType> classes;
        if (source instanceof IType type) {
            IType topLevel = RelatedFiles.topLevel(type);
            classes = List.of(topLevel);
        }
        else {
            classes = TemplateLocator.findClasses((IFile) source, monitor);
        }

        Set<IType> types = new LinkedHashSet<>(classes);
        Set<IFile> files = new LinkedHashSet<>();
        for (IType type : classes) {
            List<IFile> companions = RelatedFiles.find(type, RelatedFiles::isCompanion);
            files.addAll(companions);
        }

        if (source instanceof IType type) {
            IType topLevel = RelatedFiles.topLevel(type);
            types.remove(topLevel);
        }

        files.remove(source);

        List<FileEntry> sortedFiles = new ArrayList<>();
        for (IFile file : files) {
            boolean override = isOverride(file);
            sortedFiles.add(new FileEntry(file, override));
        }
        sortedFiles.sort(FileEntry.ORDER);

        List<Object> result = new ArrayList<>(types);
        sortedFiles.forEach(entry -> result.add(entry.file()));

        return result;
    }

    private static boolean isOverride(IFile file) throws CoreException {
        IContainer root = RelatedFiles.sourceFolderOf(file);
        return root != null && RelatedFiles.isInOverrideFolder(root, file);
    }

    private record FileEntry(IFile file, //
                             boolean override) {

        static final Comparator<FileEntry> ORDER =
            Comparator.comparing(FileEntry::override) //
                      .thenComparingInt(FileEntry::extensionRank)
                      // "Index.tml" < "Index_B5.tml" < "Index_B5_de.tml" < "Index_de.tml": plain name first, then suffixes
                      .thenComparing(entry -> entry.file().getName().toLowerCase(Locale.ROOT));

        int extensionRank() {
            String extension = this.file.getFileExtension();
            if (extension == null) {
                return EXTENSION_ORDER.size();
            }

            String lowerCaseExtension = extension.toLowerCase(Locale.ROOT);
            int rank = EXTENSION_ORDER.indexOf(lowerCaseExtension);
            if (rank < 0) {
                return EXTENSION_ORDER.size();
            }

            return rank;
        }
    }
}
