package com.faridfaharaj.profitable.commands;

import java.util.Optional;
import java.util.OptionalInt;

final class WalletTransferAmounts {

    private WalletTransferAmounts() {
    }

    static Optional<DecimalAmounts> decimal(double gross, double fee) {
        if (!Double.isFinite(gross) || gross <= 0 || !Double.isFinite(fee) || fee < 0) {
            return Optional.empty();
        }
        double net = gross - fee;
        if (!Double.isFinite(net) || net <= 0) {
            return Optional.empty();
        }
        return Optional.of(new DecimalAmounts(gross, fee, net));
    }

    static Optional<IntegralAmounts> integral(double gross, double fee) {
        if (!Double.isFinite(gross) || gross <= 0 || gross > Integer.MAX_VALUE || gross != Math.rint(gross)
                || !Double.isFinite(fee) || fee < 0) {
            return Optional.empty();
        }
        long roundedFee = (long) Math.ceil(fee);
        long net = (long) gross - roundedFee;
        if (roundedFee > Integer.MAX_VALUE || net <= 0 || net > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        return Optional.of(new IntegralAmounts((int) gross, (int) roundedFee, (int) net));
    }

    static OptionalInt physicalUnits(double amount) {
        if (!Double.isFinite(amount) || amount <= 0 || amount > Integer.MAX_VALUE
                || amount != Math.rint(amount)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) amount);
    }

    record DecimalAmounts(double gross, double fee, double net) {
    }

    record IntegralAmounts(int gross, int fee, int net) {
    }
}
