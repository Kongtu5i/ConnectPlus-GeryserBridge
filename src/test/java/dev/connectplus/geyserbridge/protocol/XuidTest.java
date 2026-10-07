package dev.connectplus.geyserbridge.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XuidTest {

    @Test
    void acceptsValidUnsigned64Range() {
        assertTrue(Xuid.isValid("1"));
        assertTrue(Xuid.isValid("12345678901234567890"));
        assertTrue(Xuid.isValid("18446744073709551615")); // 2^64 - 1
    }

    @Test
    void rejectsZero() {
        assertFalse(Xuid.isValid("0"));
    }

    @Test
    void rejectsLeadingZeros() {
        assertFalse(Xuid.isValid("01"));
        assertFalse(Xuid.isValid("00000000000000000001"));
    }

    @Test
    void rejectsOverflow() {
        assertFalse(Xuid.isValid("18446744073709551616")); // 2^64
        assertFalse(Xuid.isValid("99999999999999999999999"));
    }

    @Test
    void rejectsNonDecimal() {
        assertFalse(Xuid.isValid(null));
        assertFalse(Xuid.isValid(""));
        assertFalse(Xuid.isValid(" 1"));
        assertFalse(Xuid.isValid("12a"));
        assertFalse(Xuid.isValid("0x10"));
        assertFalse(Xuid.isValid("-1"));
        assertFalse(Xuid.isValid("1.0"));
        assertFalse(Xuid.isValid("＋１２")); // 全角
    }

    @Test
    void requireValidThrowsWithMessage() {
        assertThrows(IllegalArgumentException.class, () -> Xuid.requireValid("0"));
        assertThrows(IllegalArgumentException.class, () -> Xuid.requireValid(null));
    }
}
