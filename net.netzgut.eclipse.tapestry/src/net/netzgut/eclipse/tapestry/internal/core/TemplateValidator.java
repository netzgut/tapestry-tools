package net.netzgut.eclipse.tapestry.internal.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJarEntryResource;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;

/// Finds references in a template that Tapestry can't resolve: unknown component types, mixins and
/// pages, parameters Tapestry would ignore or reject, message keys in none of the catalogs, and
/// (optionally) component ids without a field or event handler.
///
/// A validator is meant for one run over one or more templates of a project: it remembers what
/// it resolved.
public final class TemplateValidator {

    public enum Check {

        UNKNOWN_COMPONENT(TapestryPreferences.VALIDATE_COMPONENTS),
        UNKNOWN_PAGE(TapestryPreferences.VALIDATE_PAGES),
        UNKNOWN_PARAMETER(TapestryPreferences.VALIDATE_PARAMETERS),
        MISSING_MESSAGE(TapestryPreferences.VALIDATE_MESSAGES),
        UNKNOWN_COMPONENT_ID(TapestryPreferences.VALIDATE_COMPONENT_IDS);

        /// The preference holding this check's severity.
        public final String preference;

        Check(String preference) {
            this.preference = preference;
        }
    }

    public record Problem(Check check, //
                          String message,
                          int offset,
                          int length) {
    }

    /// Where Tapestry's built-in messages are, merged into every catalog.
    private static final String      CORE_CATALOG_PACKAGE = "org.apache.tapestry5";
    private static final String      CORE_CATALOG         = "core.properties";
    private static final String      WEB_INF              = "WEB-INF";
    private static final Set<String> RESERVED             = Set.of("id",           //
                                                                   "type",
                                                                   "mixins");

    /// What Tapestry needs to know about a component or mixin to bind a template attribute.
    private record Model(Set<String> parameters, //
                         boolean informal,
                         boolean implicitMixins) {
    }

    private final IJavaProject      project;
    private final Set<Check>        checks;
    private final IProgressMonitor  monitor;
    private final ComponentResolver resolver;
    private final Map<IType, Model> models = new HashMap<>();
    private Set<String>             globalKeys;

    public TemplateValidator(IJavaProject project, Set<Check> checks, IProgressMonitor monitor) {
        this.project = project;
        this.checks = checks;
        this.monitor = monitor;
        this.resolver = new ComponentResolver(project, monitor);
    }

    /// The problems in `content`, the text of `template`, in document order.
    public List<Problem> validate(IFile template, String content) throws CoreException {
        List<Problem> problems = new ArrayList<>();
        TemplateReference.Namespaces namespaces = TemplateReference.Namespaces.of(content);
        List<IType> classes = null;
        for (int start = TemplateReference.nextTag(content, 0);
             start >= 0 && !this.monitor.isCanceled();
             start = TemplateReference.nextTag(content, start + 1)) {
            TemplateReference.Tag tag;
            if (content.startsWith("</", start)) {
                tag = null;
            }
            else {
                tag = TemplateReference.Tag.parse(content, start);
            }

            if (tag != null) {
                if (this.checks.contains(Check.UNKNOWN_COMPONENT_ID) && classes == null) {
                    classes = TemplateLocator.findClasses(template, this.monitor);
                }
                validateTag(content, start, tag, namespaces, classes, problems);
            }
        }

        if (this.checks.contains(Check.MISSING_MESSAGE)) {
            validateMessages(template, content, problems);
        }

        problems.sort(Comparator.comparing(Problem::offset));
        return problems;
    }

