package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;

/// What to propose at a position in a template, independent of the editor:
///
/// - component names after `<t:` (`<t:layout.header>`), after a library prefix (`<lib:bar>`) and in
///   `t:type`
/// - mixin names in `t:mixins`
/// - page names in `page` of a PageLink and `t:page`
/// - message keys after `message:`, from the catalogs of the template's class
/// - parameters of the component whose tag the cursor is in (`<t:grid |`) or of the enclosing
///   component in a parameter block (`<p:|`)
/// - properties and methods in property expressions (`${user.|}`, `value="user.|"`), see
///   [PropertyExpression]
///
/// Names are proposed the way templates usually spell them: each segment starting lowercase
/// (`layout/header`), which Tapestry accepts as it ignores case.
public final class TemplateCompletion {

    /// @param replacement the text replacing `length` characters at `offset`
    /// @param cursor where the caret goes, relative to `offset`
    /// @param element the proposed [IType], [MessageKey], [ComponentParameters.Parameter] or
    ///            [PropertyPaths.Property]
    public record Candidate(String replacement,
                            String label,
                            String qualifier,
                            int offset,
                            int length,
                            int cursor,
                            Object element) {

        Candidate(String replacement, String qualifier, int offset, int length, Object element) {
            this(replacement, replacement, qualifier, offset, length, replacement.length(), element);
        }
    }

    private static final Pattern ELEMENT_PREFIX = Pattern.compile("<([\\w.\\-]+):$");

    /// Whether `candidate` still fits after the user typed `typed`: message keys (dotted, dashed)
    /// match anywhere, names at their start or the start of a segment.
    public static boolean accepts(Candidate candidate, String typed) {
        String text = candidate.label().toLowerCase(Locale.ROOT);
        String lowerTyped = typed.toLowerCase(Locale.ROOT);
        return switch (candidate.element()) {
            case MessageKey _ -> text.contains(lowerTyped);
            case ComponentParameters.Parameter _,PropertyPaths.Property _ -> text.startsWith(lowerTyped);
            default -> matches(text, typed);
        };
    }

    public static List<Candidate> compute(IFile template,
                                          String content,
                                          int offset,
                                          IProgressMonitor monitor) throws CoreException {
        TemplateReference reference = TemplateReference.completionAt(content, offset);
        IJavaProject project = JavaCore.create(template.getProject());
        if (!project.exists()) {
            return List.of();
        }
        if (reference == null) {
            ParameterReference parameter = ParameterReference.at(content, offset, true);
            if (parameter != null && offset >= parameter.offset()) {
                String typedParameter = content.substring(parameter.offset(), offset);
                return parameters(project, content, parameter, typedParameter, monitor);
            }
            PropertyExpression expression = PropertyExpression.at(content, offset, true);
            if (expression == null || offset < expression.offset()) {
                return List.of();
            }

            String typedProperty = content.substring(expression.offset(), offset);
            return properties(template, expression, typedProperty, monitor);
        }
        if (offset < reference.offset()) {
            return List.of();
        }
        String typed = content.substring(reference.offset(), offset);
        return switch (reference.kind()) {
            case COMPONENT -> componentNames(project, content, reference, typed, monitor);
            case MIXIN -> names(project, ComponentResolver.Kind.MIXIN, reference, typed, null, '/', monitor);
            case PAGE -> names(project, ComponentResolver.Kind.PAGE, reference, typed, null, '/', monitor);
            case MESSAGE -> messageKeys(template, reference, typed, monitor);
            case COMPONENT_ID -> List.of();
        };
    }

    /// Component names as an element name (`<t:layout.header>`) or as a `t:type` value
    /// (`layout/header`).
    private static List<Candidate> componentNames(IJavaProject project,
                                                  String content,
                                                  TemplateReference reference,
                                                  String typed,
                                                  IProgressMonitor monitor) throws CoreException {
        if (reference.isElementName(content)) {
            return elementNames(project, content, reference, typed, monitor);
        }

        return names(project, ComponentResolver.Kind.COMPONENT, reference, typed, null, '/', monitor);
    }

