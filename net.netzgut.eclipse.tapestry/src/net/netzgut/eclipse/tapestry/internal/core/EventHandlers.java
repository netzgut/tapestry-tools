package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;

/// Computes the event handler methods a page/component class could declare, based on the components
/// in its template(s), and leaves out the ones it already has.
public final class EventHandlers {

    private record ParsedTemplate(long modificationStamp, //
                                  List<ComponentUsage> components) {
    }

    private static final Map<IPath, ParsedTemplate> TEMPLATES   = new ConcurrentHashMap<>();
    private static final List<String>               PAGE_EVENTS = List.of("activate", "passivate");

    public static final String EVENT_CONSTANTS = "org.apache.tapestry5.EventConstants";

    public static List<EventHandler> missingHandlers(IType type, IProgressMonitor monitor) throws CoreException {
        Set<String> existing = existingHandlers(type);
        Map<String, String> constants = eventConstants(type);
        ComponentEvents componentEvents = new ComponentEvents(type.getJavaProject(), monitor);
        Map<String, EventHandler> handlers = new LinkedHashMap<>();

        for (IFile template : TemplateLocator.findTemplates(type)) {
            for (ComponentUsage component : components(template)) {
                String componentType = component.type();
                Set<String> events = new LinkedHashSet<>(component.explicitEvents());
                if (componentType != null) {
                    events.addAll(componentEvents.ofComponent(componentType));
                }
                else {
                    // <form t:id="login"> with @Component Form login / @InjectComponent Form login
                    IType declared = declaredComponentType(type, component.id());
                    if (declared != null) {
                        componentType = declared.getElementName();
                        events.addAll(componentEvents.of(declared));
                    }
                }

                for (String mixin : component.mixins()) {
                    events.addAll(componentEvents.ofMixin(mixin));
                }

                for (String event : events) {
                    String name = handlerName(event, component.id());
                    String key = name.toLowerCase(Locale.ROOT);
                    if (!existing.contains(key)) {
                        handlers.putIfAbsent(key,
                                             new EventHandler(name,
                                                              event,
                                                              constants.get(event.toLowerCase(Locale.ROOT)),
                                                              component.id(),
                                                              componentType,
                                                              CoreEvents.returnType(event),
                                                              template.getName()));
                    }
                }
            }
        }
        if (isPage(type)) {
            for (String event : PAGE_EVENTS) {
                String name = handlerName(event, null);
                String key = name.toLowerCase(Locale.ROOT);
                if (!existing.contains(key)) {
                    handlers.putIfAbsent(key,
                                         new EventHandler(name,
                                                          event,
                                                          constants.get(event),
                                                          null,
                                                          null,
                                                          CoreEvents.returnType(event),
                                                          null));
                }
            }
        }
        return new ArrayList<>(handlers.values());
    }

    /// Pages live in a `pages` package (or below), like `com.example.pages.admin.Index`.
    static boolean isPage(IType type) {
        return packageSegments(type).contains("pages");
    }

    /// Pages, components, mixins and their base classes, or anything with a template.
    public static boolean isComponentClass(IType type) throws CoreException {
        List<String> segments = packageSegments(type);
        return segments.contains("pages")
               || segments.contains("components")
               || segments.contains("mixins")
               || segments.contains("base")
               || !TemplateLocator.findTemplates(type).isEmpty();
    }

    private static List<String> packageSegments(IType type) {
        return List.of(type.getPackageFragment().getElementName().split("\\."));
    }

    /// `("success", "loginForm")` -> `onSuccessFromLoginForm`
    public static String handlerName(String event, String componentId) {
        String name = "on" + Strings.capitalize(event);
        if (componentId != null) {
            name = name + "From" + Strings.capitalize(componentId);
        }
        return name;
    }

    /// Lower-cased names of handled events in naming-convention form, including `@OnEvent` methods.
    private static Set<String> existingHandlers(IType type) throws CoreException {
        Set<String> names = new HashSet<>();
        for (IMethod method : type.getMethods()) {
            String methodName = method.getElementName().toLowerCase(Locale.ROOT);
            names.add(methodName);
            IAnnotation onEvent = JavaSources.annotation(method.getAnnotations(), "OnEvent");
            if (onEvent == null) {
                continue;
            }

            String event = JavaSources.stringOrConstant(type, onEvent, "value");
            String component = JavaSources.stringOrConstant(type, onEvent, "component");
            if (event == null) {
                event = "action";
            }
            String componentId = Strings.blankToNull(component);
            String conventionName = handlerName(event, componentId).toLowerCase(Locale.ROOT);
            names.add(conventionName);

        }
        return names;
    }

    /// Lower-cased event names -> `EventConstants` field names, read from the Tapestry version on
    /// the project's classpath. Empty if Tapestry is not on it.
    private static Map<String, String> eventConstants(IType type) throws CoreException {
        Map<String, String> constants = new HashMap<>();
        IType eventConstants = type.getJavaProject().findType(EVENT_CONSTANTS);
        if (eventConstants == null) {
            return constants;
        }

        for (IField field : eventConstants.getFields()) {
            // Binary fields return the value, source fields the literal including quotes
            if (field.getConstant() instanceof String value) {
                String event = Strings.unquote(value);
                String key = event.toLowerCase(Locale.ROOT);
                constants.putIfAbsent(key, field.getElementName());
            }
        }
        return constants;
    }

    private static IType declaredComponentType(IType type, //
                                               String componentId) throws CoreException {
        if (componentId == null) {
            return null;
        }

        for (IField field : type.getFields()) {
            String fieldComponentId = ComponentIds.ofField(field);
            if (componentId.equalsIgnoreCase(fieldComponentId)) {
                String erasure = Signature.getTypeErasure(field.getTypeSignature());
                String simpleName = Signature.getSignatureSimpleName(erasure);
                return JavaSources.resolveType(type.getJavaProject(), type, simpleName);
            }
        }
        return null;
    }

    private static List<ComponentUsage> components(IFile template) throws CoreException {
        IPath path = template.getFullPath();
        long stamp = template.getModificationStamp();
        ParsedTemplate parsed = TEMPLATES.get(path);
        if (parsed == null || parsed.modificationStamp() != stamp) {
            String content = TemplateLocator.read(template);
            List<ComponentUsage> components = TemplateParser.parse(content);
            parsed = new ParsedTemplate(stamp, components);
            TEMPLATES.put(path, parsed);
        }
        return parsed.components();
    }

    private EventHandlers() {
    }
}
