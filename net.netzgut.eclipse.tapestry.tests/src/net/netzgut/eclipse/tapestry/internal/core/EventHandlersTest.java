package net.netzgut.eclipse.tapestry.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// No Tapestry jars on the classpath: core components come from the built-in table, the rest
/// from sources.
class EventHandlersTest {

    private static TestProject shop;

    @BeforeAll
    static void createProject() throws CoreException {
        shop = TestProject.create("events");
        // Stand-in for the Tapestry jar; source fields report their constant as a quoted literal
        shop.file("src/main/java/org/apache/tapestry5/EventConstants.java", """
            package org.apache.tapestry5;
            public class EventConstants {
                public static final String SUCCESS = "success";
                public static final String VALIDATE = "validate";
                public static final String PREPARE_FOR_SUBMIT = "prepareForSubmit";
            }
            """);
        shop.file("src/main/java/com/example/EventNames.java", """
            package com.example;
            public class EventNames { public static final String OTHER = "otherEvent"; }
            """);
        shop.file("src/main/java/com/example/components/Widget.java", """
            package com.example.components;
            import com.example.EventNames;
            @Events({ "customEvent", EventNames.OTHER })
            public class Widget {}
            """);
        shop.file("src/main/java/com/example/components/layout/Teaser.java", """
            package com.example.components.layout;
            @Events("teaserClicked")
            public class Teaser {}
            """);
        shop.file("src/main/java/com/example/pages/Index.java", """
            package com.example.pages;
            import com.example.components.Widget;
            public class Index {
                @Component private Widget widget;
                Object onSuccessFromLoginForm() { return null; }
                void setupRender() {}
                @AfterRender void done() {}
                Object onPassivate() { return null; }
                @OnEvent(value = "action", component = "delete") Object remove() { return null; }
                @OnEvent(value = org.apache.tapestry5.EventConstants.VALIDATE, component = "loginForm") void check() {}
            }
            """);
        shop.file("src/main/resources/com/example/pages/Index.tml", """
            <html xmlns:t="http://tapestry.apache.org/schema/tapestry_5_4.xsd">
              <t:form t:id="loginForm"><t:submit/></t:form>
              <t:actionlink t:id="delete">x</t:actionlink>
              <a t:type="eventlink" t:id="reload" event="refresh">x</a>
              <input t:type="textfield" t:id="city" t:mixins="autocomplete"/>
              <div t:id="widget"/>
              <t:layout.teaser t:id="teaser"/>
              <div t:type="layout/teaser" t:id="teaser2"/>
            </html>
            """);
    }

    @AfterAll
    static void deleteProject() throws CoreException {
        shop.close();
    }

    @Test
    void proposesHandlersForTemplateComponents() throws CoreException {
        // ACT
        Set<String> names = handlerNames();

        // ASSERT
        assertTrue(names.containsAll(Set.of("onPrepareForSubmitFromLoginForm",
                                            "onFailureFromLoginForm",
                                            "onSelected", // anonymous t:submit
                                            "onRefreshFromReload", // event="refresh"
                                            "onProvideCompletionsFromCity", // autocomplete mixin
                                            "onCustomEventFromWidget", // @Component field type
                                            "onOtherEventFromWidget", // @Events constant
                                            "onTeaserClickedFromTeaser", // <t:layout.teaser>
                                            "onTeaserClickedFromTeaser2")), // sub folder
                   names::toString);
    }

    @Test
    void skipsExistingHandlers() throws CoreException {
        // ACT
        Set<String> names = handlerNames();

        // ASSERT
        assertFalse(names.contains("onSuccessFromLoginForm"), "declared by name");
        assertFalse(names.contains("onActionFromDelete"), "declared via @OnEvent");
        assertFalse(names.contains("onValidateFromLoginForm"), "declared via @OnEvent with constant");
    }

    @Test
    void choosesReturnTypeByEvent() throws CoreException {
        // ARRANGE
        IType index = shop.type("com.example.pages.Index");

        // ACT
        List<EventHandler> handlers = EventHandlers.missingHandlers(index, new NullProgressMonitor());
        EventHandler prepareForSubmit = find(handlers, "onPrepareForSubmitFromLoginForm");
        EventHandler refresh = find(handlers, "onRefreshFromReload");

        // ASSERT
        assertEquals(true, prepareForSubmit.returnsVoid());
        assertEquals(false, refresh.returnsVoid());
    }

    @Test
    void resolvesEventConstants() throws CoreException {
        // ARRANGE
        IType index = shop.type("com.example.pages.Index");

        // ACT
        List<EventHandler> handlers = EventHandlers.missingHandlers(index, new NullProgressMonitor());
        EventHandler prepareForSubmit = find(handlers, "onPrepareForSubmitFromLoginForm");
        EventHandler refresh = find(handlers, "onRefreshFromReload");

        // ASSERT
        assertEquals("PREPARE_FOR_SUBMIT", prepareForSubmit.eventConstant());
        assertEquals(null, refresh.eventConstant());
    }

    @Test
    void proposesPageEventsAndMissingLifecycleMethods() throws CoreException {
        // ARRANGE
        IType index = shop.type("com.example.pages.Index");
        IType eventNames = shop.type("com.example.EventNames");

        // ACT
        Set<String> names = handlerNames();
        Set<String> lifecycle =
            LifecycleMethod.missing(index).stream().map(LifecycleMethod::methodName).collect(Collectors.toSet());
        boolean indexIsComponentClass = EventHandlers.isComponentClass(index);
        boolean eventNamesIsComponentClass = EventHandlers.isComponentClass(eventNames);

        // ASSERT
        assertTrue(names.contains("onActivate"), names::toString);
        assertFalse(names.contains("onPassivate"), "declared");
        assertTrue(lifecycle.contains("beginRender"));
        assertFalse(lifecycle.contains("setupRender"), "declared by name");
        assertFalse(lifecycle.contains("afterRender"), "declared via annotation");
        assertTrue(indexIsComponentClass);
        assertFalse(eventNamesIsComponentClass);
    }

    private static Set<String> handlerNames() throws CoreException {
        return EventHandlers.missingHandlers(shop.type("com.example.pages.Index"), new NullProgressMonitor()).stream()
                            .map(EventHandler::methodName).collect(Collectors.toSet());
    }

    private static EventHandler find(List<EventHandler> handlers, String name) {
        return handlers.stream().filter(h -> h.methodName().equals(name)).findFirst().orElseThrow();
    }
}
