package net.netzgut.eclipse.tapestry.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MessageCatalogsTest {

    private static final String INDEX = """
        package com.example.pages;
        import java.util.Map;
        import org.apache.tapestry5.commons.Messages;
        public class Index extends com.example.base.BasePage {
            private Messages messages;
            private Map<String, String> map;
            String a() { return messages.get("ti"); }
            String b() { return messages.format("greeting", "x"); }
            String c() { return map.get("ti"); }
            String d() { return String.valueOf("ti"); }
        }
        """;

    private static TestProject shop;

    @BeforeAll
    static void createProject() throws CoreException {
        shop = TestProject.create("messages");
        shop.file("src/main/java/org/apache/tapestry5/commons/Messages.java", """
            package org.apache.tapestry5.commons;
            public interface Messages {
                String get(String key);
                String format(String key, Object... args);
                boolean contains(String key);
            }
            """);
        shop.file("src/main/java/com/example/base/BasePage.java",
                  "package com.example.base; public abstract class BasePage {}");
        shop.file("src/main/resources/com/example/base/BasePage.properties", "title=Base title\nfooter=Footer\n");
        shop.file("src/main/java/com/example/pages/Index.java", INDEX);
        shop.file("src/main/resources/com/example/pages/Index.properties",
                  "# comment with title=x\ntitle=Welcome\n  greeting = Hello %s\n");
        shop.file("src/main/resources/com/example/pages/Index_de.properties", "title=Willkommen\n");
    }

    @AfterAll
    static void deleteProject() throws CoreException {
        shop.close();
    }

    @Test
    void collectsKeysFromClassLocalesAndSuperclasses() throws CoreException {
        // ARRANGE
        IType index = shop.type("com.example.pages.Index");

        // ACT
        List<MessageKey> keys = MessageCatalogs.keys(index);

        // ASSERT
        List<String> keyNames = keys.stream().map(MessageKey::key).toList();
        assertEquals(List.of("footer", "greeting", "title"), keyNames);
        MessageKey title = keys.get(2);
        assertEquals("Welcome", title.value());
        List<String> titleFiles = title.definitions().stream().map(d -> d.file().getName()).toList();
        assertEquals(List.of("Index.properties", "Index_de.properties", "BasePage.properties"), titleFiles);
    }

    @Test
    void recognizesKeyArgumentsOfMessages() {
        // ARRANGE
        IFile source = shop.file("src/main/java/com/example/pages/Index.java");
        ICompilationUnit unit = JavaCore.createCompilationUnitFrom(source);
        int keyStart = INDEX.indexOf("messages.get(\"") + "messages.get(\"".length();

        // ACT
        MessageCalls.KeyArgument get = argumentAfter(unit, "messages.get(\"t");

        // ASSERT
        assertEquals("t", get.prefix());
        assertEquals("ti", get.key());
        assertEquals(2, get.length());
        assertEquals(keyStart, get.start());

        // ACT & ASSERT
        assertEquals("gree", argumentAfter(unit, "messages.format(\"gree").prefix());
        assertNull(argumentAfter(unit, "map.get(\"t"), "Map, not Messages");
        assertNull(argumentAfter(unit, "valueOf(\"t"), "other method");
        assertNull(argumentAfter(unit, "\"greeting\", \"x"), "second argument");
    }

    @Test
    void locatesKeysInCatalogs() throws CoreException {
        // ARRANGE
        IFile index = shop.file("src/main/resources/com/example/pages/Index.properties");
        IType indexType = shop.type("com.example.pages.Index");
        int greetingLineStart = "# comment with title=x\ntitle=Welcome\n".length();

        // ACT
        MessageCatalogs.KeyPosition title = MessageCatalogs.locate(index, "title");
        MessageCatalogs.KeyPosition greeting = MessageCatalogs.locate(index, "greeting");
        MessageCatalogs.KeyPosition footer = MessageCatalogs.locate(index, "footer");
        List<String> footerFiles =
            MessageCatalogs.definitions(indexType, "footer").stream().map(d -> d.file().getName()).toList();

        // ASSERT
        assertEquals(2, title.line(), "not the comment");
        assertEquals(3, greeting.line());
        assertEquals("  ".length(), greeting.offset() - greetingLineStart);
        assertNull(footer);
        assertEquals(List.of("BasePage.properties"), footerFiles);
    }

    private static MessageCalls.KeyArgument argumentAfter(ICompilationUnit unit, String textBeforeCursor) {
        int offset = INDEX.indexOf(textBeforeCursor) + textBeforeCursor.length();
        return MessageCalls.keyArgumentAt(unit, offset, new NullProgressMonitor());
    }
}
