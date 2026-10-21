package net.netzgut.eclipse.tapestry.internal.refactoring;

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.IType;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext;
import org.eclipse.ltk.core.refactoring.participants.RenameParticipant;
import org.eclipse.ltk.core.refactoring.resource.RenameResourceChange;

import net.netzgut.eclipse.tapestry.internal.core.RelatedFiles;
import net.netzgut.eclipse.tapestry.internal.core.TapestryConventions;

/// Takes part in JDT's "Rename Type" (also triggered by renaming the .java file): renames
/// `Foo.tml`, `Foo.properties`, `Foo_de.properties`, `Foo.js`, … along with class `Foo`.
public class RenameRelatedFilesParticipant extends RenameParticipant {

    private IType       type;
    private List<IFile> files;

    /// Called by LTK for every type being renamed; returning `false` keeps us out of
    /// the refactoring.
    @Override
    protected boolean initialize(Object element) {
        if (!(element instanceof IType candidate) || candidate.getDeclaringType() != null) {
            return false;
        }

        try {
            this.type = candidate;
            this.files = RelatedFiles.find(this.type, RelatedFiles::isCompanion);
            return !this.files.isEmpty();
        }
        catch (CoreException _) {
            return false;
        }
    }

    @Override
    public String getName() {
        return "Rename Tapestry templates and resources";
    }

    @Override
    public RefactoringStatus checkConditions(IProgressMonitor pm,
                                             CheckConditionsContext context) throws OperationCanceledException {
        RefactoringStatus status = new RefactoringStatus();
        for (IFile file : this.files) {
            String newName = newName(file);
            if (newName != null && file.getParent().findMember(newName) != null) {
                status.addError("Cannot rename " + file.getFullPath() + ": " + newName + " already exists");
            }
        }

        return status;
    }

    @Override
    public Change createChange(IProgressMonitor pm) throws CoreException, OperationCanceledException {
        CompositeChange change = new CompositeChange(getName());
        for (IFile file : this.files) {
            String newName = newName(file);
            if (newName != null) {
                change.add(new RenameResourceChange(file.getFullPath(), newName));
            }
        }

        if (change.getChildren().length > 0) {
            return change;
        }

        return null;
    }

    /// Handles both `Foo_de.tml` and qualified override names like `com.example.Foo.tml`.
    private String newName(IFile file) {
        String newSimpleName = getArguments().getNewName();
        String packageName = this.type.getPackageFragment().getElementName();
        String newQualifiedName;
        if (packageName.isEmpty()) {
            newQualifiedName = newSimpleName;
        }
        else {
            newQualifiedName = packageName + "." + newSimpleName;
        }

        String fileName = file.getName();
        String renamed = TapestryConventions.renameFile(fileName, this.type.getFullyQualifiedName(), newQualifiedName);
        if (renamed != null) {
            return renamed;
        }

        return TapestryConventions.renameFile(fileName, this.type.getElementName(), newSimpleName);
    }
}
