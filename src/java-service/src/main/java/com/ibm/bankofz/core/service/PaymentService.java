package com.ibm.bankofz.core.service;

import com.ibm.bankofz.core.model.Account;
import com.ibm.bankofz.core.model.AccountKey;

import java.math.BigDecimal;

/** Money-movement transactions. */
public interface PaymentService {

    /** Channel that originated a DBCRFUN call; COMM-FACILTYPE 496 is the z/OS Connect / web path. */
    enum Origin { TELLER, WEB_PAYMENT }

    /**
     * DBCRFUN (COMMAREA PAYDBCR): positive amount credits, negative debits.
     * PROCTRAN type CRE/DEB for tellers, PCR/PDR for {@link Origin#WEB_PAYMENT}.
     */
    TransactionResult<Account> debitCredit(AccountKey account, BigDecimal amount, Origin origin, String description);

    /** XFRFUN (COMMAREA XFRFUN): both legs in one unit of work; amount must be &gt; 0 (fail '4'). */
    TransactionResult<TransferBalances> transfer(AccountKey from, AccountKey to, BigDecimal amount);

    /** Post-transfer balances returned in COMM-FAVBAL/FACTBAL/TAVBAL/TACTBAL. */
    record TransferBalances(Account from, Account to) {
    }
}