    /// `<t:` offers all components as `layout.header`; a library namespace (`<lib:`) only that
    /// library's, without its prefix.
    private static List<Candidate> elementNames(IJavaProject project,
                                                String content,
                                                TemplateReference reference,
                                                String typed,
                                                IProgressMonitor monitor) throws CoreException {
        int lookbehindStart = Math.max(0, reference.offset() - 100);
        String beforeName = content.substring(lookbehindStart, reference.offset());
        Matcher prefix = ELEMENT_PREFIX.matcher(beforeName);
        String library;
        if (prefix.find()) {
            TemplateReference.Namespaces namespaces = TemplateReference.Namespaces.of(content);
            String namespacePrefix = prefix.group(1);
            library = namespaces.library(namespacePrefix);
        }
        else {
            library = null;
        }

        return names(project, ComponentResolver.Kind.COMPONENT, reference, typed, library, '.', monitor);
    }

    private static List<Candidate> names(IJavaProject project,
                                         ComponentResolver.Kind kind,
                                         TemplateReference reference,
                                         String typed,
                                         String library,
                                         char separator,
                                         IProgressMonitor monitor) throws CoreException {
        List<Candidate> candidates = new ArrayList<>();
        for (ComponentNames.Name name : ComponentNames.all(project, kind, monitor)) {
            String logicalName = name.name();
            if (library != null) {
                String lowerLogicalName = logicalName.toLowerCase(Locale.ROOT);
                String libraryPath = library.toLowerCase(Locale.ROOT) + "/";
                if (!lowerLogicalName.startsWith(libraryPath)) {
                    continue;
                }
                logicalName = logicalName.substring(library.length() + 1);
            }

            String text = spell(logicalName, separator);
            if (matches(text, typed)) {
                IType type = name.type();
                candidates.add(new Candidate(text,
                                             type.getFullyQualifiedName(),
                                             reference.offset(),
                                             reference.length(),
                                             type));
            }
        }

        return candidates;
    }

    private static List<Candidate> messageKeys(IFile template,
                                               TemplateReference reference,
                                               String typed,
                                               IProgressMonitor monitor) throws CoreException {
        Map<String, MessageKey> keys = new LinkedHashMap<>();
        for (IType type : TemplateLocator.findClasses(template, monitor)) {
            for (MessageKey key : MessageCatalogs.keys(type)) {
                keys.putIfAbsent(key.key(), key);
            }
        }

        List<Candidate> candidates = new ArrayList<>();
        for (MessageKey key : keys.values()) {
            Candidate candidate = new Candidate(key.key(), key.value(), reference.offset(), reference.length(), key);
            if (accepts(candidate, typed)) {
                candidates.add(candidate);
            }
        }

        return candidates;
    }

