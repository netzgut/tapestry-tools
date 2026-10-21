package net.netzgut.eclipse.tapestry.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TapestryConventionsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        # BASE NAME    | CLASS NAME | MATCHES
        Index          | Index      | true
        Index_de       | Index      | true
        Index_B5       | Index      | true
        Index_B5_de_AT | Index      | true
        IndexPage      | Index      | false
        Index          | IndexPage  | false
        """)
    void matchesFilesWithSuffixes(String fileBaseName, String className, boolean matches) {
        // ACT
        boolean result = TapestryConventions.belongsTo(fileBaseName, className);

        // ASSERT
        assertEquals(matches, result);
    }

    @Test
    void listsCandidateClassNamesMostSpecificFirst() {
        // ACT & ASSERT
        assertEquals(List.of("Index"), TapestryConventions.candidateClassNames("Index"));
        assertEquals(List.of("Index_B5_de", "Index_B5", "Index"),
                     TapestryConventions.candidateClassNames("Index_B5_de"));
        assertEquals(List.of("com.my_app.Layout_de", "com.my_app.Layout"),
                     TapestryConventions.candidateClassNames("com.my_app.Layout_de"));
        assertEquals(List.of("_Private"), TapestryConventions.candidateClassNames("_Private"));
    }

    @Test
    void renamesFilesKeepingSuffixesAndExtension() {
        // ACT & ASSERT
        assertEquals("Start.tml", TapestryConventions.renameFile("Index.tml", "Index", "Start"));
        assertEquals("Start_de_AT.properties",
                     TapestryConventions.renameFile("Index_de_AT.properties", "Index", "Start"));
        assertEquals("Start_B5_de.tml", TapestryConventions.renameFile("Index_B5_de.tml", "Index", "Start"));
        assertEquals("com.example.Start_de.tml",
                     TapestryConventions.renameFile("com.example.Index_de.tml",
                                                    "com.example.Index",
                                                    "com.example.Start"));
        assertNull(TapestryConventions.renameFile("IndexHelper.tml", "Index", "Start"));
    }

    @Test
    void splitsPreferenceLists() {
        // ACT & ASSERT
        assertEquals(List.of("overrides", "custom"), TapestryConventions.splitList(" overrides, custom ,overrides"));
        assertEquals(List.of("bak", "orig"), TapestryConventions.splitList(".bak .orig"));
        assertEquals(List.of(), TapestryConventions.splitList(" , "));
        assertTrue(TapestryConventions.isValidName("overrides"));
        assertFalse(TapestryConventions.isValidName("a/b"));
    }

    @Test
    void computesLogicalNamesLikeTapestry() {
        // ACT & ASSERT
        assertEquals(Set.of("admin/Users", "admin/AdminUsers"),
                     TapestryConventions.logicalNames(true, "", "admin.AdminUsers"));
        assertEquals(Set.of("admin/Users", "admin/UsersAdmin"),
                     TapestryConventions.logicalNames(true, "", "admin.UsersAdmin"));
        assertEquals(Set.of("admin/Index", "admin/AdminIndex", "admin"),
                     TapestryConventions.logicalNames(true, "", "admin.AdminIndex"));
        assertEquals(Set.of("ProductPage", "Product"), TapestryConventions.logicalNames(true, "", "ProductPage"));
        assertEquals(Set.of("Index"), TapestryConventions.logicalNames(true, "", "Index"), "start page has no folder");
        assertEquals(Set.of("admin/Admin"),
                     TapestryConventions.logicalNames(true, "", "admin.Admin"),
                     "stripping everything keeps the name");
        // components: no page rules; library prefixes are stripped like folders
        assertEquals(Set.of("layout/Header", "layout/LayoutHeader"),
                     TapestryConventions.logicalNames(false, "", "layout.LayoutHeader"));
        assertEquals(Set.of("frpc2/Bar", "frpc2/Frpc2Bar"),
                     TapestryConventions.logicalNames(false, "frpc2", "Frpc2Bar"));
        assertEquals(Set.of("ProductPage"), TapestryConventions.logicalNames(false, "", "ProductPage"));
    }

    // ComponentClassResolverImpl's pattern
    private static final Pattern PATTERN_COMPONENT_CLASS_RESOLVER =
        Pattern.compile(".*/?\\w+page$", Pattern.CASE_INSENSITIVE);

    @ParameterizedTest
    @ValueSource(
        strings = { "ProductPage",
                    "admin/userpage",
                    "admin/USERPAGE",
                    "x_page",
                    "Item2Page",
                    "Page",
                    "page",
                    "admin/Page",
                    "admin-Page",
                    "Pages",
                    "Product",
                    "",
                    "äPage" })
    void recognizesPageSuffixLikeTapestry(String name) {
        // ARRANGE
        boolean expected = PATTERN_COMPONENT_CLASS_RESOLVER.matcher(name).matches();

        // ACT
        boolean result = TapestryConventions.endsWithPage(name);

        // ASSERT
        assertEquals(expected, result);
    }
}
