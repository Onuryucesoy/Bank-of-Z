package com.ibm.bankofz.core.service;

import com.ibm.bankofz.core.model.Account;
import com.ibm.bankofz.core.model.AccountKey;
import com.ibm.bankofz.core.model.ProcTran;

import java.util.List;

/** Read side of the CICS account transactions. */
public interface AccountQueryService {

    /** INQACC (COMMAREA INQACC). Account number 99999999 means "highest account number". */
    TransactionResult<Account> inquireAccount(AccountKey key);

    /** INQTRANL: PROCTRAN history for an account, newest first. */
    TransactionResult<List<ProcTran>> transactions(AccountKey key, int limit, int offset);
}
