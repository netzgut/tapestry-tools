package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;

/// How fields and methods of a page/component class refer to components in its template (`t:id`)
public final class ComponentIds {

    /// Tapestry splits handler names at the first "From": `onSuccessFromLoginForm` -> `LoginForm`.
    private static final Pattern HANDLER_NAME = Pattern.compile("on\\w+?From(\\w+)");

    /// The component id of a `@Component` or `@InjectComponent` field: the annotation's id/value,
    /// or the field name. `null` for other fields.
    public static String ofField(IField field) throws CoreException {
        IAnnotation[] annotations = field.getAnnotations();

        IAnnotation component = JavaSources.annotation(annotations, "Component");
        IAnnotation injected = JavaSources.annotation(annotations, "InjectComponent");

        if (component == null && injected == null) {
            return null;
        }

        String id = null;
        if (component != null) {
            String componentId = JavaSources.stringValue(component, "id");
            id = Strings.blankToNull(componentId);
        }

        if (id == null && injected != null) {
            String injectedId = JavaSources.stringValue(injected, "value");
            id = Strings.blankToNull(injectedId);
        }

        if (id == null) {
            return field.getElementName();
        }

        return id;
    }

    /// The component whose events a method handles: `@OnEvent(component = "x")`, or by naming
    /// convention. `null` for other methods and handlers of the class's own events.
    public static String ofMethod(IMethod method) throws CoreException {
        IAnnotation onEvent = JavaSources.annotation(method.getAnnotations(), "OnEvent");
        if (onEvent != null) {
            String componentValue = JavaSources.stringOrConstant(method.getDeclaringType(), onEvent, "component");
            String component = Strings.blankToNull(componentValue);
            if (component != null) {
                return component;
            }
        }

        Matcher name = HANDLER_NAME.matcher(method.getElementName());
        if (name.matches()) {
            return name.group(1);
        }

        return null;
    }

    /// Fields and handler methods of `type` referring to component `id` (case-insensitive).
    public static List<IMember> references(IType type, String id) throws CoreException {
        List<IMember> result = new ArrayList<>();
        for (IField field : type.getFields()) {
            String fieldId = ofField(field);
            if (id.equalsIgnoreCase(fieldId)) {
                result.add(field);
            }
        }

        for (IMethod method : type.getMethods()) {
            String methodComponentId = ofMethod(method);
            if (id.equalsIgnoreCase(methodComponentId)) {
                result.add(method);
            }
        }

        return result;
    }

    private ComponentIds() {
    }

}
