package net.netzgut.eclipse.tapestry.internal.refactoring;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.resource.MoveResourceChange;

import net.netzgut.eclipse.tapestry.internal.core.RelatedFiles;

/// Moves the resources of a Java package that live in other source folders (typically
/// `src/main/resources/<package>`) along with a package rename or move.
///
/// Only files directly in the package folder are moved, plus sub folders that are not Java packages
/// themselves (e.g. `pages/images`). Sub packages are handled by JDT calling participants for them
/// separately, if at all.
final class PackageResources {

    record Move(IResource resource, IContainer oldFolder, IFolder newFolder) {
    }

    /// Computes where the package folder of a source folder ends up.
    @FunctionalInterface
    interface Target {

        IFolder newFolder(IContainer sourceFolder) throws CoreException;
    }

    /// Moves for the package's resources in all source folders except its own (JDT handles
    /// that one).
    static List<Move> collect(IPackageFragment fragment, Target target) throws CoreException {
        IPackageFragmentRoot javaRoot = (IPackageFragmentRoot) fragment.getParent();
        IPath packagePath = path(fragment.getElementName());
        List<Move> moves = new ArrayList<>();
        for (IContainer root : RelatedFiles.sourceFolders(fragment.getJavaProject())) {
            if (root.equals(javaRoot.getResource())
                || !(root.findMember(packagePath) instanceof IContainer oldFolder)) {
                continue;
            }

            IFolder newFolder = target.newFolder(root);
            if (newFolder.equals(oldFolder)) {
                continue;
            }

            for (IResource member : oldFolder.members()) {
                boolean subPackage = false;
                if (member.getType() == IResource.FOLDER) {
                    String subPackageName = fragment.getElementName() + "." + member.getName();
                    subPackage = javaRoot.getPackageFragment(subPackageName).exists();
                }

                // Java files need JDT's package declaration updates; they are never moved as plain files
                boolean javaFile = member.getType() == IResource.FILE && JavaCore.isJavaLikeFileName(member.getName());
                if (!subPackage && !javaFile) {
                    moves.add(new Move(member, oldFolder, newFolder));
                }
            }
        }

        return moves;
    }

    static RefactoringStatus check(List<Move> moves) {
        RefactoringStatus status = new RefactoringStatus();
        for (Move move : moves) {
            String name = move.resource().getName();
            if (move.newFolder().findMember(name) != null) {
                status.addError("Cannot move "
                                + move.resource().getFullPath()
                                + ": "
                                + move.newFolder().getFullPath().append(name)
                                + " already exists");
            }
        }

        return status;
    }

    /// Creates missing target folders, moves, then removes source folders that ended up empty.
    static void addChanges(List<Move> moves, CompositeChange change) throws CoreException {
        List<IFolder> created = new ArrayList<>();
        List<IContainer> emptied = new ArrayList<>();
        List<Change> cleanups = new ArrayList<>();
        for (Move move : moves) {
            if (!move.newFolder().exists() && !created.contains(move.newFolder())) {
                created.add(move.newFolder());
                change.add(new CreateFolderChange(move.newFolder()));
            }
            change.add(new MoveResourceChange(move.resource(), move.newFolder()));

            if (!emptied.contains(move.oldFolder())) {
                emptied.add(move.oldFolder());
                IContainer sourceFolder = RelatedFiles.sourceFolderOf(move.oldFolder());
                cleanups.add(new DeleteEmptyFoldersChange(move.oldFolder(), sourceFolder));
            }
        }

        cleanups.forEach(change::add);
    }

    /// The source folder in `destination`'s project that corresponds to `sourceFolder`: the same
    /// project-relative path (src/main/resources → src/main/resources) if that is a source folder
    /// there, otherwise `destination` itself. Within the same project nothing changes.
    static IContainer correspondingSourceFolder(IContainer sourceFolder,
                                                IPackageFragmentRoot destination) throws CoreException {
        IContainer destinationFolder = (IContainer) destination.getResource();
        if (destinationFolder.getProject().equals(sourceFolder.getProject())) {
            return sourceFolder;
        }

        IPath projectRelativePath = sourceFolder.getProjectRelativePath();
        IResource sameLayout = destinationFolder.getProject().findMember(projectRelativePath);
        if (sameLayout instanceof IContainer container) {
            List<IContainer> destinationSourceFolders = RelatedFiles.sourceFolders(destination.getJavaProject());
            if (destinationSourceFolders.contains(container)) {
                return container;
            }
        }

        return destinationFolder;
    }

    static IPath path(String packageName) {
        String portablePath = packageName.replace('.', '/');
        return IPath.fromPortableString(portablePath);
    }

    private PackageResources() {
    }
}
