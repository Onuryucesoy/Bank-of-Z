package com.ibm.bankofz.core.persistence;

import com.ibm.bankofz.core.model.Account;
import com.ibm.bankofz.core.model.AccountKey;
import com.ibm.bankofz.core.model.CustomerKey;

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

    /** UPDACC / DBCRFUN / XFRFUN: UPDATE ACCOUNT SET ...; returns rows affected. */
    int update(Account account);

    /** DELACC: DELETE FROM ACCOUNT ...; returns rows affected. */
    int delete(AccountKey key);
}
