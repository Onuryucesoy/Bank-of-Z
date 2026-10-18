package com.ibm.bankofz.core.service;

import com.ibm.bankofz.core.model.Account;
import com.ibm.bankofz.core.model.AccountKey;

/** Write side of the CICS account transactions. */
public interface AccountCommandService {

    /** CREACC: allocates the account number (counter BANKZACCT), inserts ACCOUNT and PROCTRAN 'OCA'. */
    TransactionResult<Account> createAccount(Account request);

    /** UPDACC: updates type, interest rate and overdraft limit. */
    TransactionResult<Account> updateAccount(Account request);

    /** DELACC: deletes the account and writes PROCTRAN 'ODA'. */
    TransactionResult<Account> deleteAccount(AccountKey key);
}
