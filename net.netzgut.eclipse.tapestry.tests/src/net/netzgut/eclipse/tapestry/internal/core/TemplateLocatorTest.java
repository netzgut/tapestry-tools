package net.netzgut.eclipse.tapestry.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.osgi.service.prefs.BackingStoreException;

/// Runs against a real (headless) workspace.
class TemplateLocatorTest {

    private static TestProject shop;

    @BeforeAll
    static void createProject() throws CoreException {
        shop = TestProject.create("shop");

        shop.javaClass("com.example.pages.Index");
        shop.file("src/main/resources/com/example/pages/Index.tml", "<html/>");
        shop.file("src/main/resources/com/example/pages/Index_de.tml", "<html/>");
        shop.file("src/main/resources/com/example/pages/Index.properties", "");
        shop.file("src/main/resources/com/example/pages/Index.bak", ""); // ignored by default
        // template variants picked by a custom resource locator
        shop.file("src/main/resources/com/example/pages/Index_B5.tml", "<html/>");
        shop.file("src/main/resources/com/example/pages/Index_B5_de.tml", "<html/>");
        // looks like a variant of Index, but is the template of its own class
        shop.javaClass("com.example.pages.Index_Print");
        shop.file("src/main/resources/com/example/pages/Index_Print.tml", "<html/>");

        shop.javaClass("com.example.components.Layout");
        shop.file("src/main/resources/com/example/overrides/Layout_de.tml", "<html/>");

        shop.javaClass("com.example.components.Footer");
        shop.file("src/main/resources/com/example/overrides/com.example.components.Footer.tml", "<html/>");

        shop.javaClass("com.example.components.Header");
        shop.file("src/main/resources/com/example/custom/Header.tml", "<html/>");
    }

    @AfterAll
    static void deleteProject() throws CoreException {
        shop.close();
    }

    @Test
    void findsTemplatesInResourceFolder() throws CoreException {
        // ARRANGE
        IType index = shop.type("com.example.pages.Index");

        // ACT
        List<IFile> templates = TemplateLocator.findTemplates(index);
        IFile preferred = SwitchTargets.preferredTemplate(templates, "Index", "com.example.pages.Index");

        // ASSERT
        Set<String> templatePaths = paths(templates);
        assertEquals(Set.of("/shop/src/main/resources/com/example/pages/Index.tml",
                            "/shop/src/main/resources/com/example/pages/Index_de.tml",
                            "/shop/src/main/resources/com/example/pages/Index_B5.tml",
                            "/shop/src/main/resources/com/example/pages/Index_B5_de.tml"),
                     templatePaths);
        assertEquals("Index.tml", preferred.getName());
    }

    @Test
    void findsOverrideTemplatesBySimpleAndQualifiedName() throws CoreException {
        // ARRANGE
        IType layout = shop.type("com.example.components.Layout");
        IType footer = shop.type("com.example.components.Footer");

        // ACT
        List<IFile> layoutTemplates = TemplateLocator.findTemplates(layout);
        List<IFile> footerTemplates = TemplateLocator.findTemplates(footer);

        // ASSERT
        Set<String> layoutPaths = paths(layoutTemplates);
        assertEquals(Set.of("/shop/src/main/resources/com/example/overrides/Layout_de.tml"), layoutPaths);
        Set<String> footerPaths = paths(footerTemplates);
        assertEquals(Set.of("/shop/src/main/resources/com/example/overrides/com.example.components.Footer.tml"),
                     footerPaths);
    }

    @Test
    void findsAllCompanionFiles() throws CoreException {
        // ARRANGE
        IType index = shop.type("com.example.pages.Index");

        // ACT
        List<IFile> companions = RelatedFiles.find(index, RelatedFiles::isCompanion);

        // ASSERT
        Set<String> companionPaths = paths(companions);
        assertEquals(Set.of("/shop/src/main/resources/com/example/pages/Index.tml",
                            "/shop/src/main/resources/com/example/pages/Index_de.tml",
                            "/shop/src/main/resources/com/example/pages/Index_B5.tml",
                            "/shop/src/main/resources/com/example/pages/Index_B5_de.tml",
                            "/shop/src/main/resources/com/example/pages/Index.properties"),
                     companionPaths);
    }

    @Test
    void findsClassForTemplate() throws CoreException {
        // ACT & ASSERT
        assertEquals(List.of("com.example.pages.Index"),
                     classesFor("src/main/resources/com/example/pages/Index_de.tml"));
        assertEquals(List.of("com.example.pages.Index"),
                     classesFor("src/main/resources/com/example/pages/Index_B5_de.tml"));
        assertEquals(List.of("com.example.pages.Index_Print"),
                     classesFor("src/main/resources/com/example/pages/Index_Print.tml"));
    }

