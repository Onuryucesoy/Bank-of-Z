package com.ibm.bankofz.core.service;

import java.util.Optional;

/**
 * Outcome of a ported transaction in COBOL terms: the success flag ('Y' /
 * 'N' or ' ') and single-character fail code each COMMAREA returns, plus the
 * payload. Preserving the raw codes keeps golden-master comparison exact.
 */
public record TransactionResult<T>(boolean success, char failCode, Optional<T> payload) {

    public static <T> TransactionResult<T> ok(T payload) {
        return new TransactionResult<>(true, ' ', Optional.ofNullable(payload));
    }

    public static <T> TransactionResult<T> failed(char failCode) {
        return new TransactionResult<>(false, failCode, Optional.empty());
    }
}
