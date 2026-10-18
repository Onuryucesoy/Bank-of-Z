package com.ibm.bankofz.core.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read side of the IMS transactions behind /ims/... (IBGCUDAT, IBACSUM).
 * Payloads are deliberately IMS-shaped: IMS customers are a different
 * population from CICS/Db2 customers and are not merged.
 */
public interface ImsCustomerQueryService {

    /** IBGCUDAT: customer details for an IMS customer ID. */
    TransactionResult<ImsCustomer> customer(String customerId);

    /** IBACSUM: account summaries with balances for an IMS customer. */
    TransactionResult<List<ImsAccountSummary>> accountSummary(String customerId);

    /** IBGCUDAT OUTPUT-AREA: CUSTID-OUT X(9), FIRSTNAME/LASTNAME X(50), ADDRESS X(80), CITY X(25), ZIPCODE X(15), PHONE X(12). */
    record ImsCustomer(String customerId, String firstName, String lastName, String address,
                       String city, String zipCode, String phone) {
    }

    record ImsAccountSummary(String accountId, String accountType, BigDecimal balance) {
    }
}
