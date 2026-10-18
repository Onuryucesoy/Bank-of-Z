package com.ibm.bankofz.core.persistence;

import com.ibm.bankofz.core.model.Customer;
import com.ibm.bankofz.core.model.CustomerKey;

import java.util.Optional;

/** BANKZ.CUSTOMER persistence, one method per SQL statement the CICS programs issue. */
public interface CustomerRepository {

    /** INQCUST / DELCUS / UPDCUST: SELECT ... WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ?. */
    Optional<Customer> find(CustomerKey key);

    /** INQCUST customer number 9999999999: ORDER BY CUSTOMER_NUMBER DESC FETCH FIRST 1 ROW ONLY. */
    Optional<Customer> findHighest(String sortCode);

    /** CRECUST: INSERT INTO CUSTOMER. */
    void insert(Customer customer);

    /**
     * UPDCUST: updates title, names, DOB, phone, address and status only;
     * the key, created date and credit-score fields are left as stored.
     * Returns rows affected.
     */
    int update(Customer customer);

    /** DELCUS: DELETE FROM CUSTOMER WHERE ...; returns rows affected. */
    int delete(CustomerKey key);
}
