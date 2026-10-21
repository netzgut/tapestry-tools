package net.netzgut.eclipse.tapestry.internal.refactoring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import net.netzgut.eclipse.tapestry.internal.core.TestProject;
import net.netzgut.eclipse.tapestry.internal.core.TestProject.Layout;

/// Renaming a Java package must take the same package in resource folders along.
class PackageRenameTest {

    private static final String RESOURCES = "src/main/resources/com/example/";

    private TestProject shop;

    private void createProject(Layout layout) throws CoreException {
        this.shop = TestProject.create("packages-" + layout.name().toLowerCase(), layout);
        this.shop.javaClass("com.example.pages.Index");
        this.shop.file(RESOURCES + "pages/Index.tml", "<html/>");
        this.shop.file(RESOURCES + "pages/Index_de.properties", "");
        this.shop.file(RESOURCES + "pages/images/logo.svg", "<svg/>"); // resource-only sub folder
        this.shop.javaClass("com.example.pages.admin.Users");
        this.shop.file(RESOURCES + "pages/admin/Users.tml", "<html/>");
        this.shop.file(RESOURCES + "overrides/com.example.pages.Index.tml", "<html/>");
        this.shop.file(RESOURCES + "overrides/com.example.pages.admin.Users.tml", "<html/>");
    }

    @AfterEach
    void deleteProject() throws CoreException {
        this.shop.close();
    }

    @ParameterizedTest
    @EnumSource(Layout.class)
    void renamesResourcePackage(Layout layout) throws CoreException {
        // ARRANGE
        createProject(layout);

        // ACT
        renamePackage("com.example.pages", "com.example.views", false);

        // ASSERT
        assertExists(RESOURCES + "views/Index.tml");
        assertExists(RESOURCES + "views/Index_de.properties");
        assertExists(RESOURCES + "views/images/logo.svg");
        assertGone(RESOURCES + "pages/Index.tml");
        assertGone(RESOURCES + "pages/images/logo.svg");
        // sub packages stay where they are
        assertExists(RESOURCES + "pages/admin/Users.tml");
        assertExists(RESOURCES + "overrides/com.example.views.Index.tml");
        assertExists(RESOURCES + "overrides/com.example.pages.admin.Users.tml");
    }

    @ParameterizedTest
    @EnumSource(Layout.class)
    void renamesResourcePackageWithSubpackages(Layout layout) throws CoreException {
        // ARRANGE
        createProject(layout);

        // ACT
        renamePackage("com.example.pages", "com.example.views", true);

        // ASSERT
        assertExists(RESOURCES + "views/Index.tml");
        assertExists(RESOURCES + "views/admin/Users.tml");
        assertGone(RESOURCES + "pages/admin/Users.tml");
        assertExists(RESOURCES + "overrides/com.example.views.Index.tml");
        assertExists(RESOURCES + "overrides/com.example.views.admin.Users.tml");
        assertFalse(this.shop.project.getFolder(RESOURCES + "pages").exists(), "emptied folder is removed");
    }

    @ParameterizedTest
    @EnumSource(Layout.class)
    void undoRestoresResourcePackages(Layout layout) throws CoreException {
        // ARRANGE
        createProject(layout);

        // ACT
        Change undo = renamePackage("com.example.pages", "com.example.views", true);
        PerformChangeOperation undoOperation = new PerformChangeOperation(undo);
        ResourcesPlugin.getWorkspace().run(undoOperation, new NullProgressMonitor());

        // ASSERT
        assertExists(RESOURCES + "pages/Index.tml");
        assertExists(RESOURCES + "pages/images/logo.svg");
        assertExists(RESOURCES + "pages/admin/Users.tml");
        assertExists(RESOURCES + "overrides/com.example.pages.Index.tml");
        assertGone(RESOURCES + "views/Index.tml");
    }

    private Change renamePackage(String from, String to, boolean subpackages) throws CoreException {
        IPackageFragment fragment =
            this.shop.javaProject.getPackageFragmentRoot(this.shop.project.getFolder("src/main/java"))
                                 .getPackageFragment(from);
        RenameJavaElementDescriptor descriptor = Refactorings.descriptor(this.shop, IJavaRefactorings.RENAME_PACKAGE);
        descriptor.setJavaElement(fragment);
        descriptor.setNewName(to);
        descriptor.setUpdateReferences(true);
        descriptor.setUpdateHierarchy(subpackages);
        return Refactorings.perform(descriptor);
    }

    private void assertExists(String path) {
        assertTrue(this.shop.file(path).exists(), path + " should exist");
    }

    private void assertGone(String path) {
        assertFalse(this.shop.file(path).exists(), path + " should be gone");
    }
}
