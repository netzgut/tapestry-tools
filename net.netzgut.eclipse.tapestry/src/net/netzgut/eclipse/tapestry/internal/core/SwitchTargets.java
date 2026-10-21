package net.netzgut.eclipse.tapestry.internal.core;

import java.util.List;

import org.eclipse.core.resources.IFile;

/// Decides which template to open when a class has several.
public final class SwitchTargets {

    /// Returns the template to open without asking, or `null` if the user has to choose. A single
    /// template is opened directly; otherwise the single one named exactly like the class wins over
    /// its locale/variant suffixed siblings (`Index.tml` over `Index_de.tml` and `Index_B5.tml`).
    public static IFile preferredTemplate(List<IFile> templates, String simpleName, String qualifiedName) {
        if (templates.size() == 1) {
            return templates.get(0);
        }

        List<IFile> exact = templates.stream() //
                                     .filter(file -> {
                                         String baseName = file.getFullPath().removeFileExtension().lastSegment();
                                         return baseName.equals(simpleName) || baseName.equals(qualifiedName);
                                     }) //
                                     .toList();
        if (exact.size() != 1) {
            return null;
        }
        return exact.get(0);
    }

    private SwitchTargets() {
    }
}
