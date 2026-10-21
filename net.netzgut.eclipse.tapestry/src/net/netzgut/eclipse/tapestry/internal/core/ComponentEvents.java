package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMemberValuePair;
import org.eclipse.jdt.core.IType;

/// Resolves component and mixin names from templates to classes and collects the events they
/// declare via `@Events`.
///
/// See [ComponentResolver] for how names are resolved. Results are cached for the lifetime of this
/// object (one completion request).
final class ComponentEvents {

    private static final Pattern EVENTS_ANNOTATION =
        Pattern.compile("@(?:[\\w.]*\\.)?Events\\s*\\(\\s*(\\{.*?\\}|[^)]*)\\)", Pattern.DOTALL);
    private static final Pattern EVENTS_TOKEN      = Pattern.compile("\"([^\"]*)\"|([A-Za-z_][\\w.]*)");
    private static final Pattern EVENT_NAME        = Pattern.compile("[A-Za-z_]\\w*");

    private final IJavaProject              project;
    private final ComponentResolver         resolver;
    private final Map<String, List<String>> cache = new HashMap<>();

    ComponentEvents(IJavaProject project, IProgressMonitor monitor) {
        this.project = project;
        this.resolver = new ComponentResolver(project, monitor);
    }

    List<String> ofComponent(String name) throws CoreException {
        return resolve(name, ComponentResolver.Kind.COMPONENT, CoreEvents::componentEvents);
    }

    List<String> ofMixin(String name) throws CoreException {
        return resolve(name, ComponentResolver.Kind.MIXIN, CoreEvents::mixinEvents);
    }

    /// Events declared by `type` and its superclasses
    List<String> of(IType type) throws CoreException {
        Set<String> events = new LinkedHashSet<>();
        IType current = type;
        for (int depth = 0; current != null && depth < 10; depth++) {
            String packageName = current.getPackageFragment().getElementName();

            if (packageName.equals(CoreEvents.CORE_COMPONENTS_PACKAGE)) {
                List<String> coreComponentEvents = CoreEvents.componentEvents(current.getElementName());
                events.addAll(coreComponentEvents);
                break;
            }

            if (packageName.equals(CoreEvents.CORE_MIXINS_PACKAGE)) {
                List<String> coreMixinEvents = CoreEvents.mixinEvents(current.getElementName());
                events.addAll(coreMixinEvents);
                break;
            }

            List<String> declaredEvents;
            if (current.isBinary()) {
                declaredEvents = declaredEventsFromBinary(current);
            }
            else {
                declaredEvents = declaredEvents(current);
            }

            events.addAll(declaredEvents);
            current = superclass(current);
        }

        return List.copyOf(events);
    }

    private List<String> resolve(String name,
                                 ComponentResolver.Kind kind,
                                 Function<String, List<String>> coreEvents) throws CoreException {

        String key = kind + ":" + name.toLowerCase(Locale.ROOT);

        List<String> cached = this.cache.get(key);
        if (cached != null) {
            return cached;
        }

        List<String> result;
        List<IType> types = this.resolver.resolve(kind, name);
        if (types.isEmpty()) {
            result = coreEvents.apply(name);
        }
        else {
            Set<String> events = new LinkedHashSet<>();
            for (IType type : types) {
                List<String> typeEvents = of(type);
                events.addAll(typeEvents);
            }
            result = List.copyOf(events);
        }

        this.cache.put(key, result);

        return result;
    }

    private List<String> declaredEvents(IType type) throws CoreException {
        List<String> events = new ArrayList<>();

        for (IAnnotation annotation : type.getAnnotations()) {
            String annotationName = annotation.getElementName();
            if (annotationName.equals("Events") || annotationName.endsWith(".Events")) {
                for (IMemberValuePair pair : annotation.getMemberValuePairs()) {
                    Object value = pair.getValue();
                    Object[] values;
                    if (value instanceof Object[] array) {
                        values = array;
                    }
                    else {
                        values = new Object[] { value };
                    }

                    for (Object element : values) {
                        if (!(element instanceof String text)) {
                            continue;
                        }
                        String event = switch (pair.getValueKind()) {
                            case IMemberValuePair.K_STRING -> text;
                            case IMemberValuePair.K_QUALIFIED_NAME, IMemberValuePair.K_SIMPLE_NAME -> JavaSources.stringConstant(this.project,
                                                                                                                                 type,
                                                                                                                                 text);
                            // mixed arrays like {EventConstants.ACTION, "custom"}: try both
                            default -> {
                                String constant = JavaSources.stringConstant(this.project, type, text);
                                if (constant != null) {
                                    yield constant;
                                }
                                yield text;
                            }
                        };
                        addEvent(event, events);
                    }
                }
            }
        }

        return events;
    }

    /// @Events is not in the class file, so read it from attached sources (if there are any)
    private List<String> declaredEventsFromBinary(IType type) throws CoreException {
        String source = type.getSource();
        if (source == null) {
            return List.of();
        }

        Matcher annotation = EVENTS_ANNOTATION.matcher(source);

        if (annotation.find() == false) {
            return List.of();
        }

        List<String> events = new ArrayList<>();
        String annotationValue = annotation.group(1);
        Matcher token = EVENTS_TOKEN.matcher(annotationValue);
        while (token.find()) {
            String literal = token.group(1);
            String event;
            if (literal != null) {
                event = literal;
            }
            else {
                String constantName = token.group(2);
                event = JavaSources.stringConstant(this.project, type, constantName);
            }
            addEvent(event, events);
        }

        return events;
    }

    /// `"selected by default, may be overridden"` -> `selected`. Internal events are skipped.
    private static void addEvent(String value, //
                                 List<String> events) {
        if (value == null || value.contains("internal")) {
            return;
        }

        String stripped = value.strip();
        Matcher name = EVENT_NAME.matcher(stripped);
        if (name.lookingAt()) {
            events.add(name.group());
        }
    }

    private IType superclass(IType type) throws CoreException {
        String name = type.getSuperclassName();
        if (name == null) {
            return null;
        }

        int generics = name.indexOf('<');
        if (generics >= 0) {
            name = name.substring(0, generics);
        }

        return JavaSources.resolveType(this.project, type, name);
    }
}
