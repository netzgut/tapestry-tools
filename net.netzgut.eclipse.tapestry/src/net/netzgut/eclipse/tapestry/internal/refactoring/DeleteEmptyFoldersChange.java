package net.netzgut.eclipse.tapestry.internal.refactoring;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.NullChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.resource.ResourceChange;

/// Deletes `folder` and then its parents, as long as they are empty, stopping below `stopAt`.
/// Whether a folder is empty is decided when the change runs, so folders still holding files of
/// other packages survive.
class DeleteEmptyFoldersChange extends ResourceChange {

    private final IContainer folder;
    private final IContainer stopAt;

    DeleteEmptyFoldersChange(IContainer folder, IContainer stopAt) {
        this.folder = folder;
        this.stopAt = stopAt;
    }

    @Override
    protected IResource getModifiedResource() {
        return this.folder;
    }

    @Override
    public String getName() {
        return "Delete empty folder " + this.folder.getFullPath().makeRelative();
    }

    @Override
    public RefactoringStatus isValid(IProgressMonitor pm) {
        // The folder may already be gone when another change cleaned up first
        return new RefactoringStatus();
    }

    @Override
    public Change perform(IProgressMonitor pm) throws CoreException {
        IFolder deepestDeleted = null;
        IContainer current = this.folder;
        while (current instanceof IFolder candidate
               && !candidate.equals(this.stopAt)
               && candidate.exists()
               && candidate.members().length == 0) {

            if (deepestDeleted == null) {
                deepestDeleted = candidate;
            }

            IContainer parent = candidate.getParent();
            candidate.delete(false, true, pm);

            current = parent;
        }

        // Recreating the deepest folder recreates its deleted parents as well. Never return null: a null undo makes
        // the whole refactoring impossible to undo.
        if (deepestDeleted == null) {
            return new NullChange(getName());
        }

        return new CreateFolderChange(deepestDeleted);
    }
}
