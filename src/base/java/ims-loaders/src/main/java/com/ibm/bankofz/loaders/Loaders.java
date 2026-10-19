package com.ibm.bankofz.loaders;

import com.ibm.bankofz.loaders.LoaderSpec.Seg;
import com.ibm.bankofz.loaders.LoaderSpec.Txt;

import java.util.List;
import java.util.Map;

import static com.ibm.bankofz.loaders.LoaderSpec.Kind.CHAR;
import static com.ibm.bankofz.loaders.LoaderSpec.Kind.COMP3;
import static com.ibm.bankofz.loaders.LoaderSpec.Kind.COMP5;

/** The five loaders, transcribed from src/base/ims/cobol/LOAD*.cbl (PSB IBLOAD). */
final class Loaders {
    private Loaders() {
    }

    static final LoaderSpec LOADACCT = new LoaderSpec("LOADACCT", "ACCTIN", "ACCOUNT", "ACCOUNT",
            List.of(new Txt("TXT-ACCID", 19), new Txt("TXT-ACCTYPE", 1), new Txt("TXT-BALANCE", 16),
                    new Txt("TXT-LASTTXID", 19)),
            List.of(new Seg("ACCID", COMP5, 18, 0, "TXT-ACCID"), new Seg("ACCTYPE", CHAR, 1, 0, "TXT-ACCTYPE"),
                    new Seg("BALANCE", COMP3, 15, 2, "TXT-BALANCE"),
                    new Seg("LASTTXID", COMP5, 18, 0, "TXT-LASTTXID")));

    static final LoaderSpec LOADCUSA = new LoaderSpec("LOADCUSA", "CUSAIN", "CUSTACCS", "CUSTACCS",
            List.of(new Txt("TXT-ACCID", 19), new Txt("TXT-CUSTID", 9), new Txt("TXT-ACCNUM", 9)),
            List.of(new Seg("CUSTID", COMP5, 9, 0, "TXT-CUSTID"), new Seg("ACCID", COMP5, 18, 0, "TXT-ACCID"),
                    new Seg("ACCNUM", COMP5, 9, 0, "TXT-ACCNUM")));

    static final LoaderSpec LOADCUST = new LoaderSpec("LOADCUST", "CUSTIN", "CUSTOMER", "CUSTOMER",
            List.of(new Txt("TXT-CUSTID", 9), new Txt("TXT-LASTNAME", 50), new Txt("TXT-FIRSTNM", 50),
                    new Txt("TXT-ADDRESS", 80), new Txt("TXT-CITY", 25), new Txt("TXT-STATE", 2),
                    new Txt("TXT-ZIPCODE", 15), new Txt("TXT-PHONE", 12), new Txt("TXT-STATUS", 1),
                    new Txt("TXT-PASSWORD", 16), new Txt("TXT-CUSTTYPE", 1), new Txt("TXT-LASTLOGI", 23)),
            List.of(new Seg("CUSTID", COMP5, 9, 0, "TXT-CUSTID"), new Seg("LASTNAME", CHAR, 50, 0, "TXT-LASTNAME"),
                    new Seg("FIRSTNM", CHAR, 50, 0, "TXT-FIRSTNM"), new Seg("ADDRESS", CHAR, 80, 0, "TXT-ADDRESS"),
                    new Seg("CITY", CHAR, 25, 0, "TXT-CITY"), new Seg("STATE", CHAR, 2, 0, "TXT-STATE"),
                    new Seg("ZIPCODE", CHAR, 15, 0, "TXT-ZIPCODE"), new Seg("PHONE", CHAR, 12, 0, "TXT-PHONE"),
                    new Seg("STATUS", CHAR, 1, 0, "TXT-STATUS"), new Seg("PASSWORD", CHAR, 16, 0, "TXT-PASSWORD"),
                    new Seg("CUSTTYPE", CHAR, 1, 0, "TXT-CUSTTYPE"),
                    new Seg("LASTLOGI", CHAR, 23, 0, "TXT-LASTLOGI")));

    static final LoaderSpec LOADHIST = new LoaderSpec("LOADHIST", "HISTIN", "HISTORY", "HISTORY",
            List.of(new Txt("TXT-TXID", 19), new Txt("TXT-TIMESTMP", 23), new Txt("TXT-TRANSTYP", 1),
                    new Txt("TXT-AMOUNT", 16), new Txt("TXT-REFTXID", 19), new Txt("TXT-ACCID", 19)),
            List.of(new Seg("TXID", COMP5, 18, 0, "TXT-TXID"), new Seg("TIMESTMP", CHAR, 23, 0, "TXT-TIMESTMP"),
                    new Seg("TRANSTYP", CHAR, 1, 0, "TXT-TRANSTYP"), new Seg("AMOUNT", COMP3, 15, 2, "TXT-AMOUNT"),
                    new Seg("REFTXID", COMP5, 18, 0, "TXT-REFTXID"), new Seg("ACCID", COMP5, 18, 0, "TXT-ACCID")));

    static final LoaderSpec LOADTSTA = new LoaderSpec("LOADTSTA", "TSTAIN", "TSTAT", "TSTAT",
            List.of(new Txt("TXT-TXID", 19), new Txt("TXT-STATUS", 1), new Txt("TXT-STARTTIME", 23),
                    new Txt("TXT-STOPTIME", 23), new Txt("TXT-CUSTID", 9)),
            List.of(new Seg("TXID", COMP5, 18, 0, "TXT-TXID"), new Seg("STATUS", CHAR, 1, 0, "TXT-STATUS"),
                    new Seg("STARTTIME", CHAR, 23, 0, "TXT-STARTTIME"), new Seg("STOPTIME", CHAR, 23, 0, "TXT-STOPTIME"),
                    new Seg("CUSTID", COMP5, 9, 0, "TXT-CUSTID")));

    static final Map<String, LoaderSpec> ALL = Map.of("LOADACCT", LOADACCT, "LOADCUSA", LOADCUSA,
            "LOADCUST", LOADCUST, "LOADHIST", LOADHIST, "LOADTSTA", LOADTSTA);
}
