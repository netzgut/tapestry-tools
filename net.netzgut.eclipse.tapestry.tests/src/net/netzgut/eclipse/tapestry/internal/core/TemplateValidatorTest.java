package net.netzgut.eclipse.tapestry.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TemplateValidatorTest {

    private static final String HEADER = """
        <html xmlns:t="http://tapestry.apache.org/schema/tapestry_5_4.xsd" xmlns:p="tapestry:parameter">
        """;

    private static TestProject app;
    private static IFile       template;

    @BeforeAll
    static void createProject() throws CoreException {
        app = TestProject.create("validation");
        app.file("src/main/java/com/example/app/components/Table.java", """
            package com.example.app.components;
            import org.apache.tapestry5.annotations.Parameter;
            public class Table {
                @Parameter private String empty;
                @Parameter(required = true) private Iterable<?> source;
            }
            """);
        app.file("src/main/java/com/example/app/components/Box.java", """
            package com.example.app.components;
            import org.apache.tapestry5.annotations.Parameter;
            import org.apache.tapestry5.annotations.SupportsInformalParameters;
            @SupportsInformalParameters
            public class Box {
                @Parameter private String title;
            }
            """);
        app.file("src/main/java/com/example/app/components/PageLink.java", """
            package com.example.app.components;
            import org.apache.tapestry5.annotations.Parameter;
            public class PageLink {
                @Parameter(required = true, defaultPrefix = "literal") private String page;
            }
            """);
        app.file("src/main/java/com/example/app/mixins/Highlight.java", """
            package com.example.app.mixins;
            import org.apache.tapestry5.annotations.Parameter;
            public class Highlight {
                @Parameter private String color;
            }
            """);
        app.file("src/main/java/com/example/app/pages/Index.java", """
            package com.example.app.pages;
            import org.apache.tapestry5.annotations.Component;
            public class Index {
                @Component private Object grid;
                void onActionFromLink() {}
            }
            """);
        app.file("src/main/resources/com/example/app/pages/Index.properties", "title=Welcome\n");
        app.file("src/main/webapp/WEB-INF/app.properties", "footer=Bye\n");
        template = app.file("src/main/resources/com/example/app/pages/Index.tml", "");
    }

    @AfterAll
    static void deleteProject() throws CoreException {
        app.close();
    }

    @Test
    void reportsUnknownComponentsMixinsAndPages() throws CoreException {
        // ACT
        List<String> reported = problems("""
            <t:tabel/>
            <div t:type="tabel"/>
            <t:table source="x" t:mixins="highlight, glow"/>
            <t:pagelink page="about"/> <t:pagelink page="index"/>
            <t:container><t:block id="b"/><t:body/></t:container>
            <!-- <t:commented/> -->
            <t:table t:type="${dynamic}"/>
            """);

        // ASSERT
        assertEquals(List.of("UNKNOWN_COMPONENT tabel",
                             "UNKNOWN_COMPONENT tabel",
                             "UNKNOWN_COMPONENT glow",
                             "UNKNOWN_PAGE about"),
                     reported);
    }

    @Test
    void reportsParametersTapestryIgnores() throws CoreException {
        // ACT
        List<String> reported = problems("""
            <t:table sorce="x" source="y" t:empty="e" t:id="list" xml:lang="en" highlight.color="red"/>
            <div t:type="table" class="striped"/>
            <t:table t:mixins="highlight" color="red"/>
            <t:box anything="goes"/>
            <t:table><p:empty/><p:head/></t:table>
            <t:box><p:anything/></t:box>
            """);

        // ASSERT
        assertEquals(List.of("UNKNOWN_PARAMETER sorce", "UNKNOWN_PARAMETER class", "UNKNOWN_PARAMETER head"), reported);
    }

    @Test
    void reportsMissingMessages() throws CoreException {
        // ACT
        List<String> reported = problems("""
            <h1>${message:title}</h1>
            <p title="message:subtitle">${message:FOOTER}</p>
            <!-- ${message:old} -->
            """);

        // ASSERT
        assertEquals(List.of("MISSING_MESSAGE subtitle"), reported);
    }

    @Test
    void reportsComponentIdsOnlyWhenAsked() throws CoreException {
        // ARRANGE
        String body = "<t:table t:id=\"grid\"/><t:actionlink t:id=\"link\"/><t:table t:id=\"other\"/>";
        Set<TemplateValidator.Check> withoutIdCheck = EnumSet.of(TemplateValidator.Check.UNKNOWN_PAGE);
        Set<TemplateValidator.Check> withIdCheck = EnumSet.of(TemplateValidator.Check.UNKNOWN_COMPONENT_ID);

        // ACT
        List<String> reportedWithoutIdCheck = problems(body, withoutIdCheck);
        List<String> reportedWithIdCheck = problems(body, withIdCheck);

        // ASSERT
        assertEquals(List.of(), reportedWithoutIdCheck);
        assertEquals(List.of("UNKNOWN_COMPONENT_ID other"), reportedWithIdCheck);
    }

    private static List<String> problems(String body) throws CoreException {
        return problems(body, EnumSet.complementOf(EnumSet.of(TemplateValidator.Check.UNKNOWN_COMPONENT_ID)));
    }

    private static List<String> problems(String body, Set<TemplateValidator.Check> checks) throws CoreException {
        String content = HEADER + body;
        return new TemplateValidator(app.javaProject,
                                     checks,
                                     new NullProgressMonitor()).validate(template, content).stream()
                                                               .map(problem -> problem.check()
                                                                               + " "
                                                                               + content.substring(problem.offset(),
                                                                                                   problem.offset()
                                                                                                                     + problem.length()))
                                                               .toList();
    }
}
