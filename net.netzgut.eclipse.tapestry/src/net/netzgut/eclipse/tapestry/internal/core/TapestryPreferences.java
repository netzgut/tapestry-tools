package net.netzgut.eclipse.tapestry.internal.core;

import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;

/// Workspace-wide settings, edited on Preferences > Tapestry.
///
/// Eclipse preferences live in layered scopes: values the user changed are stored in the instance
/// scope (`.metadata/.plugins/org.eclipse.core.runtime/.settings/<bundle>.prefs`), and lookups fall
/// back to the default scope, which [Initializer] fills on first access.
public final class TapestryPreferences {

    public static final String QUALIFIER = "net.netzgut.eclipse.tapestry";

    /// Folders containing templates/messages that replace those of a component living elsewhere,
    /// named either `<SimpleName>.tml` or `<fully.qualified.Name>.tml`.
    public static final String OVERRIDE_FOLDERS = "overrideFolders";

    /// Extensions of files named like a class that should not be treated as its resources,
    /// e.g. backups.
    public static final String IGNORED_EXTENSIONS = "ignoredExtensions";

    /// Generate `@OnEvent`, `@SetupRender`… on proposed methods in addition to the
    /// conventional name.
    public static final String ANNOTATE_HANDLERS = "annotateHandlers";

    /// Which kinds of content assist proposals are offered; in Java, the JDT category switches
    /// them all.
    public static final String PROPOSE_EVENT_HANDLERS    = "proposeEventHandlers";
    public static final String PROPOSE_LIFECYCLE_METHODS = "proposeLifecycleMethods";
    public static final String PROPOSE_MESSAGE_KEYS      = "proposeMessageKeys";
    public static final String PROPOSE_TEMPLATE_NAMES    = "proposeTemplateNames";

    /// Javadoc and parameters of components, parameters and properties when hovering them
    /// in templates.
    public static final String TEMPLATE_HOVER = "templateHover";

    /// Severity of each template check: [#ERROR], [#WARNING] or [#IGNORE].
    public static final String VALIDATE_COMPONENTS    = "validation.unknownComponents";
    public static final String VALIDATE_PAGES         = "validation.unknownPages";
    public static final String VALIDATE_PARAMETERS    = "validation.unknownParameters";
    public static final String VALIDATE_MESSAGES      = "validation.missingMessages";
    public static final String VALIDATE_COMPONENT_IDS = "validation.unusedComponentIds";

    /// Common prefix of the validation keys, to notice changes.
    public static final String VALIDATION_PREFIX = "validation.";

    public static final String ERROR   = "error";
    public static final String WARNING = "warning";
    public static final String IGNORE  = "ignore";

    public static boolean isEnabled(String booleanKey) {
        return Platform.getPreferencesService().getBoolean(QUALIFIER, booleanKey, true, null);
    }

    /// [#ERROR], [#WARNING] or [#IGNORE].
    public static String severity(String key) {
        return Platform.getPreferencesService().getString(QUALIFIER, key, WARNING, null);
    }

    public static boolean annotateHandlers() {
        return Platform.getPreferencesService().getBoolean(QUALIFIER, ANNOTATE_HANDLERS, true, null);
    }

    public static List<String> overrideFolders() {
        String value = get(OVERRIDE_FOLDERS);
        return TapestryConventions.splitList(value);
    }

    public static List<String> ignoredExtensions() {
        String value = get(IGNORED_EXTENSIONS);
        return TapestryConventions.splitList(value);
    }

    private static String get(String key) {
        // null contexts: instance scope, then default scope
        return Platform.getPreferencesService().getString(QUALIFIER, key, "", null);
    }

    /// Registered in plugin.xml; called once, the first time the default scope of our bundle
    /// is read.
    public static class Initializer extends AbstractPreferenceInitializer {

        @Override
        public void initializeDefaultPreferences() {
            IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(QUALIFIER);
            defaults.put(OVERRIDE_FOLDERS, "overrides");
            defaults.put(IGNORED_EXTENSIONS, "bak, orig");
            defaults.putBoolean(ANNOTATE_HANDLERS, true);
            defaults.putBoolean(PROPOSE_EVENT_HANDLERS, true);
            defaults.putBoolean(PROPOSE_LIFECYCLE_METHODS, true);
            defaults.putBoolean(PROPOSE_MESSAGE_KEYS, true);
            defaults.putBoolean(PROPOSE_TEMPLATE_NAMES, true);
            defaults.putBoolean(TEMPLATE_HOVER, true);
            defaults.put(VALIDATE_COMPONENTS, WARNING);
            defaults.put(VALIDATE_PAGES, WARNING);
            defaults.put(VALIDATE_PARAMETERS, WARNING);
            defaults.put(VALIDATE_MESSAGES, WARNING);
            // A component id needs neither a field nor a handler
            defaults.put(VALIDATE_COMPONENT_IDS, IGNORE);
        }
    }

    private TapestryPreferences() {
    }

}