    private void validateTag(String content,
                             int start,
                             TemplateReference.Tag tag,
                             TemplateReference.Namespaces namespaces,
                             List<IType> classes,
                             List<Problem> problems) throws CoreException {
        IType component = null;
        String componentName = null;
        boolean componentResolved = true;
        List<IType> mixins = new ArrayList<>();
        boolean mixinsResolved = true;
        for (TemplateReference reference : TemplateReference.references(tag, namespaces, false)) {
            if (reference.value().isEmpty()) {
                continue;
            }

            switch (reference.kind()) {
                case COMPONENT -> {
                    componentName = reference.value();
                    if (isDynamic(reference.value())) {
                        componentResolved = false;
                        continue;
                    }

                    List<IType> types = this.resolver.resolve(ComponentResolver.Kind.COMPONENT, reference.value());
                    if (types.isEmpty()) {
                        componentResolved = false;
                        report(problems,
                               Check.UNKNOWN_COMPONENT,
                               reference,
                               "Unknown component type '" + reference.value() + "'");
                    }
                    else {
                        component = types.get(0);
                    }
                }

                case MIXIN -> {
                    if (isDynamic(reference.value())) {
                        mixinsResolved = false;
                        continue;
                    }

                    List<IType> types = this.resolver.resolve(ComponentResolver.Kind.MIXIN, reference.value());
                    if (types.isEmpty()) {
                        mixinsResolved = false;
                        report(problems,
                               Check.UNKNOWN_COMPONENT,
                               reference,
                               "Unknown mixin '" + reference.value() + "'");
                    }
                    else {
                        mixins.add(types.get(0));
                    }
                }

                case PAGE -> {
                    if (isDynamic(reference.value())) {
                        continue;
                    }

                    List<IType> types = this.resolver.resolve(ComponentResolver.Kind.PAGE, reference.value());
                    if (types.isEmpty()) {
                        report(problems, Check.UNKNOWN_PAGE, reference, "Unknown page '" + reference.value() + "'");
                    }
                }

                case COMPONENT_ID -> {
                    if (this.checks.contains(Check.UNKNOWN_COMPONENT_ID)
                        && classes != null
                        && !classes.isEmpty()
                        && classes.stream().allMatch(type -> hasNoReferences(type, reference.value()))) {
                        report(problems,
                               Check.UNKNOWN_COMPONENT_ID,
                               reference,
                               "No field or event handler for component id '" + reference.value() + "'");
                    }
                }

                case MESSAGE -> {
                    // NOOP
                }
            }
        }

        if (!this.checks.contains(Check.UNKNOWN_PARAMETER)) {
            return;
        }

        if (component != null && componentResolved && mixinsResolved) {
            validateAttributes(tag, namespaces, componentName, component, mixins, problems);
        }

        String tagPrefix = TemplateReference.prefix(tag.name());
        if (namespaces.isParameter(tagPrefix)) {
            validateBlock(content, tag, problems);
        }
    }

    /// Attributes that are no parameter of the component or its mixins, on a component that takes
    /// no informal parameters: Tapestry silently ignores them, so they are most likely typos.
    private void validateAttributes(TemplateReference.Tag tag,
                                    TemplateReference.Namespaces namespaces,
                                    String componentName,
                                    IType component,
                                    List<IType> mixins,
                                    List<Problem> problems) throws CoreException {
        Model model = model(component);
        if (model.informal() || model.implicitMixins()) {
            return;
        }

        Set<String> mixinParameters = new HashSet<>();
        for (IType mixin : mixins) {
            Model mixinModel = model(mixin);
            if (mixinModel.informal()) {
                return; // the mixin takes them
            }

            mixinParameters.addAll(mixinModel.parameters());
        }

        for (TemplateReference.Attribute attribute : tag.attributes()) {
            String prefix = TemplateReference.prefix(attribute.name());
            String name = TemplateReference.localName(attribute.name());
            String lowerCase = name.toLowerCase(Locale.ROOT);
            if ((prefix != null && !namespaces.isTapestry(prefix))
                || RESERVED.contains(lowerCase)
                || name.contains(".")
                || model.parameters().contains(lowerCase)
                || mixinParameters.contains(lowerCase)) {
                continue; // xmlns:..., xml:lang, mixin parameters (autocomplete.minChars)
            }

            int nameOffset = attribute.nameOffset() + attribute.name().length() - name.length();
            problems.add(new Problem(Check.UNKNOWN_PARAMETER,
                                     "Component '"
                                                              + componentName
                                                              + "' has no parameter '"
                                                              + name
                                                              + "' and doesn't support informal parameters, so Tapestry ignores it",
                                     nameOffset,
                                     name.length()));
        }
    }

    /// `<p:name>` blocks that are no parameter of the enclosing component: Tapestry fails to load
    /// the page.
    private void validateBlock(String content, TemplateReference.Tag tag, List<Problem> problems) throws CoreException {
        int nameEnd = tag.nameOffset() + tag.name().length();
        ParameterReference reference = ParameterReference.at(content, nameEnd, false);
        if (reference == null || isDynamic(reference.component())) {
            return;
        }

        List<IType> types = this.resolver.resolve(ComponentResolver.Kind.COMPONENT, reference.component());
        if (types.isEmpty()) {
            return; // reported as an unknown component
        }

        Model model = model(types.get(0));
        String lowerName = reference.name().toLowerCase(Locale.ROOT);
        if (model.informal()
            || model.implicitMixins()
            || model.parameters().contains(lowerName)) {
            return;
        }

        problems.add(new Problem(Check.UNKNOWN_PARAMETER,
                                 "Component '"
                                                          + reference.component()
                                                          + "' has no parameter '"
                                                          + reference.name()
                                                          + "'",
                                 reference.offset(),
                                 reference.length()));
    }

