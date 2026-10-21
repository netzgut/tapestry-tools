package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// A reference from a template to Java code, with its position in the template text: component
/// types (`<t:layout>`, `t:type="layout"`), mixins (`t:mixins`), pages (`page="Index"`) and
/// component ids (`t:id`) and message keys (`${message:title}`, `title="message:title"`).
///
/// Templates are often invalid XML while being edited, so this is a lenient lexer over a single tag,
/// not a parser.
///
/// @param value the referenced name as Tapestry sees it (`layout/Header`, `frpc2/Bar`, `loginForm`)
public record TemplateReference(Kind kind, String value, int offset, int length) {

    public enum Kind {
        COMPONENT,
        MIXIN,
        PAGE,
        COMPONENT_ID,
        MESSAGE
    }

    private static final String TAPESTRY_NAMESPACE_HTTP = "http://tapestry.apache.org/schema/tapestry_5";
    private static final String TAPESTRY_NAMESPACE_HTTPS = "https://tapestry.apache.org/schema/tapestry_5";
    private static final String LIBRARY_NAMESPACE_PREFIX = "tapestry-library:";
    private static final String PARAMETER_NAMESPACE = "tapestry:parameter";
    private static final Pattern NAMESPACE = Pattern.compile("xmlns:([\\w.\\-]+)\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern NAME = Pattern.compile("[\\w:.\\-]+");
    /// Message bindings, in expansions and attribute values alike.
    private static final Pattern MESSAGE = Pattern.compile("(?<![\\w-])message:([\\w.\\-]*)");
    private static final int MESSAGE_SEARCH_RADIUS = 200;
    private static final Pattern ELEMENT_NAME_START = Pattern.compile("<[\\w.\\-]+:$");
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w:.\\-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    /// Elements in the Tapestry namespace that are template structure, not components.
    private static final Set<String> STRUCTURAL_ELEMENTS = Set.of("body", //
                                                                  "container",
                                                                  "block",
                                                                  "parameter",
                                                                  "content",
                                                                  "remove",
                                                                  "extend",
                                                                  "replace",
                                                                  "extension-point");

    public boolean contains(int position) {
        return position >= this.offset && position <= this.offset + this.length;
    }

    /// The reference at `position` in `template`, or `null`.
    public static TemplateReference at(String template, int position) {
        TemplateReference reference = at(template, position, false);
        if (reference == null) {
            return null;
        }

        if (reference.value().isEmpty()) {
            return null;
        }

        return reference;
    }

    /// Like [#at(String, int)], but also returns references still being typed, with an empty value:
    /// `<t:`, `page=""`, `message:`, `t:mixins="a, "`.
    public static TemplateReference completionAt(String template, int position) {
        return at(template, position, true);
    }

    /// True if this reference is an element name (`<t:layout>`), false if it is in an attribute
    /// or expansion.
    public boolean isElementName(String template) {
        int lookbehindStart = Math.max(0, this.offset - 100);
        String beforeName = template.substring(lookbehindStart, this.offset);
        return ELEMENT_NAME_START.matcher(beforeName).find();
    }

    private static TemplateReference at(String template, int position, boolean incomplete) {
        if (inComment(template, position)) {
            return null;
        }

        TemplateReference message = messageAt(template, position);
        if (message != null) {
            return message;
        }

        int start = tagStart(template, position);
        if (start < 0) {
            return null;
        }

        Tag tag = Tag.parse(template, start);
        if (tag == null || position > tag.end()) {
            return null;
        }

        Namespaces namespaces = Namespaces.of(template);
        List<TemplateReference> references = references(tag, namespaces, incomplete);
        for (TemplateReference reference : references) {
            if (reference.contains(position)) {
                return reference;
            }
        }

        return null;
    }

    /// All component ids declared in `template`, in document order.
    public static List<TemplateReference> componentIds(String template) {
        Namespaces namespaces = Namespaces.of(template);
        List<TemplateReference> ids = new ArrayList<>();
        for (int start = nextTag(template, 0); start >= 0; start = nextTag(template, start + 1)) {
            Tag tag = Tag.parse(template, start);
            if (tag != null) {
                for (TemplateReference reference : references(tag, namespaces, false)) {
                    if (reference.kind() == Kind.COMPONENT_ID) {
                        ids.add(reference);
                    }
                }
            }
        }

        return ids;
    }

    /// All non-empty message keys in `template` outside comments, in document order.
    static List<TemplateReference> messageKeys(String template) {
        List<TemplateReference> keys = new ArrayList<>();
        Matcher message = MESSAGE.matcher(template);
        while (message.find()) {
            String key = message.group(1);
            if (!key.isEmpty() && !inComment(template, message.start())) {
                keys.add(new TemplateReference(Kind.MESSAGE, key, message.start(1), key.length()));
            }
        }

        return keys;
    }

    private static TemplateReference messageAt(String template, int position) {
        Matcher message = MESSAGE.matcher(template);
        int searchStart = Math.max(0, position - MESSAGE_SEARCH_RADIUS);
        int searchEnd = Math.min(template.length(), position + MESSAGE_SEARCH_RADIUS);
        message.region(searchStart, searchEnd);
        while (message.find() && message.start() <= position) {
            if (position >= message.start(1) && position <= message.end(1)) {
                String key = message.group(1);
                return new TemplateReference(Kind.MESSAGE, key, message.start(1), key.length());
            }
        }

        return null;
    }

    /// The `<` of the next tag at or after `from`, skipping comments, or -1. Walk all tags with
    /// `for (int start = nextTag(template, 0); start >= 0; start = nextTag(template, start + 1))`.
    static int nextTag(String template, int from) {
        int start = template.indexOf('<', from);
        while (start >= 0 && template.startsWith("<!--", start)) {
            int commentEnd = template.indexOf("-->", start);
            if (commentEnd < 0) {
                return -1;
            }

            start = template.indexOf('<', commentEnd);
        }

        return start;
    }

    static boolean inComment(String template, int position) {
        int open = template.lastIndexOf("<!--", position);
        if (open < 0) {
            return false;
        }

        int close = template.indexOf("-->", open);
        return close < 0 || close + 3 > position;
    }

    /// The `<` of the tag containing `position`, or -1 if it is in text content.
    static int tagStart(String template, int position) {
        for (int i = Math.min(position, template.length() - 1); i >= 0; i--) {
            char c = template.charAt(i);
            if (c == '<') {
                if (template.startsWith("<!--", i)) {
                    return -1;
                }

                return i;
            }

            if (c == '>' && i < position) {
                return -1;
            }
        }

        return -1;
    }

    static List<TemplateReference> references(Tag tag, Namespaces namespaces, boolean incomplete) {
        List<TemplateReference> references = new ArrayList<>();
        String prefix = prefix(tag.name());
        String localName = localName(tag.name());
        String lowerLocalName = localName.toLowerCase(Locale.ROOT);
        boolean tapestryElement = namespaces.isTapestry(prefix) && !STRUCTURAL_ELEMENTS.contains(lowerLocalName);
        String library = namespaces.library(prefix);
        boolean componentElement = tapestryElement || library != null;

        if (componentElement) {
            String type = localName.replace('.', '/'); // <t:layout.header> is layout/Header
            int localOffset = tag.nameOffset() + tag.name().length() - localName.length();
            String componentName;
            if (library == null) {
                componentName = type;
            }
            else {
                componentName = library + "/" + type;
            }

            references.add(new TemplateReference(Kind.COMPONENT, componentName, localOffset, localName.length()));
        }

        boolean pageLink = componentElement && localName.equalsIgnoreCase("pagelink");
        for (Attribute attribute : tag.attributes()) {
            String attributePrefix = prefix(attribute.name());
            String name = localName(attribute.name()).toLowerCase(Locale.ROOT);
            boolean tapestryAttribute = namespaces.isTapestry(attributePrefix);
            // On component elements, parameters need no prefix
            boolean parameter = tapestryAttribute || (attributePrefix == null && componentElement);

            switch (name) {
                case "type" -> {
                    if (tapestryAttribute) {
                        TemplateReference component = attribute.reference(Kind.COMPONENT);
                        references.add(component);
                        pageLink |= attribute.value().equalsIgnoreCase("pagelink");
                    }
                }

                case "id" -> {
                    if (parameter) {
                        TemplateReference id = attribute.reference(Kind.COMPONENT_ID);
                        references.add(id);
                    }
                }

                case "mixins" -> {
                    if (parameter) {
                        List<TemplateReference> mixins = attribute.mixins(incomplete);
                        references.addAll(mixins);
                    }
                }
            }
        }

        // Separate pass: page="…" counts on a PageLink, whose t:type may come after it
        for (Attribute attribute : tag.attributes()) {
            String attributePrefix = prefix(attribute.name());
            String attributeName = localName(attribute.name());
            if (attributeName.equalsIgnoreCase("page")
                && (namespaces.isTapestry(attributePrefix) || (attributePrefix == null && pageLink))) {
                TemplateReference page = attribute.literalReference(Kind.PAGE, incomplete);
                if (page != null) {
                    references.add(page);
                }
            }
        }

        return references;
    }

    static String prefix(String qualifiedName) {
        int colon = qualifiedName.indexOf(':');
        if (colon < 0) {
            return null;
        }
        return qualifiedName.substring(0, colon);
    }

    static String localName(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.indexOf(':') + 1);
    }

