package net.netzgut.eclipse.tapestry.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TemplateParserTest {

    private static final String TEMPLATE = """
        <!DOCTYPE html>
        <html xmlns:t="http://tapestry.apache.org/schema/tapestry_5_4.xsd"
              xmlns:p="tapestry:parameter"
              xmlns:lib="tapestry-library:shop">
        <body>
          <p>&nbsp;&copy; &amp;</p>
          <t:form t:id="loginForm">
            <t:submit/>
          </t:form>
          <form t:type="form" t:id="search"></form>
          <t:actionlink id="delete">x</t:actionlink>
          <a t:type="eventlink" t:id="reload" event="refresh">x</a>
          <t:eventlink t:id="dynamic" event="prop:eventName">x</t:eventlink>
          <input t:type="textfield" t:id="city" t:mixins="autocomplete::before:other, zoneRefresh"/>
          <div t:id="widget"/>
          <lib:teaser t:id="teaser"/>
          <t:container><t:block id="b"/><t:parameter name="x"/><p:body/></t:container>
        </body>
        </html>
        """;

    @Test
    void extractsComponents() {
        // ACT
        List<ComponentUsage> components = TemplateParser.parse(TEMPLATE);

        // ASSERT
        assertEquals(List.of(new ComponentUsage("loginForm", "form", List.of(), Set.of()),
                             new ComponentUsage(null, "submit", List.of(), Set.of()),
                             new ComponentUsage("search", "form", List.of(), Set.of()),
                             new ComponentUsage("delete", "actionlink", List.of(), Set.of()),
                             new ComponentUsage("reload", "eventlink", List.of(), Set.of("refresh")),
                             new ComponentUsage("dynamic", "eventlink", List.of(), Set.of()),
                             new ComponentUsage("city", "textfield", List.of("autocomplete", "zoneRefresh"), Set.of()),
                             new ComponentUsage("widget", null, List.of(), Set.of()),
                             new ComponentUsage("teaser", "shop/teaser", List.of(), Set.of())),
                     components);
    }

    @Test
    void keepsComponentsBeforeSyntaxError() {
        // ARRANGE
        String template = """
            <html xmlns:t="http://tapestry.apache.org/schema/tapestry_5_4.xsd">
              <t:actionlink t:id="ok"/>
              <div <<< broken
            """;

        // ACT
        List<ComponentUsage> components = TemplateParser.parse(template);

        // ASSERT
        assertEquals(List.of(new ComponentUsage("ok", "actionlink", List.of(), Set.of())), components);
    }
}
