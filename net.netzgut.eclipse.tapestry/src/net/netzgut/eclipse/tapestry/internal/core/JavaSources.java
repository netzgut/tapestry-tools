package net.netzgut.eclipse.tapestry.internal.core;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMemberValuePair;
import org.eclipse.jdt.core.IType;

/// Resolving names and constants as written in Java source, without a full AST.
final class JavaSources {

    /// Resolves a simple or qualified type name in the context of `context` (imports,
    /// same package).
    static IType resolveType(IJavaProject project, //
                             IType context,
                             String name) throws CoreException {
        if (!context.isBinary()) {
            String[][] resolved = context.resolveType(name);
            if (resolved != null && resolved.length > 0) {
                return project.findType(resolved[0][0], resolved[0][1]);
            }
        }

        IType type = project.findType(name);
        if (type == null) {
            String packageName = context.getPackageFragment().getElementName();
            type = project.findType(packageName, name);
        }

        if (type == null && name.equals("EventConstants")) {
            type = project.findType(EventHandlers.EVENT_CONSTANTS);
        }

        return type;
    }

    /// `EventConstants.ACTION` or `PREFIX` (a field of `context`) -> its String value.
    static String stringConstant(IJavaProject project, //
                                 IType context,
                                 String name) throws CoreException {
        int dot = name.lastIndexOf('.');
        String fieldName = name.substring(dot + 1);
        IType owner;
        if (dot < 0) {
            owner = context;
        }
        else {
            String ownerName = name.substring(0, dot);
            owner = resolveType(project, context, ownerName);
        }

        if (owner == null) {
            return null;
        }

        IField field = owner.getField(fieldName);
        if (!field.exists()) {
            return null;
        }

        // Source fields return String constants as written, including quotes; binary fields return
        // the value
        Object constant = field.getConstant();
        if (constant instanceof String text) {
            return Strings.unquote(text);
        }

        return null;
    }

    static IAnnotation annotation(IAnnotation[] annotations, String simpleName) {
        for (IAnnotation annotation : annotations) {
            String name = annotation.getElementName();
            if (name.equals(simpleName) || name.endsWith("." + simpleName)) {
                return annotation;
            }
        }

        return null;
    }

    static String stringValue(IAnnotation annotation, String member) throws CoreException {
        for (IMemberValuePair pair : annotation.getMemberValuePairs()) {
            if (pair.getMemberName().equals(member) && pair.getValue() instanceof String value) {
                return value;
            }
        }

        return null;
    }

    /// A String member value, resolving constants: for `@OnEvent(EventConstants.SUCCESS)` JDT
    /// reports the name `"EventConstants.SUCCESS"`, which we look up as `"success"`.
    static String stringOrConstant(IType context, IAnnotation annotation, String member) throws CoreException {
        for (IMemberValuePair pair : annotation.getMemberValuePairs()) {
            if (pair.getMemberName().equals(member) && pair.getValue() instanceof String value) {
                return switch (pair.getValueKind()) {
                    case //
                        IMemberValuePair.K_QUALIFIED_NAME, //
                        IMemberValuePair.K_SIMPLE_NAME -> stringConstant(context.getJavaProject(), context, value);
                    default -> value;
                };
            }
        }
        return null;
    }

    private JavaSources() {
    }
}
