package net.netzgut.eclipse.tapestry.internal.ui;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.ui.JavaElementLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.model.WorkbenchLabelProvider;

/// `Index.properties shop/src/main/resources/com/example/pages` with the usual Eclipse icons; files
/// still to be created get an add icon.
class RelatedElementLabelProvider extends LabelProvider implements IStyledLabelProvider {

    // Both providers own (and dispose) the images they hand out
    private final WorkbenchLabelProvider   files = new WorkbenchLabelProvider();
    private final JavaElementLabelProvider types = new JavaElementLabelProvider();

    /// The file name the user sees and filters on.
    String name(Object element) {
        if (element instanceof NewResourceEntry entry) {
            return entry.file().getName();
        }

        if (element instanceof IFile file) {
            return file.getName();
        }

        if (element instanceof IType type) {
            ICompilationUnit unit = type.getCompilationUnit();
            if (unit != null) {
                return unit.getElementName();
            }

            return type.getElementName() + ".class";
        }

        return String.valueOf(element);
    }

    private static String location(Object element) {
        if (element instanceof IFile file) {
            return file.getParent().getFullPath().makeRelative().toString();
        }

        if (element instanceof IType type) {
            IPackageFragment packageFragment = type.getPackageFragment();
            IPackageFragmentRoot root = (IPackageFragmentRoot) packageFragment.getParent();
            String container;
            if (root.isArchive()) {
                container = root.getElementName();
            }
            else {
                container = root.getResource().getFullPath().makeRelative().toString();
            }

            return packageFragment.getElementName() + " - " + container;
        }

        return "";
    }

    @Override
    public String getText(Object element) {
        return getStyledText(element).getString();
    }

    @Override
    public StyledString getStyledText(Object element) {
        String name = name(element);
        if (element instanceof NewResourceEntry entry) {
            String location = location(entry.file());
            return new StyledString(name).append("  create in " + location, StyledString.QUALIFIER_STYLER);
        }

        String location = location(element);
        return new StyledString(name).append("  " + location, StyledString.QUALIFIER_STYLER);
    }

    @Override
    public Image getImage(Object element) {
        return switch (element) {
            case null -> null;
            case NewResourceEntry _ -> PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_OBJ_ADD);
            case IFile _ -> this.files.getImage(element);
            case IType _ -> this.types.getImage(element);
            default -> null;
        };
    }

    @Override
    public void dispose() {
        this.files.dispose();
        this.types.dispose();
        super.dispose();
    }
}
