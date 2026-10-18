package com.ibm.bankofz.core.persistence;

import com.ibm.bankofz.core.model.AccountKey;
import com.ibm.bankofz.core.model.ProcTran;

import java.util.List;

/** BANKZ.PROCTRAN audit log. Insert-only from transaction programs. */
public interface ProcTranRepository {

    /** Every write transaction: INSERT INTO PROCTRAN (failure abends HWPT in COBOL). */
    void insert(ProcTran entry);

    /** INQTRANL-style read: newest first, for one account. */
    List<ProcTran> findByAccount(AccountKey account, int limit);
}