    /// Parameters the tag doesn't have yet, required ones first. An attribute without a value yet
    /// becomes `name=""` with the caret between the quotes.
    private static List<Candidate> parameters(IJavaProject project,
                                              String content,
                                              ParameterReference reference,
                                              String typed,
                                              IProgressMonitor monitor) throws CoreException {
        List<IType> types =
            new ComponentResolver(project, monitor).resolve(ComponentResolver.Kind.COMPONENT, reference.component());
        if (types.isEmpty()) {
            return List.of();
        }

        int end = reference.offset() + reference.length();
        boolean hasValue;
        if (reference.block()) {
            hasValue = false;
        }
        else {
            int afterName = skipWhitespace(content, end);
            hasValue = content.startsWith("=", afterName);
        }

        String lowerTyped = typed.toLowerCase(Locale.ROOT);
        String lowerReferenceName = reference.name().toLowerCase(Locale.ROOT);
        List<Candidate> candidates = new ArrayList<>();
        for (ComponentParameters.Parameter parameter : ComponentParameters.of(types.get(0), monitor)) {
            String name = parameter.name();
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (!lowerName.startsWith(lowerTyped)
                || (reference.present().contains(lowerName) && !lowerName.equals(lowerReferenceName))) {
                continue;
            }

            String replacement;
            int cursor;
            if (reference.block() || hasValue) {
                replacement = name;
                cursor = name.length();
            }
            else {
                replacement = name + "=\"\"";
                cursor = name.length() + 2;
            }

            String declaringClass = parameter.field().getDeclaringType().getElementName();
            String qualifier;
            if (parameter.required()) {
                qualifier = declaringClass + " (required)";
            }
            else {
                qualifier = declaringClass;
            }

            candidates.add(new Candidate(replacement,
                                         name,
                                         qualifier,
                                         reference.offset(),
                                         reference.length(),
                                         cursor,
                                         parameter));
        }

        candidates.sort(Comparator.comparing(TemplateCompletion::isOptionalParameter)
                                  .thenComparing(candidate -> candidate.label().toLowerCase(Locale.ROOT)));
        return candidates;
    }

    /// For sorting required parameters first (`false` sorts before `true`).
    private static boolean isOptionalParameter(Candidate candidate) {
        ComponentParameters.Parameter parameter = (ComponentParameters.Parameter) candidate.element();
        return !parameter.required();
    }

    /// Properties and methods of the type the expression's path leads to; methods are inserted with
    /// `()`, the caret inside if they take arguments.
    private static List<Candidate> properties(IFile template,
                                              PropertyExpression expression,
                                              String typed,
                                              IProgressMonitor monitor) throws CoreException {
        IType type = PropertyPaths.context(template, expression, monitor);
        if (type == null) {
            return List.of();
        }

        String lowerTyped = typed.toLowerCase(Locale.ROOT);
        List<Candidate> candidates = new ArrayList<>();
        for (PropertyPaths.Property property : PropertyPaths.of(type, monitor)) {
            String lowerName = property.name().toLowerCase(Locale.ROOT);
            if (!lowerName.startsWith(lowerTyped)) {
                continue;
            }

            String replacement;
            if (property.method()) {
                replacement = property.name() + "()";
            }
            else {
                replacement = property.name();
            }

            boolean arguments = property.member() instanceof IMethod method && method.getNumberOfParameters() > 0;
            int cursor;
            if (arguments) {
                cursor = replacement.length() - 1;
            }
            else {
                cursor = replacement.length();
            }

            String declaringClass = property.member().getDeclaringType().getElementName();
            String qualifier = property.typeName() + " - " + declaringClass;
            candidates.add(new Candidate(replacement,
                                         replacement,
                                         qualifier,
                                         expression.offset(),
                                         expression.length(),
                                         cursor,
                                         property));
        }

        return candidates;
    }

    private static int skipWhitespace(String content, int from) {
        int position = from;
        while (position < content.length() && Character.isWhitespace(content.charAt(position))) {
            position++;
        }

        return position;
    }

    /// `admin/UserDetails` → `admin.userDetails` or `admin/userDetails`.
    static String spell(String logicalName, char separator) {
        StringBuilder text = new StringBuilder();
        for (String segment : logicalName.split("/")) {
            if (!text.isEmpty()) {
                text.append(separator);
            }

            text.append(Strings.decapitalize(segment));
        }

        return text.toString();
    }

    /// Prefix match on the whole name or any segment, so `head` finds `layout.header`.
    static boolean matches(String text, String typed) {
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerTyped = typed.toLowerCase(Locale.ROOT);
        if (lowerText.startsWith(lowerTyped)) {
            return true;
        }

        for (int i = 0; i < lowerText.length(); i++) {
            char c = lowerText.charAt(i);
            if ((c == '.' || c == '/' || c == '-') && lowerText.startsWith(lowerTyped, i + 1)) {
                return true;
            }
        }

        return false;
    }

    private TemplateCompletion() {
    }

}