    @Test
    void findsClassForOverrideTemplate() throws CoreException {
        // ACT & ASSERT
        assertEquals(List.of("com.example.components.Layout"),
                     classesFor("src/main/resources/com/example/overrides/Layout_de.tml"));
        assertEquals(List.of("com.example.components.Footer"),
                     classesFor("src/main/resources/com/example/overrides/com.example.components.Footer.tml"));
    }

    @Test
    void collectsRelatedElementsInDisplayOrder() throws CoreException {
        // ARRANGE
        IType index = shop.type("com.example.pages.Index");
        IFile germanTemplate = shop.file("src/main/resources/com/example/pages/Index_de.tml");

        // ACT
        List<Object> classElements = RelatedElements.collect(index, new NullProgressMonitor());
        List<Object> templateElements = RelatedElements.collect(germanTemplate, new NullProgressMonitor());

        // ASSERT
        List<String> classNames = displayNames(classElements);
        assertEquals(List.of("Index.tml", "Index_B5.tml", "Index_B5_de.tml", "Index_de.tml", "Index.properties"),
                     classNames);
        List<String> templateNames = displayNames(templateElements);
        assertEquals(List.of("Index", "Index.tml", "Index_B5.tml", "Index_B5_de.tml", "Index.properties"),
                     templateNames);
    }

    @Test
    void honorsPreferences() throws CoreException, BackingStoreException {
        // ARRANGE
        IType header = shop.type("com.example.components.Header");
        IType layout = shop.type("com.example.components.Layout");
        IType index = shop.type("com.example.pages.Index");
        IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(TapestryPreferences.QUALIFIER);
        preferences.put(TapestryPreferences.OVERRIDE_FOLDERS, "custom");
        preferences.put(TapestryPreferences.IGNORED_EXTENSIONS, "");
        try {
            // ACT
            List<IFile> headerTemplates = TemplateLocator.findTemplates(header);
            List<IFile> layoutTemplates = TemplateLocator.findTemplates(layout);
            List<IFile> companions = RelatedFiles.find(index, RelatedFiles::isCompanion);

            // ASSERT
            Set<String> headerPaths = paths(headerTemplates);
            assertEquals(Set.of("/shop/src/main/resources/com/example/custom/Header.tml"), headerPaths);
            Set<String> layoutPaths = paths(layoutTemplates);
            assertEquals(Set.of(), layoutPaths);
            Set<String> companionPaths = paths(companions);
            assertTrue(companionPaths.contains("/shop/src/main/resources/com/example/pages/Index.bak"));
        }
        finally {
            preferences.clear();
        }
    }

    @Test
    void findsOverrideFoldersCreatedAfterFirstLookup() throws CoreException {
        // ARRANGE
        shop.javaClass("com.example.components.Sidebar");
        IType sidebar = shop.type("com.example.components.Sidebar");

        // ACT
        List<IFile> templatesBefore = TemplateLocator.findTemplates(sidebar);

        // ASSERT
        Set<String> pathsBefore = paths(templatesBefore);
        assertEquals(Set.of(), pathsBefore);

        // ARRANGE
        // a second, new override folder; the resource listener drops the cached folder list
        shop.file("src/main/resources/com/example/more/overrides/Sidebar.tml", "<html/>");

        // ACT
        List<IFile> templatesAfter = TemplateLocator.findTemplates(sidebar);

        // ASSERT
        Set<String> pathsAfter = paths(templatesAfter);
        assertEquals(Set.of("/shop/src/main/resources/com/example/more/overrides/Sidebar.tml"), pathsAfter);
    }

    @Test
    void asksWhenSeveralExactTemplatesExist() {
        // ARRANGE
        IFile first = shop.file("a/Index.tml");
        IFile second = shop.file("b/Index.tml");
        List<IFile> templates = List.of(first, second);

        // ACT
        IFile preferred = SwitchTargets.preferredTemplate(templates, "Index", "com.example.pages.Index");

        // ASSERT
        assertNull(preferred);
    }

    private static List<String> classesFor(String templatePath) throws CoreException {
        return TemplateLocator.findClasses(shop.file(templatePath), new NullProgressMonitor()) //
                              .stream() //
                              .map(IType::getFullyQualifiedName).sorted(Comparator.naturalOrder()) //
                              .toList();
    }

    private static List<String> displayNames(List<Object> elements) {
        return elements.stream() //
                       .map(element -> element instanceof IFile file ? file.getName()
                                                                     : ((IType) element).getElementName())
                       .toList();
    }

    private static Set<String> paths(List<IFile> files) {
        return files.stream() //
                    .map(f -> f.getFullPath().toString()) //
                    .collect(Collectors.toSet());
    }
}
