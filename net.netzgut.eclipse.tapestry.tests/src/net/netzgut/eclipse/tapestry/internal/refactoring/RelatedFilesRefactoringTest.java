package net.netzgut.eclipse.tapestry.internal.refactoring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.MoveDescriptor;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.RefactoringDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.netzgut.eclipse.tapestry.internal.core.TestProject;

/// Runs JDT's real rename/move refactorings and checks that our participants take the related
/// files along.
class RelatedFilesRefactoringTest {

    private static final String RESOURCES = "src/main/resources/com/example/";

    private TestProject shop;

    @BeforeEach
    void createProject() throws CoreException {
        this.shop = TestProject.create("refactoring");
        this.shop.javaClass("com.example.pages.Index");
        this.shop.file(RESOURCES + "pages/Index.tml", "<html/>");
        this.shop.file(RESOURCES + "pages/Index_de.tml", "<html/>");
        this.shop.file(RESOURCES + "pages/Index.properties", "");
        this.shop.file(RESOURCES + "pages/Index.js", "");
        this.shop.file(RESOURCES + "pages/Index_B5.tml", "<html/>");
        this.shop.file(RESOURCES + "pages/IndexHelper.tml", "<html/>"); // similar name, must not be touched
        this.shop.file(RESOURCES + "overrides/com.example.pages.Index.tml", "<html/>");
    }

    @AfterEach
    void deleteProject() throws CoreException {
        this.shop.close();
    }

    @Test
    void renamingTypeRenamesRelatedFiles() throws CoreException {
        // ARRANGE
        IType index = this.shop.type("com.example.pages.Index");
        RenameJavaElementDescriptor descriptor = descriptor(IJavaRefactorings.RENAME_TYPE);
        descriptor.setJavaElement(index);
        descriptor.setNewName("Start");
        descriptor.setUpdateReferences(true);

        // ACT
        perform(descriptor);

        // ASSERT
        assertMoved(RESOURCES + "pages/Index.tml", RESOURCES + "pages/Start.tml");
        assertMoved(RESOURCES + "pages/Index_de.tml", RESOURCES + "pages/Start_de.tml");
        assertMoved(RESOURCES + "pages/Index.properties", RESOURCES + "pages/Start.properties");
        assertMoved(RESOURCES + "pages/Index.js", RESOURCES + "pages/Start.js");
        assertMoved(RESOURCES + "pages/Index_B5.tml", RESOURCES + "pages/Start_B5.tml");
        assertMoved(RESOURCES + "overrides/com.example.pages.Index.tml",
                    RESOURCES + "overrides/com.example.pages.Start.tml");
        assertTrue(this.shop.file(RESOURCES + "pages/IndexHelper.tml").exists());
    }

    @Test
    void renamingCompilationUnitRenamesRelatedFiles() throws CoreException {
        // ARRANGE
        ICompilationUnit index = compilationUnit("com.example.pages.Index");
        RenameJavaElementDescriptor descriptor = descriptor(IJavaRefactorings.RENAME_COMPILATION_UNIT);
        descriptor.setJavaElement(index);
        descriptor.setNewName("Start.java");

        // ACT
        perform(descriptor);

        // ASSERT
        assertMoved(RESOURCES + "pages/Index.tml", RESOURCES + "pages/Start.tml");
    }

    @Test
    void movingTypeMovesRelatedFilesAndCanBeUndone() throws CoreException {
        // ARRANGE
        IFolder javaFolder = this.shop.project.getFolder("src/main/java");
        IPackageFragmentRoot javaRoot = this.shop.javaProject.getPackageFragmentRoot(javaFolder);
        IPackageFragment destination = javaRoot.createPackageFragment("com.example.pages.admin", true, null);
        ICompilationUnit index = compilationUnit("com.example.pages.Index");

        MoveDescriptor descriptor = descriptor(IJavaRefactorings.MOVE);
        descriptor.setMoveResources(new IFile[0], new IFolder[0], new ICompilationUnit[] { index });
        descriptor.setDestination(destination);
        descriptor.setUpdateReferences(true);

        // ACT
        Change undo = perform(descriptor);

        // ASSERT
        assertMoved(RESOURCES + "pages/Index.tml", RESOURCES + "pages/admin/Index.tml");
        assertMoved(RESOURCES + "pages/Index_de.tml", RESOURCES + "pages/admin/Index_de.tml");
        assertMoved(RESOURCES + "pages/Index.properties", RESOURCES + "pages/admin/Index.properties");
        assertMoved(RESOURCES + "overrides/com.example.pages.Index.tml",
                    RESOURCES + "overrides/com.example.pages.admin.Index.tml");
        assertTrue(this.shop.file(RESOURCES + "pages/IndexHelper.tml").exists());

        // ACT
        PerformChangeOperation undoOperation = new PerformChangeOperation(undo);
        ResourcesPlugin.getWorkspace().run(undoOperation, new NullProgressMonitor());

        // ASSERT
        assertMoved(RESOURCES + "pages/admin/Index.tml", RESOURCES + "pages/Index.tml");
        assertMoved(RESOURCES + "overrides/com.example.pages.admin.Index.tml",
                    RESOURCES + "overrides/com.example.pages.Index.tml");
        assertFalse(this.shop.project.getFolder(RESOURCES + "pages/admin").exists(), "created folder is removed again");
    }

    private <T extends RefactoringDescriptor> T descriptor(String id) {
        return Refactorings.descriptor(this.shop, id);
    }

    private ICompilationUnit compilationUnit(String qualifiedName) {
        return (ICompilationUnit) JavaCore.create(this.shop.file("src/main/java/"
                                                                 + qualifiedName.replace('.', '/')
                                                                 + ".java"));
    }

    private static Change perform(RefactoringDescriptor descriptor) throws CoreException {
        return Refactorings.perform(descriptor);
    }

    private void assertMoved(String from, String to) {
        assertFalse(this.shop.file(from).exists(), from + " should be gone");
        assertTrue(this.shop.file(to).exists(), to + " should exist");
    }
}
