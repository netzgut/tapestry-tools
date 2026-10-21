package net.netzgut.eclipse.tapestry.internal.refactoring;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.NullChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.resource.DeleteResourceChange;
import org.eclipse.ltk.core.refactoring.resource.ResourceChange;

/// Creates a folder including missing parents. LTK has no public change for that. Every
/// [Change] returns its own undo from [#perform]; here that is deleting the topmost folder
/// we created.
class CreateFolderChange extends ResourceChange {

    private final IFolder folder;

    CreateFolderChange(IFolder folder) {
        this.folder = folder;
    }

    @Override
    protected IResource getModifiedResource() {
        return this.folder;
    }

    @Override
    public String getName() {
        return "Create folder " + this.folder.getFullPath().makeRelative();
    }

    @Override
    public RefactoringStatus isValid(IProgressMonitor pm) {
        // The default implementation expects the resource to exist already
        return new RefactoringStatus();
    }

    @Override
    public Change perform(IProgressMonitor pm) throws CoreException {
        IFolder topmostCreated = null;
        IContainer current = this.folder;
        while (current instanceof IFolder missing && !missing.exists()) {
            topmostCreated = missing;
            current = missing.getParent();
        }

        if (topmostCreated == null) {
            return new NullChange(getName()); // null would make the whole refactoring impossible to undo
        }

        create(this.folder, pm);

        return new DeleteResourceChange(topmostCreated.getFullPath(), true);
    }

    private static void create(IFolder folder, IProgressMonitor pm) throws CoreException {
        if (folder.getParent() instanceof IFolder parent && !parent.exists()) {
            create(parent, pm);
        }

        if (!folder.exists()) {
            folder.create(false, true, pm);
        }
    }
}
