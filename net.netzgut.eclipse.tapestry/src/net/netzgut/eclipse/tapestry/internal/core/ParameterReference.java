package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/// A component parameter in a template: an attribute of a component's tag (`<t:grid source="…">`,
/// `<div t:type="grid" t:source="…">`) or a parameter block inside it (`<p:empty>`).
///
/// @param component the component's name as in [TemplateReference] (`grid`, `layout/Header`)
/// @param name the parameter name, empty while still being typed
/// @param present lower-cased names of the parameters the component's tag already has as attributes
/// @param block true for `<p:name>`, false for attributes
public record ParameterReference(String component,
                                 String name,
                                 int offset,
                                 int length,
                                 Set<String> present,
                                 boolean block) {

    /// Tapestry attributes on component tags that aren't parameters.
    private static final Set<String> RESERVED = Set.of("id", //
                                                       "type",
                                                       "mixins");

    /// The parameter at `position`, or `null`. With `incomplete` also one whose name is still empty
    /// (`<t:grid |`).
    public static ParameterReference at(String template, int position, boolean incomplete) {
        if (TemplateReference.inComment(template, position)) {
            return null;
        }

        int start = TemplateReference.tagStart(template, position);
        if (start < 0 || template.startsWith("</", start)) {
            return null;
        }

        TemplateReference.Tag tag = TemplateReference.Tag.parse(template, start);
        if (tag == null || position > tag.end()) {
            return null;
        }

        TemplateReference.Namespaces namespaces = TemplateReference.Namespaces.of(template);
        int nameEnd = tag.nameOffset() + tag.name().length();
        ParameterReference reference;
        if (position <= nameEnd) {
            reference = block(template, start, tag, namespaces);
        }
        else {
            reference = attribute(template, position, tag, namespaces);
        }

        if (reference == null || (reference.name().isEmpty() && !incomplete)) {
            return null;
        }

        return reference;
    }

    /// `<p:name`: a parameter of the innermost enclosing component.
    private static ParameterReference block(String template,
                                            int start,
                                            TemplateReference.Tag tag,
                                            TemplateReference.Namespaces namespaces) {
        String prefix = TemplateReference.prefix(tag.name());
        if (!namespaces.isParameter(prefix)) {
            return null;
        }

        TemplateReference.Tag component = enclosingComponent(template, start, namespaces);
        if (component == null) {
            return null;
        }

        String name = TemplateReference.localName(tag.name());
        String componentName = componentOf(component, namespaces);
        int nameOffset = tag.nameOffset() + tag.name().length() - name.length();
        Set<String> present = attributeNames(component);
        return new ParameterReference(componentName,
                                      name,
                                      nameOffset,
                                      name.length(),
                                      present,
                                      true);
    }

    /// An attribute name (`source`, `t:source`) of a component's tag; not in a value.
    private static ParameterReference attribute(String template,
                                                int position,
                                                TemplateReference.Tag tag,
                                                TemplateReference.Namespaces namespaces) {
        int nameEnd = tag.nameOffset() + tag.name().length();
        if (inQuotes(template, nameEnd, position)) {
            return null;
        }

        int tokenStart = position;
        while (tokenStart > 0 && isNameChar(template.charAt(tokenStart - 1))) {
            tokenStart--;
        }

        int tokenEnd = position;
        while (tokenEnd < template.length() && isNameChar(template.charAt(tokenEnd))) {
            tokenEnd++;
        }

        if (tokenStart == 0) {
            return null;
        }

        char charBeforeToken = template.charAt(tokenStart - 1);
        if (!Character.isWhitespace(charBeforeToken)) {
            return null;
        }

        String token = template.substring(tokenStart, tokenEnd);
        String prefix = TemplateReference.prefix(token);
        if (prefix != null && !namespaces.isTapestry(prefix)) {
            return null; // xml:lang, xmlns:…
        }

        String name = TemplateReference.localName(token);
        String lowerCaseName = name.toLowerCase(Locale.ROOT);
        if (RESERVED.contains(lowerCaseName)) {
            return null;
        }

        String component = componentOf(tag, namespaces);
        if (component == null) {
            return null;
        }

        Set<String> present = attributeNames(tag);
        return new ParameterReference(component,
                                      name,
                                      tokenEnd - name.length(),
                                      name.length(),
                                      present,
                                      false);
    }

    private static Set<String> attributeNames(TemplateReference.Tag tag) {
        return tag.attributes() //
                  .stream() //
                  .map(attr -> TemplateReference.localName(attr.name()).toLowerCase(Locale.ROOT)) //
                  .collect(Collectors.toSet());
    }

    /// The component a start tag declares, or `null` for plain HTML.
    static String componentOf(TemplateReference.Tag tag, TemplateReference.Namespaces namespaces) {
        for (TemplateReference reference : TemplateReference.references(tag, namespaces, false)) {
            if (reference.kind() == TemplateReference.Kind.COMPONENT) {
                return reference.value();
            }
        }

        return null;
    }

    /// The start tag of the innermost component open at `before`.
    private static TemplateReference.Tag enclosingComponent(String template,
                                                            int before,
                                                            TemplateReference.Namespaces namespaces) {
        record Open(TemplateReference.Tag tag, //
                    boolean component) {
        }

        Deque<Open> open = new ArrayDeque<>();
        for (int start = TemplateReference.nextTag(template, 0);
             start >= 0 && start < before;
             start = TemplateReference.nextTag(template, start + 1)) {
            TemplateReference.Tag tag = TemplateReference.Tag.parse(template, start);
            if (tag != null) {
                if (template.startsWith("</", start)) {
                    // Pop up to the matching start tag; unmatched end tags are ignored
                    if (open.stream().anyMatch(element -> element.tag().name().equalsIgnoreCase(tag.name()))) {
                        while (!open.pop().tag().name().equalsIgnoreCase(tag.name())) {
                            // discard elements left open
                        }
                    }
                }
                else if (tag.end() < template.length()
                         && template.charAt(tag.end()) == '>'
                         && template.charAt(tag.end() - 1) != '/') {
                    boolean isComponent = componentOf(tag, namespaces) != null;
                    open.push(new Open(tag, isComponent));
                }
            }
        }

        for (Open element : open) {
            if (element.component()) {
                return element.tag();
            }
        }

        return null;
    }

    private static boolean inQuotes(String template, int from, int position) {
        char quote = 0;
        for (int i = from; i < position; i++) {
            char c = template.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            }
            else if (c == '"' || c == '\'') {
                quote = c;
            }
        }

        return quote != 0;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ':';
    }
}
