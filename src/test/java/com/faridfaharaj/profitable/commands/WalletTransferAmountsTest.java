package com.faridfaharaj.profitable.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletTransferAmountsTest {

    @Test
    void calculatesDecimalGrossFeeAndNet() {
        var amounts = WalletTransferAmounts.decimal(10.5, 0.5).orElseThrow();

        assertEquals(10.5, amounts.gross());
        assertEquals(0.5, amounts.fee());
        assertEquals(10.0, amounts.net());
    }

    @Test
    void calculatesIntegralAmountsWithCeilingFee() {
        var amounts = WalletTransferAmounts.integral(10, 1.01).orElseThrow();

        assertEquals(10, amounts.gross());
        assertEquals(2, amounts.fee());
        assertEquals(8, amounts.net());
    }

    @Test
    void rejectsFractionalNonPositiveAndOversizedPlayerPointsAmounts() {
        assertTrue(WalletTransferAmounts.integral(1.5, 0).isEmpty());
        assertTrue(WalletTransferAmounts.integral(0, 0).isEmpty());
        assertTrue(WalletTransferAmounts.integral((double) Integer.MAX_VALUE + 1, 0).isEmpty());
        assertTrue(WalletTransferAmounts.integral(Double.NaN, 0).isEmpty());
    }

    @Test
    void rejectsTransfersWhoseFeeConsumesTheGrossAmount() {
        assertTrue(WalletTransferAmounts.decimal(5, 5).isEmpty());
        assertTrue(WalletTransferAmounts.integral(5, 4.1).isEmpty());
    }

    @Test
    void acceptsMaximumIntegralGrossWhenNetRemainsPositive() {
        var amounts = WalletTransferAmounts.integral(Integer.MAX_VALUE, 0).orElseThrow();

        assertEquals(Integer.MAX_VALUE, amounts.gross());
        assertEquals(Integer.MAX_VALUE, amounts.net());
    }

    @Test
    void physicalAssetsRequirePositiveBoundedWholeUnits() {
        assertEquals(1, WalletTransferAmounts.physicalUnits(1).orElseThrow());
        assertEquals(Integer.MAX_VALUE,
                WalletTransferAmounts.physicalUnits(Integer.MAX_VALUE).orElseThrow());

        assertTrue(WalletTransferAmounts.physicalUnits(1.5).isEmpty());
        assertTrue(WalletTransferAmounts.physicalUnits(0).isEmpty());
        assertTrue(WalletTransferAmounts.physicalUnits(-1).isEmpty());
        assertTrue(WalletTransferAmounts.physicalUnits((double) Integer.MAX_VALUE + 1).isEmpty());
        assertTrue(WalletTransferAmounts.physicalUnits(Double.POSITIVE_INFINITY).isEmpty());
    }
}
