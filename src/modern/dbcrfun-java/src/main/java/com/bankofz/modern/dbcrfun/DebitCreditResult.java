package com.bankofz.modern.dbcrfun;

import java.math.BigDecimal;

/**
 * Output half of the PAYDBCR COMMAREA.
 *
 * @param success          COMM-SUCCESS ('Y' or 'N')
 * @param failCode         COMM-FAIL-CODE, see {@link FailCode}
 * @param sortCode         COMM-SORTC, always the installation sort code
 * @param availableBalance COMM-AV-BAL; null when DBCRFUN leaves it untouched (lookup or rule failures)
 * @param actualBalance    COMM-ACT-BAL; null when DBCRFUN leaves it untouched
 */
public record DebitCreditResult(
        boolean success,
        char failCode,
        String sortCode,
        BigDecimal availableBalance,
        BigDecimal actualBalance) {

    /** COMM-FAIL-CODE values set by DBCRFUN. */
    public static final class FailCode {
        public static final char NONE = '0';
        public static final char ACCOUNT_NOT_FOUND = '1';
        public static final char DATASTORE_ERROR = '2';
        public static final char INSUFFICIENT_FUNDS = '3';
        public static final char PAYMENT_NOT_ALLOWED_FOR_ACCOUNT_TYPE = '4';

        private FailCode() {
        }
    }
}
