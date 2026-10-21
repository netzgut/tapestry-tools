package net.netzgut.eclipse.tapestry.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PropertyExpressionTest {

    private static final String HEADER = """
        <html xmlns:t="http://tapestry.apache.org/schema/tapestry_5_4.xsd">
        """;

    private static TestProject app;
    private static IFile       template;

    @BeforeAll
    static void createProject() throws CoreException {
        app = TestProject.create("properties");
        app.file("src/main/java/com/example/app/model/Address.java", """
            package com.example.app.model;
            public class Address {
                public String getCity() { return null; }
                public String format(String pattern) { return null; }
            }
            """);
        app.file("src/main/java/com/example/app/model/User.java", """
            package com.example.app.model;
            public class User {
                public String getName() { return null; }
                public boolean isActive() { return false; }
                public Address getAddress() { return null; }
                public String getURL() { return null; }
                public String nickname;
                private String secret;
            }
            """);
        app.file("src/main/java/com/example/app/model/Box.java", """
            package com.example.app.model;
            public class Box<T> {
                public T getContent() { return null; }
                public int getSize() { return 0; }
            }
            """);
        app.file("src/main/java/com/example/app/base/BasePage.java", """
            package com.example.app.base;
            import com.example.app.model.User;
            public abstract class BasePage {
                public User getCurrentUser() { return null; }
            }
            """);
        app.file("src/main/java/com/example/app/pages/Index.java", """
            package com.example.app.pages;
            import org.apache.tapestry5.annotations.Property;
            import com.example.app.model.User;
            public class Index extends com.example.app.base.BasePage {
                @Property private User user;
                @Property private com.example.app.model.Box<User> users;
                public int count() { return 0; }
                public void reset() {}
            }
            """);
        app.file("src/main/java/com/example/app/components/Output.java", """
            package com.example.app.components;
            import org.apache.tapestry5.annotations.Parameter;
            public class Output {
                @Parameter private Object value;
                @Parameter(defaultPrefix = "literal") private String format;
            }
            """);
        template = app.file("src/main/resources/com/example/app/pages/Index.tml", "");
    }

    @AfterAll
    static void deleteProject() throws CoreException {
        app.close();
    }

    @Test
    void findsExpressionsInExpansionsAndParameters() {
        // ACT
        PropertyExpression expansion = at("<p>${user.addr|ess.city}</p>");

        // ASSERT
        assertEquals(List.of("user"), expansion.path());
        assertEquals("address", expansion.name());
        assertFalse(expansion.needsPropertyParameter());

        // ACT & ASSERT
        assertEquals(List.of("user", "address"), at("${prop:user?.address.ci|ty}").path());
        assertEquals(List.of("user"), at("${format(user.na|me)}").path());
        assertEquals(List.of("users", "get"), at("${users.get(0).na|me}").path());
        assertEquals(List.of(), at("${this.us|er}").path());
        assertEquals(List.of(), at("${!act|ive}").path());

        // ACT
        PropertyExpression parameter = at("<t:output value=\"user.na|me\"/>");

        // ASSERT
        assertEquals("output", parameter.component());
        assertEquals("value", parameter.parameter());
        assertTrue(parameter.needsPropertyParameter());

        // ACT & ASSERT
        assertFalse(at("<t:output value=\"prop:user.na|me\"/>").needsPropertyParameter());
        assertNull(at("${message:ti|tle}"));
        assertNull(at("<t:output value=\"literal:na|me\"/>"));
        assertNull(at("<div class=\"us|er\"/>")); // not a component
        assertNull(at("<t:output t:id=\"us|er\"/>"));
        assertNull(at("${1..1|0}"));
        assertNull(at("${tr|ue}"));
        assertNull(at("${user}<p>na|me</p>"));
    }

    @Test
    void walksPropertiesLikeTapestry() throws CoreException {
        // ARRANGE
        IFile index = template;
        PropertyExpression addressCity = at("${currentUser.address.ci|ty}");
        PropertyExpression boxSize = at("${users.si|ze}");
        PropertyExpression contentName = at("${users.content.na|me}");
        PropertyExpression literalParameter = at("<t:output format=\"user.na|me\"/>");
        PropertyExpression informalParameter = at("<t:output class=\"user.na|me\"/>");
        IType user = app.type("com.example.app.model.User");
        IType page = app.type("com.example.app.pages.Index");

        // ACT
        IType address = PropertyPaths.context(index, addressCity, null);
        IType box = PropertyPaths.context(index, boxSize, null);
        IType typeVariable = PropertyPaths.context(index, contentName, null);
        IMember active = PropertyPaths.find(user, "ACTIVE", null);
        IMember userProperty = PropertyPaths.find(page, "user", null);
        IType literal = PropertyPaths.context(index, literalParameter, null);
        IType informal = PropertyPaths.context(index, informalParameter, null);

        // ASSERT
        assertEquals("com.example.app.model.Address", address.getFullyQualifiedName());
        assertEquals("com.example.app.model.Box", box.getFullyQualifiedName());
        assertNull(typeVariable); // type variable
        assertEquals("isActive", active.getElementName());
        assertEquals("user", userProperty.getElementName());
        assertNull(literal); // literal parameter
        assertNull(informal); // informal
    }

    @Test
    void proposesPropertiesAndMethods() throws CoreException {
        // ACT & ASSERT
        assertEquals(List.of("active", "address", "name", "nickname", "URL"), replacements("${user.|}"));
        assertEquals(List.of("city", "format()"), replacements("${user.address.|}"));
        assertEquals(List.of("currentUser", "user", "users", "count()"), replacements("<t:output value=\"|\"/>"));
        assertEquals(List.of("user", "users"), replacements("${us|}"));
        assertEquals(List.of(), replacements("<t:output format=\"|\"/>"));
    }

    @Test
    void placesCaretInsideArguments() throws CoreException {
        // ARRANGE
        String text = HEADER + "${user.address.form|}";
        int offset = text.indexOf('|');
        String source = text.replace("|", "");

        // ACT
        List<TemplateCompletion.Candidate> candidates =
            TemplateCompletion.compute(template, source, offset, new NullProgressMonitor());

        // ASSERT
        TemplateCompletion.Candidate candidate = candidates.get(0);
        assertEquals("format()", candidate.replacement());
        assertEquals("format(".length(), candidate.cursor());
    }

    private static PropertyExpression at(String body) {
        String text = HEADER + body;
        return PropertyExpression.at(text.replace("|", ""), text.indexOf('|'), false);
    }

    private static List<String> replacements(String body) throws CoreException {
        String text = HEADER + body;
        int offset = text.indexOf('|');
        return TemplateCompletion.compute(template, text.replace("|", ""), offset, new NullProgressMonitor()).stream()
                                 .map(TemplateCompletion.Candidate::replacement).toList();
    }
}
