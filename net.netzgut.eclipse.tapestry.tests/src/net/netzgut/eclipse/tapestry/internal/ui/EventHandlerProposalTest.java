package net.netzgut.eclipse.tapestry.internal.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.junit.jupiter.api.Test;

import net.netzgut.eclipse.tapestry.internal.core.EventHandler;
import net.netzgut.eclipse.tapestry.internal.core.LifecycleMethod;
import net.netzgut.eclipse.tapestry.internal.core.TestProject;

class EventHandlerProposalTest {

    private static final EventHandler SUCCESS =
        new EventHandler("onSuccessFromLogin", "success", "SUCCESS", "login", "Form", "Object", "Index.tml");

    @Test
    void insertsAnnotatedDeclarationWithCursorInBody() {
        // ARRANGE
        IDocument document = new Document("""
            class Index {
                onSu
            }
            """);
        EventHandlerProposal proposal =
            new EventHandlerProposal(SUCCESS, true, null, 18, 18, 4, true, "    ", "    ", "\n");

        // ACT
        proposal.apply(document);

        // ASSERT
        String result = document.get();
        assertEquals("""
            class Index {
                @OnEvent(value = EventConstants.SUCCESS, component = "login")
                Object onSuccessFromLogin() {
                   \s
                    return null;
                }
            }
            """, result);

        int expectedCursor = result.indexOf("\n        return");
        int cursor = proposal.getSelection(document).x;
        assertEquals(expectedCursor, cursor);
    }

    @Test
    void putsAnnotationBeforeTypedModifiersAndReturnType() {
        // ARRANGE
        IDocument document = new Document("""
            class Index {
                void onVa
            }
            """);
        EventHandler handler = new EventHandler("onRefresh", "refresh", null, null, null, "void", "Index.tml");
        EventHandlerProposal proposal =
            new EventHandlerProposal(handler, true, null, 18, 23, 4, false, "    ", "    ", "\n");

        // ACT
        proposal.apply(document);

        // ASSERT
        String result = document.get();
        assertEquals("""
            class Index {
                @OnEvent(value = "refresh")
                void onRefresh() {
                   \s
                }
            }
            """, result);
    }

    @Test
    void insertsLifecycleMethodWithAnnotation() {
        // ARRANGE
        IDocument document = new Document("""
            class Index {
                set
            }
            """);
        LifecycleMethod setupRender = new LifecycleMethod("setupRender", "SetupRender", "Render phase");
        EventHandlerProposal proposal =
            new EventHandlerProposal(setupRender, true, null, 18, 18, 3, true, "    ", "    ", "\n");

        // ACT
        proposal.apply(document);

        // ASSERT
        String result = document.get();
        assertEquals("""
            class Index {
                @SetupRender
                void setupRender() {
                   \s
                }
            }
            """, result);
    }

    @Test
    void omitsAnnotationWhenDisabled() {
        // ARRANGE
        IDocument document = new Document("""
            class Index {
                onSu
            }
            """);
        EventHandlerProposal proposal =
            new EventHandlerProposal(SUCCESS, false, null, 18, 18, 4, true, "    ", "    ", "\n");

        // ACT
        proposal.apply(document);

        // ASSERT
        String result = document.get();
        assertEquals("""
            class Index {
                Object onSuccessFromLogin() {
                   \s
                    return null;
                }
            }
            """, result);
    }

    @Test
    void addsImports() throws CoreException {
        // ARRANGE
        try (TestProject project = TestProject.create("imports")) {
            String source = """
                package com.example.pages;

                import java.util.List;

                public class Index {
                    onSu
                }
                """;
            IFile file = project.file("src/main/java/com/example/pages/Index.java", source);
            ICompilationUnit unit = JavaCore.createCompilationUnitFrom(file);
            IDocument document = new Document(source);
            int start = source.indexOf("onSu");
            EventHandlerProposal proposal =
                new EventHandlerProposal(SUCCESS, true, unit, start, start, 4, true, "    ", "    ", "\n");
            // The closing delimiter sets the indentation: both lines keep 4 spaces
            String declaration = """
                    @OnEvent(value = EventConstants.SUCCESS, component = "login")
                    Object onSuccessFromLogin() {
                """;

            // ACT
            proposal.apply(document);

            // ASSERT
            String result = document.get();
            assertTrue(result.contains("import org.apache.tapestry5.EventConstants;\n"), result);
            assertTrue(result.contains("import org.apache.tapestry5.annotations.OnEvent;\n"), result);
            assertTrue(result.contains(declaration), result);
            int expectedCursor = result.indexOf("\n        return");
            int cursor = proposal.getSelection(document).x;
            assertEquals(expectedCursor, cursor);
        }
    }

    @Test
    void matchesPrefixCamelCaseAndSubstring() {
        // ACT & ASSERT
        assertTrue(EventHandlerProposal.matches("onsu", "onSuccessFromLogin"));
        assertTrue(EventHandlerProposal.matches("oSFL", "onSuccessFromLogin"));
        assertTrue(EventHandlerProposal.matches("login", "onSuccessFromLogin"));
        assertFalse(EventHandlerProposal.matches("onVa", "onSuccessFromLogin"));
    }
}
