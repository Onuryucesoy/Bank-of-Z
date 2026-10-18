package com.ibm.bankofz.core.service;

import com.ibm.bankofz.core.model.Account;
import com.ibm.bankofz.core.model.Customer;
import com.ibm.bankofz.core.model.CustomerKey;

import java.util.List;

/** Read side of the CICS customer transactions. Phase 2 implements these. */
public interface CustomerQueryService {

    /**
     * INQCUST (COMMAREA INQCUSTZ). Customer number 0 means "random existing
     * customer", 9999999999 means "highest customer number".
     */
    TransactionResult<Customer> inquireCustomer(CustomerKey key);

    /** INQACCCU (COMMAREA INQACCCU / INQACCCZ): up to 20 accounts; fail '4' when the customer is missing. */
    TransactionResult<List<Account>> accountsForCustomer(CustomerKey key);
}
