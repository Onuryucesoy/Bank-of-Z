package com.ibm.bankofz.core.mapper;

import com.ibm.bankofz.core.model.CobolDate;
import com.ibm.bankofz.core.model.Customer;
import com.ibm.bankofz.core.model.CustomerRow;

/**
 * CUSTOMER record &lt;-&gt; BANKZ.CUSTOMER row, mirroring the MOVEs in
 * CRECUST (insert) and INQCUST/DELCUS (select): CHAR keys zero-padded,
 * dates as INTEGER YYYYMMDD.
 */
public final class CustomerRowMapper {

    private CustomerRowMapper() {
    }

    public static CustomerRow toRow(Customer c) {
        return new CustomerRow(c.eyecatcher(), c.sortCode(), String.format("%010d", c.customerNumber()),
                c.title(), c.firstName(), c.lastName(), yyyymmdd(c.dateOfBirth()), c.phone(),
                c.addressLine1(), c.addressLine2(), c.city(), c.postcode(), c.country(), c.status(),
                yyyymmdd(c.created()), (short) c.creditScore(), yyyymmdd(c.creditScoreReviewDate()));
    }

    public static Customer fromRow(CustomerRow r) {
        return new Customer(r.eyecatcher(), r.sortCode(), Long.parseLong(r.customerNumber().trim()),
                r.title(), r.firstName(), r.lastName(), date(r.dateOfBirth()), r.phone(),
                r.addressLine1(), r.addressLine2(), r.city(), r.postcode(), r.country(), r.status(),
                date(r.createdDate()), r.creditScore() == null ? 0 : r.creditScore(),
                date(r.creditScoreReviewDate()));
    }

    private static Integer yyyymmdd(CobolDate d) {
        return d == null ? 0 : (int) d.toYyyyMmDd();
    }

    private static CobolDate date(Integer v) {
        return v == null ? CobolDate.ZERO : CobolDate.ofYyyyMmDd(v);
    }
}