    /// The Tapestry 5 template namespace of any schema version, e.g.
    /// `http://tapestry.apache.org/schema/tapestry_5_4.xsd`.
    static boolean isTapestryNamespace(String uri) {
        return uri.startsWith(TAPESTRY_NAMESPACE_HTTP) || uri.startsWith(TAPESTRY_NAMESPACE_HTTPS);
    }

    record Namespaces(Set<String> tapestry, Map<String, String> libraries, Set<String> parameters) {

        static Namespaces of(String template) {
            Set<String> tapestry = new HashSet<>();
            Map<String, String> libraries = new HashMap<>();
            Set<String> parameters = new HashSet<>();
            Matcher declaration = NAMESPACE.matcher(template);
            while (declaration.find()) {
                String prefix = declaration.group(1);
                String uri = declaration.group(2);
                if (isTapestryNamespace(uri)) {
                    tapestry.add(prefix);
                }
                else if (uri.startsWith(LIBRARY_NAMESPACE_PREFIX)) {
                    String library = uri.substring(LIBRARY_NAMESPACE_PREFIX.length());
                    libraries.put(prefix, library);
                }
                else if (uri.equals(PARAMETER_NAMESPACE)) {
                    parameters.add(prefix);
                }
            }

            // fragments without declarations
            if (tapestry.isEmpty()) {
                tapestry.add("t");
            }

            if (parameters.isEmpty()) {
                parameters.add("p");
            }

            return new Namespaces(tapestry, libraries, parameters);
        }

