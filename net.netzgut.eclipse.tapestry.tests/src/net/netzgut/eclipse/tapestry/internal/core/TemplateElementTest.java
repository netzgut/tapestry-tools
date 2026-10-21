package net.netzgut.eclipse.tapestry.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.IRegion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TemplateElementTest {

    private static final String HEADER = """
        <html xmlns:t="http://tapestry.apache.org/schema/tapestry_5_4.xsd" xmlns:p="tapestry:parameter">
        """;

    private static TestProject app;
    private static IFile       template;

    @BeforeAll
    static void createProject() throws CoreException {
        app = TestProject.create("elements");
        app.file("src/main/java/com/example/app/components/Table.java", """
            package com.example.app.components;
            import org.apache.tapestry5.annotations.Parameter;
            public class Table {
                @Parameter private String empty;
                @Parameter(required = true) private Iterable<?> source;
                @Parameter private Object row;
            }
            """);
        app.file("src/main/java/com/example/app/pages/Index.java", """
            package com.example.app.pages;
            public class Index {
                public String getTitle() { return null; }
            }
            """);
        template = app.file("src/main/resources/com/example/app/pages/Index.tml", "");
    }

    @AfterAll
    static void deleteProject() throws CoreException {
        app.close();
    }

    @Test
    void resolvesComponentsWithRequiredParametersFirst() throws CoreException {
        // ACT
        TemplateElement tableElement = at("<t:ta|ble source=\"items\"/>");

        // ASSERT
        TemplateElement.Component component = assertInstanceOf(TemplateElement.Component.class, tableElement);
        assertEquals("com.example.app.components.Table", component.member().getFullyQualifiedName());
        List<String> parameterNames = component.parameters().stream().map(ComponentParameters.Parameter::name).toList();
        assertEquals(List.of("source", "empty", "row"), parameterNames);

        // ACT
        TemplateElement pageElement = at("<t:pagelink page=\"in|dex\"/>");

        // ASSERT
        TemplateElement.Component page = assertInstanceOf(TemplateElement.Component.class, pageElement);
        assertEquals(List.of(), page.parameters());
    }

    @Test
    void resolvesParametersAndProperties() throws CoreException {
        // ACT
        TemplateElement sourceElement = at("<t:table so|urce=\"items\"/>");
        TemplateElement emptyElement = at("<t:table><p:em|pty/></t:table>");
        TemplateElement titleElement = at("<h1>${ti|tle}</h1>");

        // ASSERT
        TemplateElement.Parameter source = assertInstanceOf(TemplateElement.Parameter.class, sourceElement);
        assertEquals("source", source.member().getElementName());
        assertEquals("table", source.component());
        TemplateElement.Parameter empty = assertInstanceOf(TemplateElement.Parameter.class, emptyElement);
        assertEquals("empty", empty.member().getElementName());
        assertEquals("getTitle", titleElement.member().getElementName());

        // ACT & ASSERT
        assertNull(at("<t:table un|known=\"x\"/>"));
        assertNull(at("<t:table t:id=\"ta|ble\"/>")); // component ids aren't hovered
        assertNull(at("${message:ti|tle}"));
    }

    @Test
    void findsRegionsWithoutResolving() {
        // ACT
        IRegion sourceRegion = regionAt("<t:table so|urce=\"items\"/>");
        IRegion titleRegion = regionAt("${ti|tle}");
        IRegion textRegion = regionAt("<p>te|xt</p>");

        // ASSERT
        String source = text("<t:table so|urce=\"items\"/>", sourceRegion);
        assertEquals("source", source);

        String title = text("${ti|tle}", titleRegion);
        assertEquals("title", title);

        assertNull(textRegion);
    }

    private static TemplateElement at(String body) throws CoreException {
        String text = HEADER + body;
        return TemplateElement.at(template, text.replace("|", ""), text.indexOf('|'), new NullProgressMonitor());
    }

    private static IRegion regionAt(String body) {
        String text = HEADER + body;
        return TemplateElement.regionAt(text.replace("|", ""), text.indexOf('|'));
    }

    private static String text(String body, IRegion region) {
        String text = (HEADER + body).replace("|", "");
        return text.substring(region.getOffset(), region.getOffset() + region.getLength());
    }
}
