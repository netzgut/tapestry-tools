package net.netzgut.eclipse.tapestry.internal.core;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/// Events of Tapestry's built-in components and mixins.
///
/// `@Events` has `RetentionPolicy.SOURCE`, so it is not visible in tapestry-core.jar; this table was
/// extracted from Tapestry 5.9.1 sources. Internal events are left out.
///
/// Field events come from `FieldValidationSupport`: every field fires `validate`, text fields
/// (`AbstractTextField`) also `toClient` and `parseClient`.
public final class CoreEvents {

    public static final String CORE_COMPONENTS_PACKAGE = "org.apache.tapestry5.corelib.components";
    public static final String CORE_MIXINS_PACKAGE     = "org.apache.tapestry5.corelib.mixins";

    private static final List<String> FORM = List.of("prepareForRender",
                                                     "prepare",
                                                     "prepareForSubmit",
                                                     "validate",
                                                     "submit",
                                                     "failure",
                                                     "success",
                                                     "canceled");

    private static final List<String> FIELD = List.of("validate");

    private static final List<String> TEXT_FIELD = List.of("toClient", "validate", "parseClient");

    // Keys are lower case: component names are case-insensitive
    // Keys are lower case: component names are case-insensitive
    private static final Map<String, List<String>> COMPONENTS =
        Map.ofEntries(Map.entry("actionlink", List.of("action")),
                      Map.entry("ajaxformloop", List.of("addRow", "removeRow")),
                      // BeanEditForm re-triggers the events of its inner Form
                      Map.entry("beaneditform", FORM),
                      Map.entry("checkbox", FIELD),
                      Map.entry("checklist", FIELD),
                      Map.entry("datefield", FIELD),
                      Map.entry("form", FORM),
                      Map.entry("html5datefield", FIELD),
                      Map.entry("linksubmit", List.of("selected")),
                      Map.entry("loop", List.of("synchronizeValues")),
                      Map.entry("palette", FIELD),
                      Map.entry("passwordfield", TEXT_FIELD),
                      Map.entry("progressivedisplay", List.of("progressiveDisplay")),
                      Map.entry("radiogroup", FIELD),
                      Map.entry("select", List.of("validate", "valueChanged")),
                      Map.entry("submit", List.of("selected")),
                      Map.entry("submitnotifier", List.of("beginSubmit", "afterSubmit")),
                      Map.entry("textarea", TEXT_FIELD),
                      Map.entry("textfield", TEXT_FIELD),
                      Map.entry("tree", List.of("nodeSelected", "nodeUnselected")));

    private static final Map<String, List<String>> MIXINS = Map.of("autocomplete",
                                                                   List.of("provideCompletions"),
                                                                   "rendernotification",
                                                                   List.of("beginRender", "afterRender"),
                                                                   "zonerefresh",
                                                                   List.of("refresh"));

    /// Events whose handlers typically return nothing; all others may return a navigation target.
    private static final Set<String> VOID_EVENTS = Set.of("activate",
                                                          "prepareforrender",
                                                          "prepare",
                                                          "prepareforsubmit",
                                                          "validate",
                                                          "synchronizevalues",
                                                          "beginrender",
                                                          "afterrender",
                                                          "beginsubmit",
                                                          "aftersubmit");

    /// Events of a core component by name (`form`, `core/Form`), or an empty list.
    public static List<String> componentEvents(String name) {
        String key = key(name);
        return COMPONENTS.getOrDefault(key, List.of());
    }

    public static List<String> mixinEvents(String name) {
        String key = key(name);
        return MIXINS.getOrDefault(key, List.of());
    }

    /// The return type of a generated handler: `void`, `String` for `toClient` (Tapestry rejects
    /// anything else), otherwise `Object` for a navigation target or value.
    public static String returnType(String event) {
        String key = event.toLowerCase(Locale.ROOT);
        if (VOID_EVENTS.contains(key)) {
            return "void";
        }

        if (key.equals("toclient")) {
            return "String";
        }

        return "Object";
    }

    private static String key(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (key.startsWith("core/")) {
            return key.substring("core/".length());
        }

        return key;
    }

    private CoreEvents() {
    }
}
