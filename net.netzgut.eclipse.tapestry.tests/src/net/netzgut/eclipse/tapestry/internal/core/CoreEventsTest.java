package net.netzgut.eclipse.tapestry.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class CoreEventsTest {

    static Stream<Arguments> provideKnowsFieldEvents() {
        return Stream.of(Arguments.of("TextField", List.of("toClient", "validate", "parseClient")),
                         Arguments.of("core/textarea", List.of("toClient", "validate", "parseClient")),
                         Arguments.of("Checkbox", List.of("validate")),
                         Arguments.of("Palette", List.of("validate")));
    }

    @ParameterizedTest
    @MethodSource("provideKnowsFieldEvents")
    void knowsFieldEvents(String name, List<String> expected) {
        // ACT
        List<String> result = CoreEvents.componentEvents(name);

        // ASSERT
        assertEquals(expected, result);
    }

    @Test
    void ignoresComponentsWithoutEvents() {
        // ACT
        List<String> gridEvents = CoreEvents.componentEvents("Grid");
        List<String> unknownMixinEvents = CoreEvents.mixinEvents("unknown");

        // ASSERT
        assertTrue(gridEvents.isEmpty());
        assertTrue(unknownMixinEvents.isEmpty());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        # EVENT     | RETURN TYPE
        toClient    | String
        parseClient | Object
        success     | Object
        validate    | void
        """)
    void choosesReturnTypes(String event, String expectedReturnType) {
        // ACT
        String result = CoreEvents.returnType(event);

        // ACT
        assertEquals(expectedReturnType, result);
    }
}
