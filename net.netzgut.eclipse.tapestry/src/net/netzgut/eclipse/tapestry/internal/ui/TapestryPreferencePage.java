package net.netzgut.eclipse.tapestry.internal.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.dialogs.PreferenceLinkArea;
import org.eclipse.ui.preferences.IWorkbenchPreferenceContainer;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import net.netzgut.eclipse.tapestry.internal.core.TapestryConventions;
import net.netzgut.eclipse.tapestry.internal.core.TapestryPreferences;

/// Preferences > Tapestry. A [FieldEditorPreferencePage] binds each field editor to a preference
/// key and handles loading, "Restore Defaults" and "Apply" on its own; we only supply the store and
/// the editors.
public class TapestryPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    private static final String CONTENT_ASSIST_PAGE = "org.eclipse.jdt.ui.preferences.CodeAssistPreferenceAdvanced";

    private static final String[][] SEVERITIES = { { "Error", TapestryPreferences.ERROR },
                                                   { "Warning", TapestryPreferences.WARNING },
                                                   { "Ignore", TapestryPreferences.IGNORE } };

    private final List<Group> groups = new ArrayList<>();

    public TapestryPreferencePage() {
        super(GRID);
        setDescription("Settings for navigating, refactoring, completing and validating Tapestry classes and their resources.");
    }

    @Override
    public void init(IWorkbench workbench) {
        // Writes go to the instance scope, defaults come from TapestryPreferences.Initializer
        setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE, TapestryPreferences.QUALIFIER));
    }

    @Override
    protected void createFieldEditors() {
        Composite relatedFiles = group("Related Files");
        addField(new NameListFieldEditor(TapestryPreferences.OVERRIDE_FOLDERS,
                                         "&Override folders:",
                                         "folder name",
                                         relatedFiles));
        hint(relatedFiles,
             "Folders in any source folder containing templates and messages of components living elsewhere, "
                           + "e.g. overrides/Layout.tml or overrides/com.example.components.Layout.tml.");
        addField(new NameListFieldEditor(TapestryPreferences.IGNORED_EXTENSIONS,
                                         "&Ignored extensions:",
                                         "extension",
                                         relatedFiles));
        hint(relatedFiles, "Files named like a class but not considered its resources, e.g. backups.");

        Composite contentAssist = group("Content Assist");
        addField(new BooleanFieldEditor(TapestryPreferences.PROPOSE_EVENT_HANDLERS,
                                        "&Event handlers for template components and page activation",
                                        contentAssist));
        addField(new BooleanFieldEditor(TapestryPreferences.PROPOSE_LIFECYCLE_METHODS,
                                        "&Render phase and page lifecycle methods",
                                        contentAssist));
        addField(new BooleanFieldEditor(TapestryPreferences.PROPOSE_MESSAGE_KEYS,
                                        "&Message keys in messages.get(\"…\")",
                                        contentAssist));
        addField(new BooleanFieldEditor(TapestryPreferences.PROPOSE_TEMPLATE_NAMES,
                                        "&Templates: components, mixins, pages and message keys",
                                        contentAssist));
        addField(new BooleanFieldEditor(TapestryPreferences.ANNOTATE_HANDLERS,
                                        "&Annotate generated methods (@OnEvent, @SetupRender, …)",
                                        contentAssist));
        hint(contentAssist, "Generated methods keep the conventional name either way.");

        PreferenceLinkArea link =
            new PreferenceLinkArea(contentAssist,
                                   SWT.NONE,
                                   CONTENT_ASSIST_PAGE,
                                   "All of them can also be turned off as \"Tapestry Proposals\" on the <a>''{0}''</a> page.",
                                   (IWorkbenchPreferenceContainer) getContainer(),
                                   null);
        GridDataFactory.fillDefaults().span(2, 1).indent(0, convertVerticalDLUsToPixels(4))
                       .hint(convertWidthInCharsToPixels(60), SWT.DEFAULT).applyTo(link.getControl());

        Composite templates = group("Templates");
        addField(new BooleanFieldEditor(TapestryPreferences.TEMPLATE_HOVER,
                                        "Show &hover information for components, parameters and properties",
                                        templates));
        severity(TapestryPreferences.VALIDATE_COMPONENTS, "Unknown &component types and mixins:", templates);
        severity(TapestryPreferences.VALIDATE_PAGES, "Unknown &pages:", templates);
        severity(TapestryPreferences.VALIDATE_PARAMETERS, "Parameters the component doe&sn't take:", templates);
        severity(TapestryPreferences.VALIDATE_MESSAGES, "Message &keys in no catalog:", templates);
        severity(TapestryPreferences.VALIDATE_COMPONENT_IDS, "Component i&ds without field or handler:", templates);
        hint(templates,
             "Templates are checked when saved, and all of a project's templates when its classes or message "
                        + "catalogs change. Keys in catalogs contributed by modules aren't known.");
    }

    private void severity(String key, String label, Composite parent) {
        addField(new ComboFieldEditor(key, label, SEVERITIES, parent));
    }

    /// Field editors set the layout of their parent and later adapt to the column count of the
    /// page's grid ([#adjustGridLayout()]), which knows nothing about nested groups. So groups get
    /// their two-column grid back there.
    private Composite group(String title) {
        Group group = new Group(getFieldEditorParent(), SWT.NONE);
        group.setText(title);
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).indent(0, 0).applyTo(group);
        this.groups.add(group);
        return group;
    }

    @Override
    protected void adjustGridLayout() {
        super.adjustGridLayout();
        for (Group group : this.groups) {
            GridLayoutFactory.swtDefaults().numColumns(2).applyTo(group);
        }
    }

    private void hint(Composite parent, String text) {
        Label label = new Label(parent, SWT.WRAP);
        label.setText(text);
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(convertWidthInCharsToPixels(60), SWT.DEFAULT)
                       .indent(0, 0).applyTo(label);
    }

    /// A comma separated list of single path segments.
    private static final class NameListFieldEditor extends StringFieldEditor {

        private final String kind;

        NameListFieldEditor(String key, String label, String kind, Composite parent) {
            super(key, label, parent);
            this.kind = kind;
            setEmptyStringAllowed(true);
        }

        @Override
        protected boolean doCheckState() {
            String value = getStringValue();
            for (String name : TapestryConventions.splitList(value)) {
                if (!TapestryConventions.isValidName(name)) {
                    setErrorMessage("Invalid " + this.kind + ": " + name);
                    return false;
                }
            }
            return true;
        }
    }
}
