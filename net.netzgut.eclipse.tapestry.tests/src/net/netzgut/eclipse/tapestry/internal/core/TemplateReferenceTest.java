package net.netzgut.eclipse.tapestry.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import net.netzgut.eclipse.tapestry.internal.core.TemplateReference.Kind;

class TemplateReferenceTest {

    private static final String TEMPLATE = """
        <html xmlns:t="http://tapestry.apache.org/schema/tapestry_5_4.xsd"
              xmlns:p="tapestry:parameter" xmlns:fr="tapestry-library:frpc2">
          <t:layout.header title="x"/>
          <t:form t:id="loginForm"><t:submit id="go"/></t:form>
          <input t:type="textfield" t:id="city" t:mixins="autocomplete, zoneUpdater::before:x"/>
          <t:pagelink page="admin/Index">Admin</t:pagelink>
          <a t:type="pagelink" page="prop:target">dynamic</a>
          <fr:bar/>
          <h1>${message:page-title}</h1><t:label title="message:form.label"/> <span>errormessage:nope</span>
          <p:body>text with t:id="notAnId"</p:body>
          <!-- <t:commented t:id="hidden"/> -->
        </html>
        """;

    @Test
    void findsComponentTypes() {
        // ACT & ASSERT
        assertReference(Kind.COMPONENT, "layout/header", "layout.header");
        assertReference(Kind.COMPONENT, "form", "form t:id");
        assertReference(Kind.COMPONENT, "textfield", "textfield");
        assertReference(Kind.COMPONENT, "frpc2/bar", "bar/>");

        // ARRANGE
        // closing tag
        int closingTagPosition = TEMPLATE.indexOf("form>") + 1;

        // ACT
        TemplateReference closingTag = TemplateReference.at(TEMPLATE, closingTagPosition);

        // ASSERT
        assertEquals("form", closingTag.value());
    }

    @Test
    void findsMixinsIdsAndPages() {
        // ACT & ASSERT
        assertReference(Kind.MIXIN, "autocomplete", "autocomplete");
        assertReference(Kind.MIXIN, "zoneUpdater", "zoneUpdater");
        assertReference(Kind.COMPONENT_ID, "loginForm", "loginForm");
        assertReference(Kind.COMPONENT_ID, "go", "go\"");
        assertReference(Kind.PAGE, "admin/Index", "admin/Index");
    }

    @Test
    void findsMessageKeysInExpansionsAndAttributes() {
        // ACT & ASSERT
        assertReference(Kind.MESSAGE, "page-title", "page-title");
        assertReference(Kind.MESSAGE, "form.label", "form.label");
        assertNull(at("nope"));
    }

    @ParameterizedTest
    @ValueSource(
        strings = { "target", //
                    "notAnId",
                    "body>",
                    "commented",
                    "Admin<" })
    void ignoresBindingsTextStructureAndComments(String textAtCursor) {
        // ACT
        TemplateReference result = at(textAtCursor);

        // ASSERT
        assertNull(result);
    }

    @Test
    void reportsPreciseRegions() {
        // ACT
        TemplateReference mixin = at("zoneUpdater");

        // ASSERT
        String region = TEMPLATE.substring(mixin.offset(), mixin.offset() + mixin.length());
        assertEquals("zoneUpdater", region);
    }

    @Test
    void listsComponentIds() {
        // ACT
        List<String> ids = TemplateReference.componentIds(TEMPLATE).stream().map(TemplateReference::value).toList();

        // ASSERT
        assertEquals(List.of("loginForm", "go", "city"), ids);
    }

    @Test
    void toleratesUnfinishedTags() {
        // ARRANGE
        String editing =
            "<html xmlns:t=\"http://tapestry.apache.org/schema/tapestry_5_4.xsd\"><t:grid t:id=\"items\" sou";
        int position = editing.indexOf("grid");

        // ACT
        TemplateReference reference = TemplateReference.at(editing, position);

        // ASSERT
        assertEquals("grid", reference.value());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        # NAMESPACE URI                                       | IS TAPESTRY NAMESPACE
        http://tapestry.apache.org/schema/tapestry_5_4.xsd    | true
        https://tapestry.apache.org/schema/tapestry_5_0_0.xsd | true
        http://tapestry.apache.org/schema/tapestry_4.xsd      | false
        tapestry:parameter                                    | false
        ''                                                    | false
        """)
    void recognizesTapestryNamespacesOfAnySchemaVersion(String uri, boolean isTapestryNamespace) {
        // ACT
        boolean result = TemplateReference.isTapestryNamespace(uri);

        // ASSERT
        assertEquals(isTapestryNamespace, result);
    }

    @Test
    void walksTagsSkippingComments() {
        // ARRANGE
        String template = "<a><!-- <b> --><c/>text<!-- unclosed <d>";
        List<Integer> starts = new ArrayList<>();

        // ACT
        for (int start = TemplateReference.nextTag(template, 0); start >= 0; start =
            TemplateReference.nextTag(template, start + 1)) {
            starts.add(start);
        }

        // ASSERT
        assertEquals(List.of(0, 15), starts);
    }

    private static void assertReference(Kind kind, String value, String textAtCursor) {
        TemplateReference reference = at(textAtCursor);
        Kind actualKind = null;
        if (reference != null) {
            actualKind = reference.kind();
        }

        assertEquals(kind, actualKind, textAtCursor);
        assertEquals(value, reference.value());
    }

    private static TemplateReference at(String textAtCursor) {
        int position = TEMPLATE.indexOf(textAtCursor);
        if (position < 0) {
            throw new IllegalArgumentException(textAtCursor);
        }
        return TemplateReference.at(TEMPLATE, position + 1);
    }
}
