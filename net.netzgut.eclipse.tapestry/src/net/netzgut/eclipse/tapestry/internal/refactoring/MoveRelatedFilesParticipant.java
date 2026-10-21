package net.netzgut.eclipse.tapestry.internal.refactoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext;
import org.eclipse.ltk.core.refactoring.participants.MoveParticipant;
import org.eclipse.ltk.core.refactoring.resource.MoveResourceChange;
import org.eclipse.ltk.core.refactoring.resource.RenameResourceChange;

import net.netzgut.eclipse.tapestry.internal.core.RelatedFiles;
import net.netzgut.eclipse.tapestry.internal.core.TapestryConventions;

/// Takes part in JDT's "Move" of a class to another package: moves its related files to the same
/// package in their own source folder, e.g. `src/main/resources/com/a/Foo.tml` →
/// `src/main/resources/com/b/Foo.tml`. Override files stay where they are; those named by qualified
/// name are renamed to the new qualified name.
public class MoveRelatedFilesParticipant extends MoveParticipant {

    private IType            type;
    private IPackageFragment destination;
    private List<IFile>      files;

    @Override
    protected boolean initialize(Object element) {
        if (!(element instanceof IType candidate)
            || candidate.getDeclaringType() != null
            || !(getArguments().getDestination() instanceof IPackageFragment target)
            || target.equals(candidate.getPackageFragment())) {
            return false;
        }

        try {
            this.type = candidate;
            this.destination = target;
            this.files = RelatedFiles.find(this.type, RelatedFiles::isCompanion);
            return !this.files.isEmpty();
        }
        catch (CoreException e) {
            return false;
        }
    }

    @Override
    public String getName() {
        return "Move Tapestry templates and resources";
    }

    @Override
    public RefactoringStatus checkConditions(IProgressMonitor pm,
                                             CheckConditionsContext context) throws OperationCanceledException {
        RefactoringStatus status = new RefactoringStatus();
        try {
            for (IFile file : this.files) {
                IPath target = targetPath(file);
                if (target != null && file.getWorkspace().getRoot().findMember(target) != null) {
                    status.addError("Cannot move " + file.getFullPath() + ": " + target + " already exists");
                }
            }
        }
        catch (CoreException e) {
            status.addFatalError(e.getMessage());
        }

        return status;
    }

    @Override
    public Change createChange(IProgressMonitor pm) throws CoreException, OperationCanceledException {
        // Folders must exist before files can be moved into them, so collect them first
        Map<IFolder, CreateFolderChange> folders = new LinkedHashMap<>();
        CompositeChange moves = new CompositeChange("Move files");
        CompositeChange renames = new CompositeChange("Rename override files");

        for (IFile file : this.files) {
            IPath target = targetPath(file);
            if (target == null) {
                continue;
            }

            IPath targetFolder = target.removeLastSegments(1);
            IPath currentFolder = file.getParent().getFullPath();
            if (targetFolder.equals(currentFolder)) {
                renames.add(new RenameResourceChange(file.getFullPath(), target.lastSegment()));
            }
            else {
                IContainer folder = folder(targetFolder);
                if (folder instanceof IFolder newFolder && !newFolder.exists()) {
                    folders.computeIfAbsent(newFolder, CreateFolderChange::new);
                }
                moves.add(new MoveResourceChange(file, folder));
            }
        }

        CompositeChange change = new CompositeChange(getName());
        folders.values().forEach(change::add);
        if (moves.getChildren().length > 0) {
            change.add(moves);
        }

        if (renames.getChildren().length > 0) {
            change.add(renames);
        }

        if (change.getChildren().length > 0) {
            return change;
        }

        return null;
    }

    /// Where `file` ends up, or `null` if it stays untouched.
    private IPath targetPath(IFile file) throws CoreException {
        IContainer root = RelatedFiles.sourceFolderOf(file);
        if (root == null) {
            return null;
        }

        if (RelatedFiles.isInOverrideFolder(root, file)) {
            String newQualifiedName;
            if (this.destination.getElementName().isEmpty()) {
                newQualifiedName = this.type.getElementName();
            }
            else {
                newQualifiedName = this.destination.getElementName() + "." + this.type.getElementName();
            }

            String newName =
                TapestryConventions.renameFile(file.getName(), this.type.getFullyQualifiedName(), newQualifiedName);
            if (newName == null) {
                return null;
            }

            return file.getParent().getFullPath().append(newName);
        }

        IPackageFragmentRoot destinationRoot = (IPackageFragmentRoot) this.destination.getParent();
        IContainer targetSourceFolder = PackageResources.correspondingSourceFolder(root, destinationRoot);
        String packageFolder = this.destination.getElementName().replace('.', '/');
        return targetSourceFolder.getFullPath() //
                                 .append(packageFolder) //
                                 .append(file.getName());
    }

    private static IContainer folder(IPath path) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        if (path.segmentCount() == 1) {
            return root.getProject(path.lastSegment());
        }

        return root.getFolder(path);
    }
}
