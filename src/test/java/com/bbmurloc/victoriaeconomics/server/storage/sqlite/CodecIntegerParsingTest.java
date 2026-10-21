package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Parsing optimizations must retain the exact v9 literal contract, not merely an equivalent numeric value. */
class CodecIntegerParsingTest {
    private static final UUID LOCATION = new UUID(0, 42);
    private static String inventory(String capacity) {
        return "{\"formatVersion\":9,\"snapshot\":{\"location\":\"" + LOCATION + "\",\"capacity\":" + capacity
                + ",\"quantities\":{},\"reservations\":{},\"settlements\":{}}}";
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0", "1", "9223372036854775807"})
    void exactLongLiteralsRoundTripWithoutChangingTheFormat(String literal) {
        var state = EconomyStateCodec.decodeInventory(LOCATION, inventory(literal)).state();
        assertEquals(Long.parseLong(literal), state.capacity());
        String encoded = EconomyStateCodec.encodeInventory(state);
        assertTrue(encoded.contains("\"formatVersion\":9"));
        assertEquals(state, EconomyStateCodec.decodeInventory(LOCATION, encoded).state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "1e0", "1E+1", "-0.0", "-0e0", "01", "+1", "9223372036854775808",
            "-9223372036854775809", "NaN", "Infinity", "\"1\"", "null", "true"})
    void fractionalExponentOverflowAndNonNumberTokensRemainRejected(String literal) {
        assertThrows(IllegalStateException.class, () -> EconomyStateCodec.decodeInventory(LOCATION, inventory(literal)));
    }
}
