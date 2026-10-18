package com.ibm.bankofz.migration.api;

/**
 * The z/OS Connect assets under {@code src/api/src/main/zosAssets}; see {@code migration/operation-inventory.json}.
 * Each stub endpoint reports the asset it stands in for via the {@value StubResponses#BACKEND_HEADER} header.
 */
public enum ZosAsset {
    CRECUST(Backend.CICS), DBCRFUN(Backend.CICS), DELCUS(Backend.CICS), INQACC(Backend.CICS),
    INQACCCU(Backend.CICS), INQACCS(Backend.CICS), INQCUST(Backend.CICS), INQTRAND(Backend.CICS),
    INQTRANL(Backend.CICS), UPDCUST(Backend.CICS),
    IBACSUM(Backend.IMS), IBGCUDAT(Backend.IMS), IBSCUDAT(Backend.IMS), IBTRAN(Backend.IMS);

    public enum Backend { CICS, IMS }

    private final Backend backend;

    ZosAsset(Backend backend) {
        this.backend = backend;
    }

    public Backend backend() {
        return backend;
    }
}
