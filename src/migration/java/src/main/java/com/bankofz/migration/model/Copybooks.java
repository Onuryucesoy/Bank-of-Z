package com.bankofz.migration.model;

/** Repository-relative paths of the canonical copybooks the DTOs are derived from. */
public final class Copybooks {

    public static final String CUSTOMER = "src/base/cics/copy/CUSTOMER.cpy";
    public static final String ACCOUNT = "src/base/cics/copy/ACCOUNT.cpy";
    public static final String NEWCUSNO = "src/base/cics/copy/NEWCUSNO.cpy";
    public static final String NEWACCNO = "src/base/cics/copy/NEWACCNO.cpy";
    public static final String IBGHIST = "src/base/ims/copy/IBGHIST.cpy";

    private Copybooks() {
    }
}
