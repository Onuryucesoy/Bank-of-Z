package nazare.jmp.experimental.ims;

import com.ibm.ims.jdbc.IMSDataSource;

import nazare.jmp.experimental.ExperimentalFeatures;

/** Builds type-4 IMS Universal JDBC data sources for the read-only PSBs. */
public final class ImsDataSources {

    /** PSB with PROCOPT=G on CUSTOMER. */
    public static final String CUSTOMER_READ_PSB = "IBGCUDAT";
    /** PSB with PROCOPT=G on ACCOUNT and CUSTACCS. */
    public static final String ACCOUNT_READ_PSB = "IBACSUM";

    private ImsDataSources() {
    }

    /**
     * @param host   IMS Connect host
     * @param port   IMS Connect port
     * @param psb    PSB name; use {@link #CUSTOMER_READ_PSB} or {@link #ACCOUNT_READ_PSB}
     * @param user   RACF user (password is supplied when the connection is opened)
     */
    public static IMSDataSource type4(String host, int port, String datastoreName, String psb, String user) {
        ExperimentalFeatures.requireImsReadOnly();
        if (!CUSTOMER_READ_PSB.equals(psb) && !ACCOUNT_READ_PSB.equals(psb)) {
            throw new IllegalArgumentException("Only the read-only PSBs " + CUSTOMER_READ_PSB + " and "
                    + ACCOUNT_READ_PSB + " are allowed, got " + psb);
        }
        IMSDataSource ds = new IMSDataSource();
        ds.setDriverType(IMSDataSource.DRIVER_TYPE_4);
        ds.setDatastoreServer(host);
        ds.setPortNumber(port);
        ds.setDatastoreName(datastoreName);
        ds.setDatabaseName(psb);
        ds.setUser(user);
        return ds;
    }
}
