package com.faridfaharaj.profitable.data.tables;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountsSecurityTest {

    @Test
    void serverAndUuidDefaultAccountsCannotReceivePasswords() {
        assertTrue(Accounts.isProtectedAccount("server"));
        assertTrue(Accounts.isProtectedAccount(UUID.randomUUID().toString()));
        assertFalse(Accounts.isProtectedAccount("trading_account"));

        assertFalse(Accounts.changePassword(null, "server", "valid-password"));
        assertFalse(Accounts.changePassword(null, "trading_account", "short"));
    }

    @Test
    void passwordHashesUseRandomSaltAndRoundTrip() {
        byte[][] first = Accounts.hashPassword("correct horse battery staple");
        byte[][] second = Accounts.hashPassword("correct horse battery staple");

        assertFalse(MessageDigest.isEqual(first[1], second[1]));
        assertTrue(MessageDigest.isEqual(first[0],
                Accounts.hashPassword("correct horse battery staple", first[1])));
        assertFalse(MessageDigest.isEqual(first[0],
                Accounts.hashPassword("wrong password", first[1])));
    }

    @Test
    void malformedDeliveryLocationIsRejectedBeforeWorldLookup() {
        assertThrows(IOException.class, () -> Accounts.decodeLocation(new byte[39]));
        assertThrows(IOException.class, () -> Accounts.decodeLocation(new byte[41]));
    }
}
