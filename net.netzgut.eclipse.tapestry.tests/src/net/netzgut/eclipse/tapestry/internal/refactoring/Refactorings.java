package net.netzgut.eclipse.tapestry.internal.refactoring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CheckConditionsOperation;
import org.eclipse.ltk.core.refactoring.PerformRefactoringOperation;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.RefactoringDescriptor;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;

import net.netzgut.eclipse.tapestry.internal.core.TestProject;

/// Runs JDT refactorings the way the UI does after the wizard's "Finish".
final class Refactorings {

    @SuppressWarnings("unchecked")
    static <T extends RefactoringDescriptor> T descriptor(TestProject project, String id) {
        T descriptor = (T) RefactoringCore.getRefactoringContribution(id).createDescriptor();
        descriptor.setProject(project.project.getName());
        return descriptor;
    }

    /// Performs the refactoring, failing on errors, and returns the undo change.
    static Change perform(RefactoringDescriptor descriptor) throws CoreException {
        RefactoringStatus status = new RefactoringStatus();
        Refactoring refactoring = descriptor.createRefactoring(status);
        assertTrue(status.isOK(), status::toString);

        PerformRefactoringOperation operation =
            new PerformRefactoringOperation(refactoring, CheckConditionsOperation.ALL_CONDITIONS);
        ResourcesPlugin.getWorkspace().run(operation, new NullProgressMonitor());
        RefactoringStatus conditions = operation.getConditionStatus();
        assertFalse(conditions != null && conditions.hasError(), () -> String.valueOf(conditions));
        return operation.getUndoChange();
    }

    private Refactorings() {
    }
}
