package net.netzgut.eclipse.tapestry.internal.ui;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.ui.JavaElementLabelProvider;
import org.eclipse.jdt.ui.JavaElementLabels;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.model.WorkbenchLabelProvider;

/// Labels for files, Java elements and template positions in "choose target" dialogs, qualified
/// enough to tell them apart.
class TargetLabelProvider extends LabelProvider {

    // Both providers own (and dispose) the images they hand out
    private final WorkbenchLabelProvider   files        = new WorkbenchLabelProvider();
    private final JavaElementLabelProvider javaElements = new JavaElementLabelProvider();

    @Override
    public String getText(Object element) {
        // TODO: switch pattern matching?
        if (element instanceof IFile file) {
            return file.getName() + " – " + file.getParent().getFullPath().makeRelative();
        }

        if (element instanceof FileLocation location) {
            return location.file().getName()
                   + ":"
                   + location.line()
                   + " – "
                   + location.file().getParent().getFullPath().makeRelative();
        }

        if (element instanceof IType type) {
            return JavaElementLabels.getElementLabel(type,
                                                     JavaElementLabels.T_FULLY_QUALIFIED
                                                           | JavaElementLabels.APPEND_ROOT_PATH);
        }

        if (element instanceof IJavaElement member) {
            return JavaElementLabels.getElementLabel(member,
                                                     JavaElementLabels.M_PARAMETER_TYPES
                                                             | JavaElementLabels.M_APP_RETURNTYPE
                                                             | JavaElementLabels.F_APP_TYPE_SIGNATURE
                                                             | JavaElementLabels.ALL_POST_QUALIFIED);
        }

        return super.getText(element);
    }

    @Override
    public Image getImage(Object element) {
        // TODO: switch pattern matching?
        if (element instanceof IFile) {
            return this.files.getImage(element);
        }
        if (element instanceof FileLocation location) {
            return this.files.getImage(location.file());
        }
        if (element instanceof IJavaElement) {
            return this.javaElements.getImage(element);
        }
        return null;
    }

    @Override
    public void dispose() {
        this.files.dispose();
        this.javaElements.dispose();
        super.dispose();
    }
}
