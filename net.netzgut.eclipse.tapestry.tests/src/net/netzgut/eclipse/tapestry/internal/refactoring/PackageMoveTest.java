package net.netzgut.eclipse.tapestry.internal.refactoring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.MoveDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import net.netzgut.eclipse.tapestry.internal.core.TestProject;
import net.netzgut.eclipse.tapestry.internal.core.TestProject.Layout;

/// Moving a Java package to another project must take its resources along.
class PackageMoveTest {

    private static final String RESOURCES = "src/main/resources/com/example/";

    private TestProject app;
    private TestProject library;

    private void createProjects(Layout layout) throws CoreException {
        String suffix = "-" + layout.name().toLowerCase();
        this.app = TestProject.create("app" + suffix, layout);
        this.app.javaClass("com.example.pages.Index");
        this.app.file(RESOURCES + "pages/Index.tml", "<html/>");
        this.app.file(RESOURCES + "pages/images/logo.svg", "<svg/>");
        this.app.javaClass("com.example.pages.admin.Users");
        this.app.file(RESOURCES + "pages/admin/Users.tml", "<html/>");
        this.library = TestProject.create("library" + suffix, layout);
    }

    @AfterEach
    void deleteProjects() throws CoreException {
        this.app.close();
        if (this.library != null) {
            this.library.close();
        }
    }

    @ParameterizedTest
    @EnumSource(Layout.class)
    void movesResourcesToOtherProject(Layout layout) throws CoreException {
        // ARRANGE
        createProjects(layout);
        IPackageFragmentRoot destination = javaRoot(this.library);

        // ACT
        // JDT itself provides no undo for package moves, so there is nothing to test there
        movePackage("com.example.pages", destination);

        // ASSERT
        assertTrue(this.library.file(RESOURCES + "pages/Index.tml").exists());
        assertTrue(this.library.file(RESOURCES + "pages/images/logo.svg").exists());
        assertFalse(this.app.file(RESOURCES + "pages/Index.tml").exists());
        assertTrue(this.app.file(RESOURCES + "pages/admin/Users.tml").exists(), "sub package stays");
        assertFalse(this.app.project.getFolder(RESOURCES + "pages/images").exists(), "emptied folder is removed");
    }

    @Test
    void leavesResourcesAloneWithinProject() throws CoreException {
        // ARRANGE
        createProjects(Layout.GRADLE);
        IClasspathEntry[] classpath = this.app.javaProject.getRawClasspath();
        IClasspathEntry[] extended = Arrays.copyOf(classpath, classpath.length + 1);
        IFolder otherSources = this.app.mkdirs("src/other/java");
        extended[classpath.length] = JavaCore.newSourceEntry(otherSources.getFullPath());
        this.app.javaProject.setRawClasspath(extended, null);
        IPackageFragmentRoot destination = this.app.javaProject.getPackageFragmentRoot(otherSources);

        // ACT
        movePackage("com.example.pages", destination);

        // ASSERT
        assertTrue(this.app.file("src/other/java/com/example/pages/Index.java").exists());
        assertTrue(this.app.file(RESOURCES + "pages/Index.tml").exists());
    }

    private void movePackage(String name, IPackageFragmentRoot destination) throws CoreException {
        IPackageFragment fragment = javaRoot(this.app).getPackageFragment(name);
        MoveDescriptor descriptor = Refactorings.descriptor(this.app, IJavaRefactorings.MOVE);
        descriptor.setMovePackages(new IPackageFragment[] { fragment });
        descriptor.setDestination(destination);
        descriptor.setUpdateReferences(true);
        Refactorings.perform(descriptor);
    }

    private static IPackageFragmentRoot javaRoot(TestProject project) {
        return project.javaProject.getPackageFragmentRoot(project.project.getFolder("src/main/java"));
    }
}
