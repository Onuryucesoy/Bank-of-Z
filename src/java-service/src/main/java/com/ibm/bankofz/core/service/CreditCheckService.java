package com.ibm.bankofz.core.service;

import com.ibm.bankofz.core.model.Customer;

/**
 * CRECUST credit-check fan-out: run all five {@link CreditAgency}s
 * concurrently, wait up to the deadline (CRECUST: DELAY 3 seconds), and
 * average the scores that arrived. Zero answers is a failure.
 */
public interface CreditCheckService {

    /** Returns the applicant with credit score and review date set, or a failed result. */
    TransactionResult<Customer> check(Customer applicant);
}
