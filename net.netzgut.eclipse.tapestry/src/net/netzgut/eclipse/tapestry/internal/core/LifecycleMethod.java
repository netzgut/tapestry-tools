package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;

/// A render phase or page lifecycle method, recognized by Tapestry by its name or its annotation,
/// e.g. `@SetupRender void setupRender()`.
///
/// @param annotation simple name of the annotation in `org.apache.tapestry5.annotations`
public record LifecycleMethod(String methodName, String annotation, String description) implements HandlerMethod {

    public static final String ANNOTATION_PACKAGE = "org.apache.tapestry5.annotations";

    /// In the order Tapestry calls them.
    private static final List<LifecycleMethod> ALL =
        List.of(new LifecycleMethod("setupRender", "SetupRender", "Render phase: first, before any markup is rendered"),
                new LifecycleMethod("beginRender", "BeginRender", "Render phase: before the component's template"),
                new LifecycleMethod("beforeRenderTemplate",
                                    "BeforeRenderTemplate",
                                    "Render phase: before the template, may decorate or replace it"),
                new LifecycleMethod("beforeRenderBody",
                                    "BeforeRenderBody",
                                    "Render phase: before the component's body"),
                new LifecycleMethod("afterRenderBody", "AfterRenderBody", "Render phase: after the component's body"),
                new LifecycleMethod("afterRenderTemplate", "AfterRenderTemplate", "Render phase: after the template"),
                new LifecycleMethod("afterRender", "AfterRender", "Render phase: counterpart of beginRender"),
                new LifecycleMethod("cleanupRender", "CleanupRender", "Render phase: last, counterpart of setupRender"),
                new LifecycleMethod("pageLoaded",
                                    "PageLoaded",
                                    "Page lifecycle: once, after the page instance is loaded"),
                new LifecycleMethod("pageAttached",
                                    "PageAttached",
                                    "Page lifecycle: when the page is attached to a request"),
                new LifecycleMethod("pageDetached",
                                    "PageDetached",
                                    "Page lifecycle: when the page is detached at the end of a request"),
                new LifecycleMethod("pageReset",
                                    "PageReset",
                                    "Page lifecycle: when a page is rendered after coming from another page"));

    @Override
    public String returnType() {
        return "void";
    }

    public String qualifiedAnnotation() {
        return ANNOTATION_PACKAGE + "." + this.annotation;
    }

    /// The lifecycle methods `type` does not declare yet, by name or by annotation.
    public static List<LifecycleMethod> missing(IType type) throws CoreException {
        Set<String> existing = new HashSet<>();
        for (IMethod method : type.getMethods()) {
            String methodName = method.getElementName().toLowerCase(Locale.ROOT);
            existing.add(methodName);
            for (IAnnotation annotation : method.getAnnotations()) {
                String name = annotation.getElementName();
                String simpleName = Signature.getSimpleName(name);
                existing.add("@" + simpleName);
            }
        }

        List<LifecycleMethod> missing = new ArrayList<>();
        for (LifecycleMethod method : ALL) {
            String methodName = method.methodName().toLowerCase(Locale.ROOT);
            String annotation = "@" + method.annotation();
            boolean declared = existing.contains(methodName) || existing.contains(annotation);
            if (!declared) {
                missing.add(method);
            }
        }
        return missing;
    }
}
