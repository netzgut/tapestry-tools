package net.netzgut.eclipse.tapestry.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StringsTest {

    @Test
    void turnsBlankValuesIntoNull() {
        // ACT & ASSERT
        assertNull(Strings.blankToNull(null));
        assertNull(Strings.blankToNull(" \t"));
        assertEquals(" id ", Strings.blankToNull(" id "));
        assertNull(Strings.stripToNull(null));
        assertNull(Strings.stripToNull(""));
        assertEquals("id", Strings.stripToNull(" id "));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        # VALUE     | EXPECTED
        ''          | ''
        success     | Success
        userDetails | UserDetails
        URL         | URL
        x           | X
        """)
    void capitalizesLikeIntrospector(String value, String expected) {
        // ACT
        String result = Strings.capitalize(value);

        // ASSERT
        assertEquals(expected, result);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        # VALUE     | EXPECTED
        ''          | ''
        Success     | success
        UserDetails | userDetails
        URL         | URL
        X           | x
        """)
    void decapitalizesLikeIntrospector(String value, String expected) {
        // ACT
        String result = Strings.decapitalize(value);

        // ASSERT
        assertEquals(expected, result);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        # VALUE     | EXPECTED
        "success"   |success
        ""          | ''
        success     | success
        "           | "
        """)
    void unquotesOnlyQuotedValues(String value, String expected) {
        // ACT
        String result = Strings.unquote(value);

        // ASSERT
        assertEquals(expected, result);
    }

    @Test
    void escapesAndUnescapesHtml() {
        // ARRANGE
        String text = "a < b && c > \"d\"";

        // ACT
        String escaped = Strings.escapeHtml(text);
        String unescaped = Strings.unescapeHtml(escaped);

        // ASSERT
        assertEquals("a &lt; b &amp;&amp; c &gt; \"d\"", escaped);
        assertEquals(text, unescaped);
        assertEquals("&lt; \" ", Strings.unescapeHtml("&amp;lt; &quot;&nbsp;"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        # VALUE             | CHAR | COUNT
        Form                | /    | 0
        core/Form           | /    | 1
        deep/path/Component | /    | 2
        deep/path/Component | e    | 3
        """)
    void countCharacters(String value, char characterToCount, long expectedCount) {
        // ACT
        long result = Strings.count(value, characterToCount);

        // ASSERT
        assertEquals(expectedCount, result);
    }
}
