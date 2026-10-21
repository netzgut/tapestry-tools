package net.netzgut.eclipse.tapestry.internal.refactoring;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext;
import org.eclipse.ltk.core.refactoring.participants.RenameParticipant;
import org.eclipse.ltk.core.refactoring.resource.RenameResourceChange;

import net.netzgut.eclipse.tapestry.internal.core.RelatedFiles;
import net.netzgut.eclipse.tapestry.internal.core.TapestryConventions;

/// Takes part in JDT's "Rename Package": JDT only renames the package in the source folder it was
/// selected in, so `src/main/resources/com/example/pages` would stay behind when renaming
/// `com.example.pages`. With "Rename subpackages", JDT calls us once per sub package, each with its
/// own new name.
public class RenamePackageParticipant extends RenameParticipant {

    private IPackageFragment            fragment;
    private List<PackageResources.Move> moves;
    private List<FileRename>            overrideRenames;

    private record FileRename(IFile file, //
                              String newName) {
    }

    @Override
    protected boolean initialize(Object element) {
        if (!(element instanceof IPackageFragment candidate)
            || candidate.isDefaultPackage()
            || !(candidate.getParent() instanceof IPackageFragmentRoot root)) {
            return false;
        }

        try {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
                return false;
            }

            this.fragment = candidate;
            String newPackageName = getArguments().getNewName();
            IPath newPath = PackageResources.path(newPackageName);
            this.moves = PackageResources.collect(this.fragment, sourceFolder -> sourceFolder.getFolder(newPath));
            this.overrideRenames = collectOverrideRenames();
            return !this.moves.isEmpty() || !this.overrideRenames.isEmpty();
        }
        catch (CoreException _) {
            return false;
        }
    }

    /// `overrides/com.example.pages.Index.tml` -> `overrides/com.example.views.Index.tml`
    private List<FileRename> collectOverrideRenames() throws CoreException {
        List<FileRename> result = new ArrayList<>();
        for (ICompilationUnit unit : this.fragment.getCompilationUnits()) {
            IType type = unit.findPrimaryType();
            if (type == null) {
                continue;
            }

            String oldName = type.getFullyQualifiedName();
            String newName = getArguments().getNewName() + "." + type.getElementName();
            for (IFile file : RelatedFiles.find(type, RelatedFiles::isCompanion)) {
                IContainer root = RelatedFiles.sourceFolderOf(file);
                String renamed = TapestryConventions.renameFile(file.getName(), oldName, newName);
                if (root != null && RelatedFiles.isInOverrideFolder(root, file) && renamed != null) {
                    result.add(new FileRename(file, renamed));
                }
            }
        }

        return result;
    }

    @Override
    public String getName() {
        return "Rename Tapestry resource packages";
    }

    @Override
    public RefactoringStatus checkConditions(IProgressMonitor pm,
                                             CheckConditionsContext context) throws OperationCanceledException {
        RefactoringStatus status = PackageResources.check(this.moves);
        for (FileRename rename : this.overrideRenames) {
            if (rename.file().getParent().findMember(rename.newName()) != null) {
                status.addError("Cannot rename "
                                + rename.file().getFullPath()
                                + ": "
                                + rename.newName()
                                + " already exists");
            }
        }

        return status;
    }

    @Override
    public Change createChange(IProgressMonitor pm) throws CoreException, OperationCanceledException {
        CompositeChange change = new CompositeChange(getName());
        PackageResources.addChanges(this.moves, change);
        for (FileRename rename : this.overrideRenames) {
            change.add(new RenameResourceChange(rename.file().getFullPath(), rename.newName()));
        }

        return change;
    }
}
