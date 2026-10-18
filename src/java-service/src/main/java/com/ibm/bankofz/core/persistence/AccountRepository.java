package com.ibm.bankofz.core.persistence;

import com.ibm.bankofz.core.model.Account;
import com.ibm.bankofz.core.model.AccountKey;
import com.ibm.bankofz.core.model.CustomerKey;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** BANKZ.ACCOUNT persistence. */
public interface AccountRepository {

    /** INQACC: SELECT ... WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?. */
    Optional<Account> find(AccountKey key);

    /** INQACC account number 99999999: ORDER BY ACCOUNT_NUMBER DESC FETCH FIRST 1 ROWS ONLY. */
    Optional<Account> findHighest(String sortCode);

    /**
     * INQACCCU: accounts of a customer ordered by account number, capped at
     * {@code limit} (COMM-ACCOUNT-DETAILS OCCURS 1 TO 20).
     */
    List<Account> findByCustomer(CustomerKey customer, int limit);

    /** CREACC: INSERT INTO ACCOUNT. */
    void insert(Account account);

    /**
     * DBCRFUN / XFRFUN read before changing balances: locks the row for the
     * rest of the caller's {@link UnitOfWork} so a concurrent payment cannot
     * be lost (COBOL reads under CS without a lock).
     */
    Optional<Account> findForUpdate(AccountKey key);

    /** UPDACC: only ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE and ACCOUNT_OVERDRAFT_LIMIT; returns rows affected. */
    int updateDetails(Account account);

    /** DBCRFUN / XFRFUN: only the two balances; returns rows affected. */
    int updateBalances(AccountKey key, BigDecimal availableBalance, BigDecimal actualBalance);

    /** DELACC: DELETE FROM ACCOUNT ...; returns rows affected. */
    int delete(AccountKey key);
}