        boolean isParameter(String prefix) {
            return prefix != null && this.parameters.contains(prefix);
        }

        boolean isTapestry(String prefix) {
            return prefix != null && this.tapestry.contains(prefix);
        }

        String library(String prefix) {
            if (prefix == null) {
                return null;
            }

            return this.libraries.get(prefix);
        }
    }

    record Tag(String name, int nameOffset, List<Attribute> attributes, int end) {

        /// Parses the start or end tag beginning at `start` (`<`); `null` if there is none.
        static Tag parse(String template, int start) {
            int nameStart = start + 1;
            if (nameStart < template.length() && template.charAt(nameStart) == '/') {
                nameStart++;
            }

            Matcher name = NAME.matcher(template);
            name.region(nameStart, template.length());
            if (!name.lookingAt()) {
                return null; // <!DOCTYPE, <?xml, stray <
            }

            int end = end(template, name.end());
            List<Attribute> attributes = new ArrayList<>();
            Matcher attribute = ATTRIBUTE.matcher(template);
            attribute.region(name.end(), end);
            while (attribute.find()) {
                int group;
                if (attribute.group(2) != null) {
                    group = 2;
                }
                else {
                    group = 3;
                }

                attributes.add(new Attribute(attribute.group(1),
                                             attribute.group(group),
                                             attribute.start(group),
                                             attribute.start(1)));
            }

            return new Tag(name.group(), nameStart, attributes, end);
        }

        /// The closing `>`, ignoring those in quoted values, or the end of the text for
        /// unfinished tags.
        private static int end(String template, int from) {
            char quote = 0;
            for (int i = from; i < template.length(); i++) {
                char c = template.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                }
                else if (c == '"' || c == '\'') {
                    quote = c;
                }
                else if (c == '>') {
                    return i;
                }
                else if (c == '<') {
                    return i; // unfinished tag followed by the next one
                }
            }
            return template.length();
        }
    }

    record Attribute(String name, String value, int valueOffset, int nameOffset) {

        TemplateReference reference(Kind kind) {
            String trimmed = this.value.strip();
            int trimmedOffset = this.valueOffset + this.value.indexOf(trimmed);
            return new TemplateReference(kind, trimmed, trimmedOffset, trimmed.length());
        }

        /// `"Index"` and `"literal:Index"` are literals; bindings like `prop:target` are not.
        TemplateReference literalReference(Kind kind, boolean incomplete) {
            String trimmed = this.value.strip();
            int skip = 0;
            if (trimmed.startsWith("literal:")) {
                skip = "literal:".length();
            }
            else if (trimmed.contains(":") || trimmed.contains("${") || (trimmed.isEmpty() && !incomplete)) {
                return null;
            }

            String literal = trimmed.substring(skip);
            int literalOffset = this.valueOffset + this.value.indexOf(trimmed) + skip;
            return new TemplateReference(kind, literal, literalOffset, literal.length());
        }

        /// `"autocomplete, zoneRefresh::before:x"` -> one reference per mixin name; with
        /// `incomplete` also empty ones (`"autocomplete, "`).
        List<TemplateReference> mixins(boolean incomplete) {
            List<TemplateReference> mixins = new ArrayList<>();
            int position = 0;
            for (String entry : this.value.split(",", -1)) {
                String mixin = entry;
                int constraints = mixin.indexOf("::");
                if (constraints >= 0) {
                    mixin = mixin.substring(0, constraints);
                }

                String trimmed = mixin.strip();
                if (!trimmed.isEmpty() || incomplete) {
                    int leading = mixin.length() - mixin.stripLeading().length();
                    mixins.add(new TemplateReference(Kind.MIXIN,
                                                     trimmed,
                                                     this.valueOffset + position + leading,
                                                     trimmed.length()));
                }

                position += entry.length() + 1;
            }

            return mixins;
        }
    }
}
