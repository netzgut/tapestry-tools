package net.netzgut.eclipse.tapestry.internal.core;

import static net.netzgut.eclipse.tapestry.internal.core.NewResource.Kind.MESSAGES;
import static net.netzgut.eclipse.tapestry.internal.core.NewResource.Kind.TEMPLATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NewResourceTest {

    private static TestProject shop;
    private static TestProject plain;

    @BeforeAll
    static void createProjects() throws CoreException {
        shop = TestProject.create("newtemplate");
        shop.javaClass("com.example.pages.admin.Users");
        shop.javaClass("com.example.components.Layout");
        shop.javaClass("com.example.mixins.Confirm");
        shop.javaClass("com.example.services.AppModule");
        shop.file("src/main/java/com/example/base/BasePage.java",
                  "package com.example.base; public abstract class BasePage {}");
        shop.file("src/main/java/com/example/pages/Abstract.java",
                  "package com.example.pages; public abstract class Abstract {}");

        // classes and templates in the same source folder
        plain = TestProject.create("newtemplateplain");
        plain.javaProject.setRawClasspath(new IClasspathEntry[] { JavaCore.newSourceEntry(plain.mkdirs("src")
                                                                                               .getFullPath()) },
                                          plain.mkdirs("bin").getFullPath(),
                                          null);
        plain.file("src/com/example/pages/Index.java", "package com.example.pages; public class Index {}");
    }

    @AfterAll
    static void deleteProjects() throws CoreException {
        shop.close();
        plain.close();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        # QUALIFIED NAME               | IS APPLICABLE
        com.example.pages.admin.Users  | true
        com.example.components.Layout  | true
        com.example.mixins.Confirm     | false
        com.example.services.AppModule | false
        com.example.base.BasePage      | false
        com.example.pages.Abstract     | false
        """)
    void offersTemplatesForPagesAndComponentsOnly(String qualifiedName, boolean expected) throws CoreException {
        // ARRANGE
        IType itype = shop.type(qualifiedName);

        // ACT
        boolean result = NewResource.isApplicable(itype, TEMPLATE);

        // ASSERT
        assertEquals(expected, result);
    }

    @Test
    void createsPageTemplateInResourcesFolder() throws CoreException {
        // ARRANGE
        IType users = shop.type("com.example.pages.admin.Users");

        // ACT
        IFile created = NewResource.create(users, TEMPLATE, null);

        // ASSERT
        IFile expected = shop.file("src/main/resources/com/example/pages/admin/Users.tml");
        assertEquals(expected, created);
        assertTrue(created.exists());
        String content = TemplateLocator.read(created);
        assertEquals("", content);
        List<IFile> templates = TemplateLocator.findTemplates(users);
        assertEquals(List.of(created), templates);
    }

    @Test
    void createsComponentTemplateAsContainer() throws CoreException {
        // ARRANGE
        IType layout = shop.type("com.example.components.Layout");
        String expectedStart = "<t:container xmlns:t=\"" + NewResource.NAMESPACE + "\" xmlns:p=\"tapestry:parameter\">";

        // ACT
        IFile created = NewResource.create(layout, TEMPLATE, null);

        // ASSERT
        String content = TemplateLocator.read(created);
        assertTrue(content.startsWith(expectedStart));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        # QUALIFIED NAME               | IS APPLICABLE
        com.example.mixins.Confirm     | true
        com.example.base.BasePage      | true
        com.example.pages.Abstract     | true
        com.example.services.AppModule | false
        """)
    void offersMessagesAlsoForMixinsAndBaseClasses(String qualifiedName, boolean expected) throws CoreException {
        // ARRANGE
        IType itype = shop.type(qualifiedName);

        // ACT
        boolean result = NewResource.isApplicable(itype, MESSAGES);

        // ASSERT
        assertEquals(expected, result);
    }

    @Test
    void listsMissingFilesIgnoringLocaleVariants() throws CoreException {
        // ARRANGE
        shop.javaClass("com.example.pages.Contact");
        shop.file("src/main/resources/com/example/pages/Contact_de.properties", "title=Kontakt\n");
        IType contact = shop.type("com.example.pages.Contact");
        IType confirm = shop.type("com.example.mixins.Confirm");

        // ACT
        List<NewResource.Kind> missingBefore = NewResource.missing(contact);

        // ASSERT
        assertEquals(List.of(TEMPLATE, MESSAGES), missingBefore);

        // ACT
        IFile catalog = NewResource.create(contact, MESSAGES, null);
        String content = TemplateLocator.read(catalog);
        List<NewResource.Kind> missingAfter = NewResource.missing(contact);
        List<NewResource.Kind> missingForMixin = NewResource.missing(confirm);

        // ASSERT
        IFile expected = shop.file("src/main/resources/com/example/pages/Contact.properties");
        assertEquals(expected, catalog);
        assertEquals("", content);
        assertEquals(List.of(TEMPLATE), missingAfter);
        assertEquals(List.of(MESSAGES), missingForMixin);
    }

    @Test
    void createsTemplateNextToClassWithoutResourcesFolder() throws CoreException {
        // ARRANGE
        IType index = plain.type("com.example.pages.Index");

        // ACT
        IFile location = NewResource.location(index, TEMPLATE);

        // ASSERT
        IFile expected = plain.file("src/com/example/pages/Index.tml");
        assertEquals(expected, location);
    }
}
