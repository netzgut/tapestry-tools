package net.netzgut.eclipse.tapestry.internal.core;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;

/// A Java project in the test workspace, laid out like a Gradle/Maven Tapestry app.
public final class TestProject implements AutoCloseable {

    public final IProject     project;
    public final IJavaProject javaProject;

    private TestProject(IProject project) {
        this.project = project;
        this.javaProject = JavaCore.create(project);
    }

    /// Resource folder layout of the build tool that set up the Eclipse project.
    public enum Layout {
        /// m2e: src/main/resources is a source folder excluding everything
        MAVEN,
        /// Buildship: src/main/resources is a plain source folder
        GRADLE
    }

    public static TestProject create(String name) throws CoreException {
        return create(name, Layout.MAVEN);
    }

    public static TestProject create(String name, Layout layout) throws CoreException {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        project.create(null);
        project.open(null);
        IProjectDescription description = project.getDescription();
        description.setNatureIds(new String[] { JavaCore.NATURE_ID });
        project.setDescription(description, null);

        TestProject result = new TestProject(project);
        IPath java = result.mkdirs("src/main/java").getFullPath();
        IPath resources = result.mkdirs("src/main/resources").getFullPath();
        result.javaProject.setRawClasspath(new IClasspathEntry[] { JavaCore.newSourceEntry(java),
                                                                   layout == Layout.MAVEN ? JavaCore.newSourceEntry(resources,
                                                                                                                    new IPath[0],
                                                                                                                    new IPath[] { IPath.fromPortableString("**") },
                                                                                                                    null)
                                                                                          : JavaCore.newSourceEntry(resources) },
                                           result.mkdirs("bin").getFullPath(),
                                           null);
        return result;
    }

    public IFile file(String path) {
        return this.project.getFile(path);
    }

    public IFile file(String path, String content) throws CoreException {
        IFile file = this.project.getFile(path);
        mkdirs(file.getParent());
        file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, null);
        return file;
    }

    /// Creates `src/main/java/<package>/<Name>.java` with an empty public class.
    public IFile javaClass(String qualifiedName) throws CoreException {
        int dot = qualifiedName.lastIndexOf('.');
        String packageName = qualifiedName.substring(0, dot);
        String simpleName = qualifiedName.substring(dot + 1);
        return file("src/main/java/" + qualifiedName.replace('.', '/') + ".java",
                    "package " + packageName + "; public class " + simpleName + " {}");
    }

    public IType type(String qualifiedName) throws CoreException {
        return this.javaProject.findType(qualifiedName);
    }

    public IFolder mkdirs(String path) throws CoreException {
        IFolder folder = this.project.getFolder(path);
        mkdirs(folder);
        return folder;
    }

    private static void mkdirs(IContainer container) throws CoreException {
        if (container instanceof IFolder folder && !folder.exists()) {
            mkdirs(folder.getParent());
            folder.create(IResource.FORCE, true, null);
        }
    }

    @Override
    public void close() throws CoreException {
        this.project.delete(true, true, null);
    }
}
