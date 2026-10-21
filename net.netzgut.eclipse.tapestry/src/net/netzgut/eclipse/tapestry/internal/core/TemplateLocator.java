package net.netzgut.eclipse.tapestry.internal.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;

/// Finds templates for classes and classes for templates; see [RelatedFiles] for the conventions.
public final class TemplateLocator {

    /// The content of a template (or any text file) in its configured encoding.
    public static String read(IFile file) throws CoreException {
        return read(file, file.getCharset());
    }

    public static String read(IFile file, String charset) throws CoreException {
        try (InputStream in = file.getContents(true)) {
            byte[] bytes = in.readAllBytes();
            Charset encoding = Charset.forName(charset);
            return new String(bytes, encoding);
        }
        catch (IOException e) {
            IStatus status = Status.error("Could not read " + file.getFullPath(), e);
            throw new CoreException(status);
        }
    }

    public static List<IFile> findTemplates(IType type) throws CoreException {
        return RelatedFiles.find(type, RelatedFiles::isTemplate);
    }

    /// Classes a related file (template, messages, …) belongs to. For `Index_B5_de.tml` the most
    /// specific existing class wins: `Index_B5_de`, then `Index_B5`, then `Index`.
    public static List<IType> findClasses(IFile template, IProgressMonitor monitor) throws CoreException {
        IContainer root = RelatedFiles.sourceFolderOf(template);
        if (root == null) {
            return List.of();
        }

        IJavaProject javaProject = JavaCore.create(template.getProject());

        String baseName = template.getFullPath().removeFileExtension().lastSegment();
        boolean override = RelatedFiles.isInOverrideFolder(root, template);
        IPath parentPath = template.getParent().getFullPath();
        IPath relativePath = parentPath.makeRelativeTo(root.getFullPath());
        String packageName = String.join(".", relativePath.segments());

        for (String className : TapestryConventions.candidateClassNames(baseName)) {
            Set<IType> types = new LinkedHashSet<>();
            if (!override) {
                IType type = javaProject.findType(packageName, className);
                addIfExists(type, types);
            }
            else if (className.contains(".")) {
                IType type = javaProject.findType(className);
                addIfExists(type, types);
            }
            else {
                List<IType> bySimpleName = findTypesBySimpleName(javaProject, className, monitor);
                types.addAll(bySimpleName);
            }

            if (!types.isEmpty()) {
                return new ArrayList<>(types);
            }
        }
        return List.of();
    }

    private static List<IType> findTypesBySimpleName(IJavaProject javaProject,
                                                     String simpleName,
                                                     IProgressMonitor monitor) throws CoreException {
        List<IType> found = new ArrayList<>();
        IJavaSearchScope scope = SearchEngine.createJavaSearchScope(new IJavaElement[] { javaProject });
        TypeNameMatchRequestor requestor = new TypeNameMatchRequestor() {

            @Override
            public void acceptTypeNameMatch(TypeNameMatch match) {
                found.add(match.getType());
            }
        };
        new SearchEngine().searchAllTypeNames(null,
                                              SearchPattern.R_EXACT_MATCH,
                                              simpleName.toCharArray(),
                                              SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
                                              IJavaSearchConstants.CLASS,
                                              scope,
                                              requestor,
                                              IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                                              monitor);
        return found;
    }

    private static void addIfExists(IType type, Set<IType> types) {
        if (type != null && type.exists()) {
            types.add(type);
        }
    }

    private TemplateLocator() {
    }
}
