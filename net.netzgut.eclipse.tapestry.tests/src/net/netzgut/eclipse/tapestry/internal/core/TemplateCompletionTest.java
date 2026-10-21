package net.netzgut.eclipse.tapestry.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.netzgut.eclipse.tapestry.internal.core.ComponentResolver.Kind;

class TemplateCompletionTest {

    private static final String HEADER = """
        <html xmlns:t="http://tapestry.apache.org/schema/tapestry_5_4.xsd"
              xmlns:lib="tapestry-library:frpc2">
        """;

    private static TestProject app;
    private static IFile       template;

    @BeforeAll
    static void createProject() throws CoreException {
        app = TestProject.create("completion");
        // Stand-ins for the Tapestry jar
        app.file("src/main/java/org/apache/tapestry5/services/LibraryMapping.java", """
            package org.apache.tapestry5.services;
            public class LibraryMapping { public LibraryMapping(String prefix, String rootPackage) {} }
            """);
        app.javaClass("org.apache.tapestry5.corelib.components.PageLink");
        app.file("src/main/java/com/example/commons/CommonsModule.java", """
            package com.example.commons;
            import org.apache.tapestry5.services.LibraryMapping;
            public class CommonsModule {
                void contribute(java.util.List<LibraryMapping> configuration) {
                    configuration.add(new LibraryMapping("frpc2", "com.example.commons"));
                }
            }
            """);
        app.javaClass("com.example.commons.components.Bar");
        app.javaClass("com.example.app.components.layout.LayoutHeader");
        app.javaClass("com.example.app.components.Layout");
        app.file("src/main/java/com/example/app/components/AbstractField.java",
                 "package com.example.app.components; public abstract class AbstractField {}");
        app.javaClass("com.example.app.mixins.Confirm");
        app.javaClass("com.example.app.pages.admin.Index");
        app.javaClass("com.example.app.pages.admin.AdminUsers");
        app.javaClass("com.example.app.pages.ProductPage");
        app.javaClass("com.example.app.pages.Login");
        app.file("src/main/resources/com/example/app/pages/Login.properties",
                 "title=Sign in\nlogin.failed=Wrong password\n");
        template = app.file("src/main/resources/com/example/app/pages/Login.tml", "");
    }

    @AfterAll
    static void deleteProject() throws CoreException {
        app.close();
    }

    @Test
    void listsNamesLikeTapestry() throws CoreException {
        // ACT
        List<String> components = ComponentNames.all(app.javaProject, Kind.COMPONENT, new NullProgressMonitor())
                                                .stream().map(ComponentNames.Name::name).toList();
        List<String> pages = ComponentNames.all(app.javaProject, Kind.PAGE, new NullProgressMonitor()).stream()
                                           .map(ComponentNames.Name::name).toList();

        // ASSERT
        assertEquals(List.of("frpc2/Bar", "Layout", "layout/Header", "PageLink"), components);
        assertEquals(List.of("admin/Index", "admin/Users", "Login", "Product"), pages);
    }

    @Test
    void proposesComponentElements() throws CoreException {
        // ACT & ASSERT
        assertEquals(List.of("frpc2.bar", "layout", "layout.header", "pageLink"), replacements("<t:|"));
        assertEquals(List.of("layout.header"), replacements("<t:head|"));
        assertEquals(List.of("bar"), replacements("<lib:|"));
    }

    @Test
    void proposesComponentTypesMixinsAndPages() throws CoreException {
        // ACT & ASSERT
        assertEquals(List.of("layout", "layout/header"), replacements("<div t:type=\"lay|\"/>"));
        assertEquals(List.of("confirm"), replacements("<t:pageLink t:mixins=\"x, |\"/>"));
        assertEquals(List.of("admin/index", "admin/users"), replacements("<t:pageLink page=\"adm|\"/>"));
        assertEquals(List.of("product"), replacements("<a t:page=\"literal:p|\"/>"));
    }

    @Test
    void proposesMessageKeysOfTheTemplatesClass() throws CoreException {
        // ACT & ASSERT
        assertEquals(List.of("login.failed", "title"), replacements("${message:|}"));
        assertEquals(List.of("login.failed"), replacements("<p title=\"message:fail|\"/>"));
    }

    @Test
    void replacesTheWholeName() throws CoreException {
        // ARRANGE
        String text = HEADER + "<div t:type=\"lay|out\"/>";
        int offset = text.indexOf('|');
        String content = text.replace("|", "");

        // ACT
        TemplateCompletion.Candidate candidate =
            TemplateCompletion.compute(template, content, offset, new NullProgressMonitor()).get(0);

        // ASSERT
        assertEquals(offset - 3, candidate.offset());
        assertEquals(6, candidate.length());
    }

    @ParameterizedTest
    @ValueSource(
        strings = { "<div class=\"lay|\"/>", //
                    "<!-- <t:| -->",
                    "<t:layout t:id=\"|\"/>" })
    void ignoresOtherPlaces(String body) throws CoreException {
        // ACT
        List<String> result = replacements(body);

        // ASSERT
        assertEquals(List.of(), result);
    }

    @Test
    void cmdClickStillNeedsAName() {
        // ACT
        TemplateReference emptyPage = TemplateReference.at("<t:pageLink page=\"\"/>", 18);
        TemplateReference emptyMessage = TemplateReference.at("${message:}", 10);
        TemplateReference completion = TemplateReference.completionAt("<t:pageLink page=\"\"/>", 18);

        // ASSERT
        assertNull(emptyPage);
        assertNull(emptyMessage);
        assertEquals(TemplateReference.Kind.PAGE, completion.kind());
    }

    @Test
    void spellsAndMatchesNames() {
        // ACT & ASSERT
        assertEquals("admin.userDetails", TemplateCompletion.spell("admin/UserDetails", '.'));
        assertEquals("frpc2/HTMLEditor", TemplateCompletion.spell("frpc2/HTMLEditor", '/'));

        assertTrue(TemplateCompletion.matches("layout.header", "Lay"));
        assertTrue(TemplateCompletion.matches("layout.header", "head"));
        assertFalse(TemplateCompletion.matches("layout.header", "eader"));
    }

    /// Replacements proposed at `|` in the template, after the namespace declarations.
    private static List<String> replacements(String body) throws CoreException {
        String text = HEADER + body;
        int offset = text.indexOf('|');
        return TemplateCompletion.compute(template, text.replace("|", ""), offset, new NullProgressMonitor()) //
                                 .stream() //
                                 .map(TemplateCompletion.Candidate::replacement) //
                                 .toList();
    }
}
