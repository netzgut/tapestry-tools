package net.netzgut.eclipse.tapestry.internal.core;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/// Extracts component declarations from a Tapestry template. Lenient: HTML entities are tolerated,
/// and on malformed XML everything up to the error is returned, so completion keeps working while a
/// template is being edited.
public final class TemplateParser {

    private static final String LIBRARY_NAMESPACE_PREFIX = "tapestry-library:";

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

    // Everything but the five XML entities and character references, e.g. &nbsp; &copy;
    private static final Pattern HTML_ENTITY =
        Pattern.compile("&(?!(?:amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)\\w+;");

    private TemplateParser() {
    }

    public static List<ComponentUsage> parse(String template) {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);

        List<ComponentUsage> components = new ArrayList<>();

        try {
            String sanitized = HTML_ENTITY.matcher(template).replaceAll(" ");
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(sanitized));
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                    ComponentUsage component = component(reader);
                    if (component != null) {
                        components.add(component);
                    }
                }
            }
        }
        catch (XMLStreamException _) {
            // keep what we have so far
        }
        return components;
    }

    private static ComponentUsage component(XMLStreamReader reader) {
        String namespace = reader.getNamespaceURI();
        String localName = reader.getLocalName();
        boolean tapestryElement = namespace != null && TemplateReference.isTapestryNamespace(namespace);
        boolean libraryElement = namespace != null && namespace.startsWith(LIBRARY_NAMESPACE_PREFIX);

        String type = null;
        String id = tapestryAttribute(reader, "id");
        String mixins = tapestryAttribute(reader, "mixins");
        String event = null;

        if (tapestryElement) {
            if (STRUCTURAL_ELEMENTS.contains(localName)) {
                return null;
            }

            type = localName.replace('.', '/'); // <t:layout.header> is layout/Header
            // On component elements, parameters (including id, mixins, event) need no prefix
            if (id == null) {
                id = plainAttribute(reader, "id");
            }

            if (mixins == null) {
                mixins = plainAttribute(reader, "mixins");
            }

            event = plainAttribute(reader, "event");
        }
        else if (libraryElement) {
            String library = namespace.substring(LIBRARY_NAMESPACE_PREFIX.length());
            type = library + "/" + localName.replace('.', '/');
            if (id == null) {
                id = plainAttribute(reader, "id");
            }

            if (mixins == null) {
                mixins = plainAttribute(reader, "mixins");
            }

            event = plainAttribute(reader, "event");
        }
        else {
            type = tapestryAttribute(reader, "type");
            if (type == null && id == null) {
                return null; // plain HTML element
            }
            event = plainAttribute(reader, "event");
        }

        Set<String> explicitEvents = new LinkedHashSet<>();
        String literalEvent = literal(event);
        if (literalEvent != null) {
            explicitEvents.add(literalEvent);
        }

        String componentId = Strings.stripToNull(id);
        String componentType = Strings.stripToNull(type);
        List<String> mixinNames = mixins(mixins);
        return new ComponentUsage(componentId, componentType, mixinNames, explicitEvents);
    }

    /// `"autocomplete, zoneRefresh::before:x"` → `[autocomplete, zoneRefresh]`.
    private static List<String> mixins(String value) {
        if (value == null) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (String mixin : value.split(",")) {
            String name = mixin.strip();
            int constraints = name.indexOf("::");
            if (constraints >= 0) {
                name = name.substring(0, constraints);
            }

            if (!name.isEmpty()) {
                result.add(name);
            }
        }

        return result;
    }

    /// `"refresh"` and `"literal:refresh"` are literals; bindings like `prop:x` are not.
    private static String literal(String value) {
        if (value == null || value.isBlank() || value.contains("${")) {
            return null;
        }

        if (value.startsWith("literal:")) {
            return value.substring("literal:".length());
        }

        if (value.contains(":")) {
            return null;
        }

        return value;
    }

    private static String tapestryAttribute(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String namespace = reader.getAttributeNamespace(i);
            if (namespace != null
                && TemplateReference.isTapestryNamespace(namespace)
                && name.equalsIgnoreCase(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }

        return null;
    }

    private static String plainAttribute(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String namespace = reader.getAttributeNamespace(i);
            if ((namespace == null || namespace.isEmpty()) && name.equalsIgnoreCase(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }

        return null;
    }
}
