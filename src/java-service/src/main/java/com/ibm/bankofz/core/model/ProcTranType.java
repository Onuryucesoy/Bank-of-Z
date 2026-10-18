package com.ibm.bankofz.core.model;

/** PROC-TRAN-TYPE 88-level values from PROCTRAN.cpy. */
public enum ProcTranType {
    CHEQUE_ACKNOWLEDGED("CHA"),
    CHEQUE_FAILURE("CHF"),
    CHEQUE_PAID_IN("CHI"),
    CHEQUE_PAID_OUT("CHO"),
    CREDIT("CRE"),
    DEBIT("DEB"),
    WEB_CREATE_ACCOUNT("ICA"),
    WEB_CREATE_CUSTOMER("ICC"),
    WEB_DELETE_ACCOUNT("IDA"),
    WEB_DELETE_CUSTOMER("IDC"),
    BRANCH_CREATE_ACCOUNT("OCA"),
    BRANCH_CREATE_CUSTOMER("OCC"),
    BRANCH_DELETE_ACCOUNT("ODA"),
    BRANCH_DELETE_CUSTOMER("ODC"),
    CREATE_SODD("OCS"),
    PAYMENT_CREDIT("PCR"),
    PAYMENT_DEBIT("PDR"),
    TRANSFER("TFR");

    private final String code;

    ProcTranType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ProcTranType fromCode(String code) {
        for (ProcTranType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown PROC-TRAN-TYPE: " + code);
    }
}
