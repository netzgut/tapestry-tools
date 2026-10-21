package net.netzgut.eclipse.tapestry.internal.core;

import java.util.Comparator;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Region;

/// The Java element a position in a template refers to, for hovers: the class of a component, mixin
/// or page, the `@Parameter` field of a parameter, or the getter, field or method of a
/// property expression.
public sealed interface TemplateElement {

    /// The element to show the Javadoc of.
    IMember member();

    /// @param name as written in the template (`grid`, `layout/header`)
    /// @param parameters required ones first, then by name; empty for pages
    record Component(TemplateReference.Kind kind,
                     String name,
                     IType member,
                     List<ComponentParameters.Parameter> parameters)
        implements
            TemplateElement {
    }

    /// @param component as written in the template
    record Parameter(String component, //
                     ComponentParameters.Parameter parameter)
        implements
            TemplateElement {

        @Override
        public IMember member() {
            return this.parameter.field();
        }
    }

    record Property(String name, //
                    IMember member)
        implements
            TemplateElement {
    }

    /// The text that may refer to an element, without resolving anything; `null` if there is none
    /// at `offset`.
    static IRegion regionAt(String template, int offset) {
        TemplateReference reference = TemplateReference.at(template, offset);
        if (reference != null) {
            if (!isJavaReference(reference)) {
                return null;
            }

            return new Region(reference.offset(), reference.length());
        }

        ParameterReference parameter = ParameterReference.at(template, offset, false);
        if (parameter != null) {
            return new Region(parameter.offset(), parameter.length());
        }

        PropertyExpression expression = PropertyExpression.at(template, offset, false);
        if (expression == null) {
            return null;
        }

        return new Region(expression.offset(), expression.length());
    }

    /// The element at `offset`, or `null` if there is none or it can't be resolved.
    static TemplateElement at(IFile template,
                              String content,
                              int offset,
                              IProgressMonitor monitor) throws CoreException {
        IJavaProject project = JavaCore.create(template.getProject());
        if (!project.exists()) {
            return null;
        }

        TemplateReference reference = TemplateReference.at(content, offset);
        if (reference != null) {
            if (!isJavaReference(reference)) {
                return null;
            }

            return component(project, reference, monitor);
        }

        ParameterReference parameter = ParameterReference.at(content, offset, false);
        if (parameter != null) {
            ComponentResolver resolver = new ComponentResolver(project, monitor);
            List<IType> candidates = resolver.resolve(ComponentResolver.Kind.COMPONENT, parameter.component());
            for (IType type : candidates) {
                ComponentParameters.Parameter found = ComponentParameters.find(type, parameter.name(), monitor);
                if (found != null) {
                    return new Parameter(parameter.component(), found);
                }
            }

            return null;
        }

        PropertyExpression expression = PropertyExpression.at(content, offset, false);
        if (expression != null) {
            IType type = PropertyPaths.context(template, expression, monitor);
            if (type == null) {
                return null;
            }

            IMember member = PropertyPaths.find(type, expression.name(), monitor);
            if (member == null) {
                return null;
            }

            return new Property(expression.name(), member);
        }

        return null;
    }

    private static boolean isJavaReference(TemplateReference reference) {
        return switch (reference.kind()) {
            case COMPONENT, MIXIN, PAGE -> true;
            case COMPONENT_ID, MESSAGE -> false;
        };
    }

    private static Component component(IJavaProject project,
                                       TemplateReference reference,
                                       IProgressMonitor monitor) throws CoreException {
        ComponentResolver.Kind kind = switch (reference.kind()) {
            case MIXIN -> ComponentResolver.Kind.MIXIN;
            case PAGE -> ComponentResolver.Kind.PAGE;
            default -> ComponentResolver.Kind.COMPONENT;
        };

        List<IType> types = new ComponentResolver(project, monitor).resolve(kind, reference.value());
        if (types.isEmpty()) {
            return null;
        }

        IType type = types.get(0);
        List<ComponentParameters.Parameter> parameters;
        if (kind == ComponentResolver.Kind.PAGE) {
            parameters = List.of();
        }
        else {
            parameters = ComponentParameters.of(type, monitor).stream()
                                            .sorted(Comparator.comparing((ComponentParameters.Parameter parameter) -> !parameter.required())
                                                              .thenComparing(ComponentParameters.Parameter::name,
                                                                             String.CASE_INSENSITIVE_ORDER))
                                            .toList();
        }

        return new Component(reference.kind(), reference.value(), type, parameters);
    }
}
