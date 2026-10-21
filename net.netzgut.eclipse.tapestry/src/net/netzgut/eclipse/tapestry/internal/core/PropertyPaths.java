package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.Signature;

/// Walks property expressions through Java types, like Tapestry's `PropertyConduitSource`: a name
/// is a JavaBeans property (getter), a public field or `@Property` field, or a method. Names are
/// case-insensitive.
///
/// Types come from declared field and return types without generics, so `List<User>` continues
/// as `List`.
public final class PropertyPaths {

    /// @param name as written in expressions: `name`, `active` (from `isActive()`), `format` (a
    ///            method)
    /// @param method true for methods that aren't getters; they are called as `name()`
    /// @param typeName the simple name of the property's type, for display
    public record Property(String name, IMember member, boolean method, String typeName) {
    }

    private static final String OBJECT = "java.lang.Object";
    private static final String PROP   = "prop";

    /// The type whose members `expression.name()` refers to: the template's class, followed along
    /// the path; `null` if the path can't be resolved or the value isn't a property expression after
    /// all (a parameter whose default binding prefix isn't `prop`, or an informal parameter).
    public static IType context(IFile template,
                                PropertyExpression expression,
                                IProgressMonitor monitor) throws CoreException {
        IJavaProject project = JavaCore.create(template.getProject());
        if (!project.exists()) {
            return null;
        }

        if (expression.needsPropertyParameter()) {
            ComponentParameters.Parameter parameter = null;
            ComponentResolver resolver = new ComponentResolver(project, monitor);
            List<IType> components = resolver.resolve(ComponentResolver.Kind.COMPONENT, expression.component());
            for (IType component : components) {
                parameter = ComponentParameters.find(component, expression.parameter(), monitor);
                if (parameter != null) {
                    break;
                }
            }

            if (parameter == null || !parameter.defaultPrefix().equalsIgnoreCase(PROP)) {
                return null;
            }
        }

        for (IType root : TemplateLocator.findClasses(template, monitor)) {
            IType type = walk(root, expression.path(), monitor);
            if (type != null) {
                return type;
            }
        }

        return null;
    }

    /// The type `path` leads to from `root`, or `null` if a segment can't be resolved or has no
    /// class type.
    public static IType walk(IType root, //
                             List<String> path,
                             IProgressMonitor monitor) throws CoreException {
        IType current = root;
        for (String segment : path) {
            IMember member = find(current, segment, monitor);
            if (member == null) {
                return null;
            }

            current = typeOf(member);
            if (current == null) {
                return null;
            }
        }

        return current;
    }

    /// The member `name` refers to in `type`, or `null`: per class, subclasses first, a getter,
    /// then a field, which may be private (`@Property` generates its getter at runtime); a method of
    /// that name only if nothing else matches.
    public static IMember find(IType type, //
                               String name,
                               IProgressMonitor monitor) throws CoreException {
        IMember method = null;
        for (IType current : hierarchy(type, monitor)) {
            for (IMethod candidate : current.getMethods()) {
                if (candidate.isConstructor() || Flags.isStatic(candidate.getFlags())) {
                    continue;
                }

                String property = propertyName(candidate);
                if (property != null && property.equalsIgnoreCase(name)) {
                    return candidate;
                }

                if (method == null && candidate.getElementName().equalsIgnoreCase(name)) {
                    method = candidate;
                }
            }

            for (IField candidate : current.getFields()) {
                if (!Flags.isStatic(candidate.getFlags()) && candidate.getElementName().equalsIgnoreCase(name)) {
                    return candidate;
                }
            }
        }

        return method;
    }

