package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMemberValuePair;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.Signature;

/// The parameters of a component or mixin: its `@Parameter` fields and those of its superclasses,
/// which source and binary classes (Tapestry's jar) both keep, as `@Parameter` is retained
/// at runtime.
public final class ComponentParameters {

    /// @param name as used in templates: `@Parameter(name)` or the field name without
    ///            leading/trailing `_` and `$`, like Tapestry's `ParameterWorker`
    /// @param defaultPrefix binding prefix for values without one; `prop` unless declared otherwise
    public record Parameter(String name, IField field, boolean required, String defaultPrefix) {
    }

    private static final Pattern MEMBER_NAME    = Pattern.compile("^[_$]*(.+?)[_$]*$");
    private static final String  DEFAULT_PREFIX = "prop";

    private ComponentParameters() {
    }

    /// Subclass parameters first. A parameter redeclared in a subclass hides the inherited one.
    public static List<Parameter> of(IType type, //
                                     IProgressMonitor monitor) throws CoreException {
        Map<String, Parameter> parameters = new LinkedHashMap<>();
        ITypeHierarchy hierarchy = type.newSupertypeHierarchy(monitor);
        for (IType current = type; current != null; current = hierarchy.getSuperclass(current)) {
            for (IField field : current.getFields()) {
                IAnnotation annotation = JavaSources.annotation(field.getAnnotations(), "Parameter");
                if (annotation == null) {
                    continue;
                }
                Parameter parameter = parameter(field, annotation);
                String key = parameter.name().toLowerCase(Locale.ROOT);
                parameters.putIfAbsent(key, parameter);

            }
        }

        return new ArrayList<>(parameters.values());
    }

    /// The parameter named `name` (case-insensitive, like Tapestry), or `null`.
    public static Parameter find(IType type, //
                                 String name,
                                 IProgressMonitor monitor) throws CoreException {
        List<Parameter> parameters = of(type, monitor);
        for (Parameter parameter : parameters) {
            if (parameter.name().equalsIgnoreCase(name)) {
                return parameter;
            }
        }

        return null;
    }

    private static Parameter parameter(IField field, //
                                       IAnnotation annotation) throws CoreException {
        String name = null;
        boolean required = false;
        String defaultPrefix = DEFAULT_PREFIX;
        for (IMemberValuePair pair : annotation.getMemberValuePairs()) {
            switch (pair.getMemberName()) {
                case "name" -> {
                    if (pair.getValue() instanceof String value) {
                        // @Parameter(name = "") means: use the field name
                        name = Strings.blankToNull(value);
                    }
                }

                case "required" -> {
                    required = Boolean.TRUE.equals(pair.getValue());
                }

                case "defaultPrefix" -> {
                    // A constant like BindingConstants.LITERAL arrives as its name in source
                    if (pair.getValue() instanceof String value) {
                        if (pair.getValueKind() == IMemberValuePair.K_STRING) {
                            defaultPrefix = value;
                        }
                        else {
                            String constantName = Signature.getSimpleName(value);
                            defaultPrefix = constantName.toLowerCase(Locale.ROOT);
                        }
                    }
                }
            }
        }

        if (name == null) {
            name = stripMemberName(field.getElementName());
        }

        return new Parameter(name,
                             field,
                             required,
                             defaultPrefix);
    }

    static String stripMemberName(String memberName) {
        Matcher matcher = MEMBER_NAME.matcher(memberName);
        if (matcher.matches()) {
            return matcher.group(1);
        }

        return memberName;
    }
}
