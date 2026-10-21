package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// A segment of a property expression in a template, with the path leading to it:
/// `user.address.city` with the cursor in `address` has the path `[user]` and the name `address`.
///
/// Expressions are found in expansions (`${user.name}`, `${prop:user.name}`) and in parameter values
/// of component tags (`value="user.name"`, `value="prop:user.name"`). Without a `prop:` prefix, a
/// parameter value is only an expression if the parameter's default binding prefix is `prop`, which
/// needs the component class: then `component` and `parameter` are set and the caller checks it.
///
/// Only the dereference chain around the cursor is looked at (`a.b?.c().d`), so it also works inside
/// method arguments, `!`, lists and ranges.
///
/// @param path the segments before `name`, without `()` and arguments; `this` is left out
/// @param component the component whose `parameter` takes the expression, if it isn't
///            explicitly `prop:`
public record PropertyExpression(List<String> path,
                                 String name,
                                 int offset,
                                 int length,
                                 String component,
                                 String parameter) {

    private static final Pattern BINDING_PREFIX = Pattern.compile("^\\s*([a-zA-Z][\\w-]*):");
    private static final String PROP = "prop";
    private static final Set<String> KEYWORDS = Set.of("null", //
                                                       "true",
                                                       "false",
                                                       "this");
    private static final Set<String> RESERVED_ATTRIBUTES = Set.of("id", //
                                                                  "type",
                                                                  "mixins");
    private static final int MAX_EXPANSION_LENGTH = 500;

    /// True if the parameter's binding prefix still has to be checked.
    public boolean needsPropertyParameter() {
        return this.parameter != null;
    }

    /// The expression segment at `position`, or `null`; with `incomplete` also an empty
    /// one (`${user.|}`).
    public static PropertyExpression at(String template, int position, boolean incomplete) {
        if (TemplateReference.inComment(template, position)) {
            return null;
        }

        PropertyExpression expression = inExpansion(template, position);
        if (expression == null) {
            expression = inParameterValue(template, position);
        }

        if (expression == null) {
            return null;
        }

        if (expression.name().isEmpty() && !incomplete) {
            return null;
        }

        return expression;
    }

    private static PropertyExpression inExpansion(String template, int position) {
        int open = template.lastIndexOf("${", position - 1);
        if (open < 0 || position - open > MAX_EXPANSION_LENGTH) {
            return null;
        }

        int close = template.indexOf('}', open);
        if (close >= 0 && close < position) {
            return null;
        }

        int expressionEnd;
        if (close < 0) {
            expressionEnd = position;
        }
        else {
            expressionEnd = close;
        }

        int start = afterPropPrefix(template, open + 2, expressionEnd);
        if (start < 0) {
            return null;
        }
        return chain(template, start, position, null, null);
    }

    private static PropertyExpression inParameterValue(String template, int position) {
        int tagStart = TemplateReference.tagStart(template, position);
        if (tagStart < 0) {
            return null;
        }

        TemplateReference.Tag tag = TemplateReference.Tag.parse(template, tagStart);
        if (tag == null || position > tag.end()) {
            return null;
        }

        TemplateReference.Namespaces namespaces = TemplateReference.Namespaces.of(template);
        for (TemplateReference.Attribute attribute : tag.attributes()) {
            int valueStart = attribute.valueOffset();
            if (position < valueStart || position > valueStart + attribute.value().length()) {
                continue;
            }

            String prefix = TemplateReference.prefix(attribute.name());
            String name = TemplateReference.localName(attribute.name());
            String lowerCaseName = name.toLowerCase(Locale.ROOT);
            String component = ParameterReference.componentOf(tag, namespaces);
            if (component == null
                || (prefix != null && !namespaces.isTapestry(prefix))
                || RESERVED_ATTRIBUTES.contains(lowerCaseName)) {
                return null;
            }

            int valueEnd = valueStart + attribute.value().length();
            Matcher binding = BINDING_PREFIX.matcher(template).region(valueStart, valueEnd);
            if (binding.lookingAt()) {
                int start = afterPropPrefix(template, valueStart, valueEnd);
                if (start < 0) {
                    return null;
                }

                return chain(template, start, position, null, null);
            }

            return chain(template, valueStart, position, component, name);
        }

        return null;
    }

    /// The start of the expression after an optional `prop:`; -1 for other binding prefixes.
    private static int afterPropPrefix(String template, int start, int end) {
        Matcher binding = BINDING_PREFIX.matcher(template).region(start, end);
        if (!binding.lookingAt()) {
            return start;
        }

        String prefix = binding.group(1);
        if (!prefix.equalsIgnoreCase(PROP)) {
            return -1;
        }

        return binding.end();
    }

    /// The dereference chain ending in the identifier at `position`, not reaching before `start`.
    private static PropertyExpression chain(String template,
                                            int start,
                                            int position,
                                            String component,
                                            String parameter) {
        int nameStart = position;
        while (nameStart > start && Character.isJavaIdentifierPart(template.charAt(nameStart - 1))) {
            nameStart--;
        }

        int nameEnd = position;
        while (nameEnd < template.length() && Character.isJavaIdentifierPart(template.charAt(nameEnd))) {
            nameEnd++;
        }

        String name = template.substring(nameStart, nameEnd);
        String lowerCaseName = name.toLowerCase(Locale.ROOT);
        if (!name.isEmpty()
            && (!Character.isJavaIdentifierStart(name.charAt(0)) || KEYWORDS.contains(lowerCaseName))) {
            return null; // number, keyword
        }

        List<String> path = new ArrayList<>();
        int i = nameStart;
        while (i > start && template.charAt(i - 1) == '.') {
            i--;
            if (i > start && template.charAt(i - 1) == '?') {
                i--;
            }

            if (i > start && template.charAt(i - 1) == '.') {
                return null; // range operator ..
            }

            if (i > start && template.charAt(i - 1) == ')') {
                i = matchingParenthesis(template, start, i - 1);
                if (i < 0) {
                    return null;
                }
            }

            int segmentEnd = i;
            while (i > start && Character.isJavaIdentifierPart(template.charAt(i - 1))) {
                i--;
            }

            String segment = template.substring(i, segmentEnd);
            if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))) {
                return null;
            }

            path.add(0, segment);
        }

        if (!path.isEmpty() && path.get(0).equalsIgnoreCase("this")) {
            path.remove(0);
        }

        return new PropertyExpression(List.copyOf(path), name, nameStart, name.length(), component, parameter);
    }

    /// The position of the `(` matching the `)` at `close`, or -1.
    private static int matchingParenthesis(String template, int start, int close) {
        int depth = 0;
        for (int i = close; i >= start; i--) {
            char c = template.charAt(i);
            if (c == ')') {
                depth++;
            }
            else if (c == '(' && --depth == 0) {
                return i;
            }
        }

        return -1;
    }
}
