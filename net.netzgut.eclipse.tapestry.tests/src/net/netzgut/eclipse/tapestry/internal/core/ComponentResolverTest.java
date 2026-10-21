package net.netzgut.eclipse.tapestry.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.netzgut.eclipse.tapestry.internal.core.ComponentResolver.Kind;

class ComponentResolverTest {

    private static TestProject app;

    @BeforeAll
    static void createProject() throws CoreException {
        app = TestProject.create("resolver");
        // Stand-in for the Tapestry jar
        app.file("src/main/java/org/apache/tapestry5/services/LibraryMapping.java", """
            package org.apache.tapestry5.services;
            public class LibraryMapping { public LibraryMapping(String prefix, String rootPackage) {} }
            """);
        app.file("src/main/java/com/example/commons/CommonsModule.java", """
            package com.example.commons;
            import org.apache.tapestry5.services.LibraryMapping;
            public class CommonsModule {
                static final String PREFIX = "frpc2";
                void contribute(java.util.List<LibraryMapping> configuration) {
                    configuration.add(new LibraryMapping(PREFIX, CommonsModule.class.getPackage().getName()));
                    configuration.add(new LibraryMapping("shop", "com.example.shop"));
                    configuration.add(new LibraryMapping("dynamic", System.getProperty("x")));
                }
            }
            """);
        app.javaClass("com.example.commons.components.Bar");
        app.javaClass("com.example.other.components.Bar");
        app.javaClass("com.example.shop.components.cart.Summary");
        app.javaClass("com.example.app.components.layout.Header");
        app.javaClass("com.example.app.mixins.Confirm");
        app.javaClass("com.example.app.pages.admin.Index");
        app.javaClass("com.example.app.pages.admin.AdminUsers");
        app.javaClass("com.example.app.pages.ProductPage");
        app.javaClass("com.example.app.components.layout.LayoutFooter");
        app.javaClass("com.example.shop.components.ShopBadge");

        app.file("src/main/java/com/example/app/pages/Login.java", """
            package com.example.app.pages;
            public class Login {
                static final String FORM = "loginForm";
                @InjectComponent private Object loginForm;
                @Component(id = "city") private Object cityField;
                @InjectComponent("zone") private Object resultZone;
                private Object notAComponent;
                void onValidateFromLoginForm() {}
                @OnEvent(value = "success", component = FORM) Object done() { return null; }
                void setupRender() {}
            }
            """);
    }

    @AfterAll
    static void deleteProject() throws CoreException {
        app.close();
    }

    @Test
    void readsLibraryMappings() throws CoreException {
        // ACT
        Map<String, List<String>> mappings = LibraryMappings.find(app.javaProject, new NullProgressMonitor());

        // ASSERT
        assertEquals(List.of("com.example.commons"), mappings.get("frpc2"));
        assertEquals(List.of("com.example.shop"), mappings.get("shop"));
        assertNull(mappings.get("dynamic"));
        assertEquals(List.of("org.apache.tapestry5.corelib"), mappings.get("core"));
    }

    @Test
    void resolvesLibraryPrefixesExactly() throws CoreException {
        // ACT & ASSERT
        assertEquals(Set.of("com.example.commons.components.Bar"), resolve(Kind.COMPONENT, "frpc2/Bar"));
        assertEquals(Set.of("com.example.commons.components.Bar"), resolve(Kind.COMPONENT, "frpc2.bar"));
        assertEquals(Set.of("com.example.shop.components.cart.Summary"), resolve(Kind.COMPONENT, "shop/cart/Summary"));
    }

    @Test
    void fallsBackToConventions() throws CoreException {
        // ACT & ASSERT
        assertEquals(Set.of("com.example.commons.components.Bar", "com.example.other.components.Bar"),
                     resolve(Kind.COMPONENT, "Bar"));
        assertEquals(Set.of("com.example.commons.components.Bar", "com.example.other.components.Bar"),
                     resolve(Kind.COMPONENT, "unknown/Bar"));
        assertEquals(Set.of("com.example.app.components.layout.Header"), resolve(Kind.COMPONENT, "layout/header"));
        assertEquals(Set.of("com.example.app.mixins.Confirm"), resolve(Kind.MIXIN, "confirm"));
        assertEquals(Set.of("com.example.app.pages.admin.Index"), resolve(Kind.PAGE, "admin/Index"));
        assertEquals(Set.of(), resolve(Kind.COMPONENT, "confirm"));
    }

    @Test
    void resolvesShortenedNames() throws CoreException {
        // ACT & ASSERT
        assertEquals(Set.of("com.example.app.pages.admin.AdminUsers"), resolve(Kind.PAGE, "admin/Users"));
        assertEquals(Set.of("com.example.app.pages.admin.AdminUsers"), resolve(Kind.PAGE, "admin/AdminUsers"));
        assertEquals(Set.of("com.example.app.pages.admin.Index"), resolve(Kind.PAGE, "admin"));
        assertEquals(Set.of("com.example.app.pages.ProductPage"), resolve(Kind.PAGE, "product"));
        assertEquals(Set.of("com.example.app.components.layout.LayoutFooter"),
                     resolve(Kind.COMPONENT, "layout/footer"));
        // library prefix "shop" is a folder term and stripped from ShopBadge
        assertEquals(Set.of("com.example.shop.components.ShopBadge"), resolve(Kind.COMPONENT, "shop/Badge"));
        assertEquals(Set.of(), resolve(Kind.PAGE, "admin/Nothing"));
    }

    @Test
    void reusesCachedResultsUntilTargetIsGone() throws CoreException {
        // ARRANGE
        app.javaClass("com.example.app.components.Teaser");

        // ACT
        Set<String> created = resolve(Kind.COMPONENT, "teaser");

        // ASSERT
        assertEquals(Set.of("com.example.app.components.Teaser"), created);

        // ARRANGE
        app.file("src/main/java/com/example/app/components/Teaser.java").delete(true, null);

        // ACT
        Set<String> deleted = resolve(Kind.COMPONENT, "teaser");

        // ASSERT
        assertEquals(Set.of(), deleted);

        // ARRANGE
        app.javaClass("com.example.app.components.Teaser"); // empty results are not cached

        // ACT
        Set<String> recreated = resolve(Kind.COMPONENT, "teaser");

        // ASSERT
        assertEquals(Set.of("com.example.app.components.Teaser"), recreated);
    }

    @Test
    void findsFieldsAndHandlersOfComponentIds() throws CoreException {
        // ARRANGE
        IType login = app.type("com.example.app.pages.Login");

        // ACT
        List<IMember> loginForm = ComponentIds.references(login, "LOGINFORM");
        List<IMember> city = ComponentIds.references(login, "city");
        List<IMember> zone = ComponentIds.references(login, "zone");
        List<IMember> notAComponent = ComponentIds.references(login, "notAComponent");

        // ASSERT
        assertEquals(List.of("loginForm", "onValidateFromLoginForm", "done"), names(loginForm));
        assertEquals(List.of("cityField"), names(city));
        assertEquals(List.of("resultZone"), names(zone));
        assertEquals(List.of(), names(notAComponent));
    }

    private static Set<String> resolve(Kind kind, String name) throws CoreException {
        return new ComponentResolver(app.javaProject, new NullProgressMonitor()).resolve(kind, name).stream()
                                                                                .map(IType::getFullyQualifiedName)
                                                                                .collect(Collectors.toSet());
    }

    private static List<String> names(List<? extends IJavaElement> elements) {
        return elements.stream().map(IJavaElement::getElementName).toList();
    }
}