    /// What an expression can use on `type`: properties first, then methods, each sorted by name.
    public static List<Property> of(IType type, IProgressMonitor monitor) throws CoreException {
        Map<String, Property> properties = new LinkedHashMap<>();
        Map<String, Property> methods = new LinkedHashMap<>();
        for (IType current : hierarchy(type, monitor)) {
            boolean isInterface = current.isInterface();
            for (IMethod method : current.getMethods()) {
                int flags = method.getFlags();
                if (method.isConstructor()
                    || Flags.isStatic(flags)
                    || !(isInterface || Flags.isPublic(flags))
                    || Signature.SIG_VOID.equals(method.getReturnType())) {
                    continue;
                }

                String property = propertyName(method);
                if (property != null) {
                    String key = property.toLowerCase(Locale.ROOT);
                    String returnTypeName = typeName(method.getReturnType());
                    Property getter = new Property(property, method, false, returnTypeName);
                    properties.putIfAbsent(key, getter);
                }
                else {
                    String methodName = method.getElementName();
                    String key = methodName.toLowerCase(Locale.ROOT);
                    String returnTypeName = typeName(method.getReturnType());
                    Property plainMethod = new Property(methodName, method, true, returnTypeName);
                    methods.putIfAbsent(key, plainMethod);
                }
            }

            for (IField field : current.getFields()) {
                int flags = field.getFlags();
                if (!Flags.isStatic(flags)
                    && (Flags.isPublic(flags) || JavaSources.annotation(field.getAnnotations(), "Property") != null)) {
                    String fieldName = field.getElementName();
                    String key = fieldName.toLowerCase(Locale.ROOT);
                    String fieldTypeName = typeName(field.getTypeSignature());
                    Property fieldProperty = new Property(fieldName, field, false, fieldTypeName);
                    properties.putIfAbsent(key, fieldProperty);
                }
            }
        }

        List<Property> sortedProperties = sorted(properties);
        List<Property> sortedMethods = sorted(methods);
        List<Property> result = new ArrayList<>(sortedProperties);
        result.addAll(sortedMethods);
        return result;
    }

    /// The class type of a field or method, or `null` for primitives, arrays and type variables.
    static IType typeOf(IMember member) throws CoreException {
        String signature = switch (member) {
            case IField field -> field.getTypeSignature();
            case IMethod method -> method.getReturnType();
            default -> null;
        };

        if (signature == null) {
            return null;
        }

        String erasure = Signature.getTypeErasure(signature);
        int kind = Signature.getTypeSignatureKind(erasure);
        if (kind != Signature.CLASS_TYPE_SIGNATURE) {
            return null;
        }

        IType declaring = member.getDeclaringType();
        String name = Signature.toString(erasure);
        IJavaProject javaProject = declaring.getJavaProject();
        if (declaring.isBinary()) {
            String binaryName = name.replace('$', '.');
            return javaProject.findType(binaryName);
        }

        return JavaSources.resolveType(javaProject, declaring, name);
    }

    /// `getName()` → `name`, `isActive()` → `active`, `getURL()` → `URL`; `null` if not a getter.
    static String propertyName(IMethod method) throws CoreException {
        if (method.getNumberOfParameters() > 0 || Signature.SIG_VOID.equals(method.getReturnType())) {
            return null;
        }

        String name = method.getElementName();
        int prefix = 0;
        if (name.startsWith("get")) {
            prefix = 3;
        }
        else if (name.startsWith("is") && Signature.SIG_BOOLEAN.equals(method.getReturnType())) {
            prefix = 2;
        }

        if (prefix == 0 || name.length() == prefix || !Character.isUpperCase(name.charAt(prefix))) {
            return null;
        }

        String property = name.substring(prefix);
        return Strings.decapitalize(property);
    }

    /// `type` and its supertypes, subclasses first, without `Object` (`getClass()` is not a
    /// useful property).
    private static List<IType> hierarchy(IType type, IProgressMonitor monitor) throws CoreException {
        ITypeHierarchy hierarchy = type.newSupertypeHierarchy(monitor);
        List<IType> types = new ArrayList<>();
        types.add(type);
        for (IType supertype : hierarchy.getAllSupertypes(type)) {
            if (!supertype.getFullyQualifiedName().equals(OBJECT)) {
                types.add(supertype);
            }
        }

        return types;
    }

    private static String typeName(String signature) {
        String readableName = Signature.toString(signature);
        return Signature.getSimpleName(readableName);
    }

    private static List<Property> sorted(Map<String, Property> properties) {
        List<Property> list = new ArrayList<>(properties.values());
        list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return list;
    }

    private PropertyPaths() {
    }
}
