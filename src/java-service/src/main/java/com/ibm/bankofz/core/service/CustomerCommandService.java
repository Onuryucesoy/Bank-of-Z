package com.ibm.bankofz.core.service;

import com.ibm.bankofz.core.model.Customer;
import com.ibm.bankofz.core.model.CustomerKey;

/** Write side of the CICS customer transactions. Not implemented until the write phases. */
public interface CustomerCommandService {

    /**
     * CRECUST: allocates the customer number ({@link com.ibm.bankofz.core.id.IdAllocator},
     * counter BANKZCUST), runs the credit check fan-out ({@link CreditCheckService}),
     * inserts CUSTOMER and a PROCTRAN 'OCC' row.
     */
    TransactionResult<Customer> createCustomer(Customer request);

    /** UPDCUST: updates title, names, address, phone, DOB; never the created date or key. */
    TransactionResult<Customer> updateCustomer(Customer request);

    /** DELCUS: deletes every account (DELACC), then the customer, then writes PROCTRAN 'ODC'. */
    TransactionResult<Customer> deleteCustomer(CustomerKey key);
}