    private void validateMessages(IFile template, String content, List<Problem> problems) throws CoreException {
        List<TemplateReference> keys = TemplateReference.messageKeys(content);
        if (keys.isEmpty()) {
            return;
        }

        List<IType> classes = TemplateLocator.findClasses(template, this.monitor);
        if (classes.isEmpty()) {
            return; // whose catalogs?
        }

        Set<String> sharedKeys = globalKeys();
        Set<String> known = new HashSet<>(sharedKeys);
        for (IType type : classes) {
            for (MessageKey key : MessageCatalogs.keys(type)) {
                String lowerKey = key.key().toLowerCase(Locale.ROOT);
                known.add(lowerKey);
            }
        }

        for (TemplateReference key : keys) {
            String lowerKey = key.value().toLowerCase(Locale.ROOT);
            if (!known.contains(lowerKey)) {
                report(problems,
                       Check.MISSING_MESSAGE,
                       key,
                       "Message '"
                            + key.value()
                            + "' not found in the catalogs of "
                            + classes.get(0).getElementName()
                            + " or the application catalog");
            }
        }
    }

    /// Keys every page and component has, lower-cased (Tapestry ignores case): Tapestry's own and
    /// those of the application catalog (`WEB-INF/app.properties`, any locale).
    private Set<String> globalKeys() throws CoreException {
        if (this.globalKeys == null) {
            this.globalKeys = new HashSet<>();
            for (IPackageFragmentRoot root : this.project.getPackageFragmentRoots()) {
                IPackageFragment fragment = root.getPackageFragment(CORE_CATALOG_PACKAGE);
                if (!fragment.exists()) {
                    continue;
                }

                for (Object resource : fragment.getNonJavaResources()) {
                    if (resource instanceof IJarEntryResource entry && entry.getName().equals(CORE_CATALOG)) {
                        try (InputStream in = entry.getContents()) {
                            byte[] bytes = in.readAllBytes();
                            String catalog = new String(bytes, StandardCharsets.UTF_8);
                            addKeys(catalog);
                        }
                        catch (IOException _) {
                            // no core keys then
                        }
                    }
                }
            }

            this.project.getProject().accept((IResourceVisitor) resource -> {
                if (resource instanceof IContainer container) {
                    return !container.isDerived() && !container.isTeamPrivateMember();
                }

                if (resource instanceof IFile file
                    && MessageCatalogs.isCatalog(file)
                    && WEB_INF.equals(file.getParent().getName())) {
                    String catalog = TemplateLocator.read(file, StandardCharsets.UTF_8.name());
                    addKeys(catalog);
                }

                return false;
            }, IResource.DEPTH_INFINITE, IResource.NONE);
        }

        return this.globalKeys;
    }

    private void addKeys(String catalog) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(catalog));
        }
        catch (IOException | IllegalArgumentException _) {
            // keep what was read
        }

        for (String key : properties.stringPropertyNames()) {
            String lowerKey = key.toLowerCase(Locale.ROOT);
            this.globalKeys.add(lowerKey);
        }
    }

    private Model model(IType type) throws CoreException {
        Model model = this.models.get(type);
        if (model == null) {
            Set<String> parameters = new HashSet<>();
            for (ComponentParameters.Parameter parameter : ComponentParameters.of(type, this.monitor)) {
                String lowerName = parameter.name().toLowerCase(Locale.ROOT);
                parameters.add(lowerName);
            }

            boolean informal = false;
            boolean implicitMixins = false;
            ITypeHierarchy hierarchy = type.newSupertypeHierarchy(this.monitor);
            for (IType current = type; current != null; current = hierarchy.getSuperclass(current)) {
                informal |= JavaSources.annotation(current.getAnnotations(), "SupportsInformalParameters") != null;
                for (IField field : current.getFields()) {
                    implicitMixins |= JavaSources.annotation(field.getAnnotations(), "Mixin") != null;
                }
            }

            model = new Model(parameters, informal, implicitMixins);
            this.models.put(type, model);
        }

        return model;
    }

    private static boolean hasNoReferences(IType type, String id) {
        try {
            return ComponentIds.references(type, id).isEmpty();
        }
        catch (CoreException _) {
            return false;
        }
    }

    /// Names built at runtime (`t:type="${…}"`) can't be checked.
    private static boolean isDynamic(String name) {
        return name.contains("$") || name.contains(":");
    }

    private void report(List<Problem> problems, Check check, TemplateReference reference, String message) {
        if (this.checks.contains(check)) {
            problems.add(new Problem(check, message, reference.offset(), reference.length()));
        }
    }

    /// Whether `project` uses Tapestry at all; other projects may have `.tml` files of their own.
    public static boolean usesTapestry(IJavaProject project) throws CoreException {
        return project.exists() && project.findType("org.apache.tapestry5.annotations.Parameter") != null;
    }

    /// The Java project of `file`, or `null`.
    public static IJavaProject javaProject(IFile file) {
        IJavaProject project = JavaCore.create(file.getProject());
        if (!project.exists()) {
            return null;
        }

        return project;
    }
}
