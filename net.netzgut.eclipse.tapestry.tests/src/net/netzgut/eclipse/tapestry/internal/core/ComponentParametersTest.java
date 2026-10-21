package net.netzgut.eclipse.tapestry.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ComponentParametersTest {

    private static final String HEADER = """
        <html xmlns:t="http://tapestry.apache.org/schema/tapestry_5_4.xsd" xmlns:p="tapestry:parameter">
        """;

    private static TestProject app;
    private static IFile       template;

    @BeforeAll
    static void createProject() throws CoreException {
        app = TestProject.create("parameters");
        app.file("src/main/java/com/example/app/base/AbstractList.java", """
            package com.example.app.base;
            import org.apache.tapestry5.annotations.Parameter;
            public abstract class AbstractList {
                @Parameter(required = true) private Iterable<?> source;
                @Parameter private String empty;
            }
            """);
        app.file("src/main/java/com/example/app/components/List.java", """
            package com.example.app.components;
            import org.apache.tapestry5.annotations.Parameter;
            import org.apache.tapestry5.BindingConstants;
            public class List extends com.example.app.base.AbstractList {
                @Parameter(defaultPrefix = BindingConstants.LITERAL) private String _rowClass;
                @Parameter(name = "row") private Object current;
                @Parameter private String empty;
                @Parameter(name = "") private String _caption;
                private Object notAParameter;
            }
            """);
        template = app.file("src/main/resources/com/example/app/pages/Index.tml", "");
    }

    @AfterAll
    static void deleteProject() throws CoreException {
        app.close();
    }

    @Test
    void readsParametersIncludingInherited() throws CoreException {
        // ARRANGE
        IType type = app.type("com.example.app.components.List");

        // ACT
        List<ComponentParameters.Parameter> parameters = ComponentParameters.of(type, new NullProgressMonitor());
        List<String> names = parameters.stream().map(ComponentParameters.Parameter::name).toList();

        // ASSERT
        assertEquals(List.of("rowClass", "row", "empty", "caption", "source"), names); // blank name: the field's
        String emptyDeclaringType = parameters.get(2).field().getDeclaringType().getFullyQualifiedName();
        assertEquals("com.example.app.components.List", emptyDeclaringType); // redeclared
        assertEquals("literal", parameters.get(0).defaultPrefix());
        assertEquals("prop", parameters.get(1).defaultPrefix());
        assertTrue(parameters.get(4).required());
    }

    @Test
    void findsParameterReferences() {
        // ACT
        ParameterReference attribute = at("<t:list source=\"items\" e|mpty=\"\"/>", false);

        // ASSERT
        assertEquals("list", attribute.component());
        assertEquals("empty", attribute.name());
        assertEquals(Set.of("source", "empty"), attribute.present());

        // ACT & ASSERT
        assertEquals("source", at("<div t:type=\"list\" t:so|urce=\"items\"/>", false).name());
        assertEquals("", at("<t:list |/>", true).name());
        assertNull(at("<t:list |/>", false));

        // ACT
        ParameterReference block = at("<t:list source=\"items\">\n  <div><p:em|pty>none</p:empty></div>", false);

        // ASSERT
        assertEquals("list", block.component());
        assertEquals("empty", block.name());
        assertTrue(block.block());

        // ACT & ASSERT
        assertNull(at("<div cl|ass=\"x\"/>", false)); // not a component
        assertNull(at("<t:list source=\"it|ems\"/>", false)); // a value
        assertNull(at("<t:list t:i|d=\"items\"/>", false)); // not a parameter
        assertNull(at("<t:list source=\"items\"/>\n<p:em|pty/>", false)); // outside the component
    }

    @Test
    void proposesMissingParametersRequiredFirst() throws CoreException {
        // ACT & ASSERT
        assertEquals(List.of("source=\"\"", "caption=\"\"", "empty=\"\"", "row=\"\"", "rowClass=\"\""), replacements("<t:list |/>"));
        assertEquals(List.of("row=\"\"", "rowClass=\"\""), replacements("<t:list source=\"x\" r|/>"));
        assertEquals(List.of("source", "caption", "row", "rowClass"), replacements("<t:list empty=\"x\"><p:|"));
        assertEquals(List.of("rowClass"), replacements("<t:list rowC|=\"odd\"/>"));
    }

    @Test
    void stripsMemberNamesLikeTapestry() {
        // ACT & ASSERT
        assertEquals("rowClass", ComponentParameters.stripMemberName("_rowClass"));
        assertEquals("value", ComponentParameters.stripMemberName("$value_"));
    }

    private static ParameterReference at(String body, boolean incomplete) {
        String text = HEADER + body;
        return ParameterReference.at(text.replace("|", ""), text.indexOf('|'), incomplete);
    }

    private static List<String> replacements(String body) throws CoreException {
        String text = HEADER + body;
        int offset = text.indexOf('|');
        return TemplateCompletion.compute(template, text.replace("|", ""), offset, new NullProgressMonitor()).stream()
                                 .map(TemplateCompletion.Candidate::replacement).toList();
    }
}
