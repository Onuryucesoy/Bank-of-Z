package com.ibm.bankofz.core.service;

import com.ibm.bankofz.core.model.Customer;

import java.util.OptionalInt;

/**
 * One of the CRDTAGY1-5 credit agencies. Each receives the CUSTOMER record in
 * its container (CIPA..CIPE) and returns it with CUSTOMER-CREDIT-SCORE set
 * (1..998). Empty means the agency did not answer within the deadline.
 */
public interface CreditAgency {

    /** 1..5, matching CRDTAGYn / transaction OCRn / container CIPA+n-1. */
    int agencyNumber();

    OptionalInt score(Customer applicant);
}
