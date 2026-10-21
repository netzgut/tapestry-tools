package net.netzgut.eclipse.tapestry.internal.refactoring;

import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext;
import org.eclipse.ltk.core.refactoring.participants.MoveParticipant;

/// Takes part in JDT's "Move" of a package to a source folder of another project: the package's
/// resources follow into the corresponding source folder there (`src/main/resources` →
/// `src/main/resources`). Moves within a project leave resources alone, since the package name, and
/// with it the classpath location, stays the same.
public class MovePackageParticipant extends MoveParticipant {

    private List<PackageResources.Move> moves;

    @Override
    protected boolean initialize(Object element) {
        if (!(element instanceof IPackageFragment fragment)
            || fragment.isDefaultPackage()
            || !(fragment.getParent() instanceof IPackageFragmentRoot root)
            || !(getArguments().getDestination() instanceof IPackageFragmentRoot destination)
            || destination.getJavaProject().equals(fragment.getJavaProject())) {
            return false;
        }

        try {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
                return false;
            }

            IPath packagePath = PackageResources.path(fragment.getElementName());
            this.moves = PackageResources.collect(fragment, sourceFolder -> {
                IContainer targetSourceFolder = PackageResources.correspondingSourceFolder(sourceFolder, destination);
                return targetSourceFolder.getFolder(packagePath);
            });

            return !this.moves.isEmpty();
        }
        catch (CoreException _) {
            return false;
        }
    }

    @Override
    public String getName() {
        return "Move Tapestry resource packages";
    }

    @Override
    public RefactoringStatus checkConditions(IProgressMonitor pm,
                                             CheckConditionsContext context) throws OperationCanceledException {
        return PackageResources.check(this.moves);
    }

    @Override
    public Change createChange(IProgressMonitor pm) throws CoreException, OperationCanceledException {
        CompositeChange change = new CompositeChange(getName());
        PackageResources.addChanges(this.moves, change);
        return change;
    }
}
