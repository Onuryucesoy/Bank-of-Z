# Phase 0 inventory

Everything under `src/base/`, as of this branch. The counts and caller lists come from grepping the sources (`COPY`, `EXEC SQL INCLUDE`, `EXEC CICS LINK PROGRAM(...)`, `CALL 'CBLTDLI'`, z/OS Connect `zosAsset.yaml`). Line numbers refer to the files at the time of writing.

## 1. CICS COBOL programs (`src/base/cics/cobol`, 32 files)

"API" means the program is a z/OS Connect provider (`src/api/src/main/zosAssets/<name>`). A program that isn't marked API can only be reached from a 3270 screen or through another program.

| Program | Lines | SQL stmts | API | Called by | Role |
|---|---:|---:|:-:|---|---|
| ABNDPROC | 176 | 0 | | every program's abend path | Writes abend records to a KSDS |
| BANKDATA | 1896 | 25 | | batch | Generates initial CUSTOMER/ACCOUNT Db2 data |
| BNKMENU | 1314 | 0 | | 3270 | Main menu (map BNK1MAI) |
| BNK1CAC | 1301 | 0 | | 3270 | Create account screen; LINKs CREACC |
| BNK1CCA | 979 | 0 | | 3270 | List a customer's accounts; LINKs INQACCCU |
| BNK1CCS | 1682 | 0 | | 3270 | Create customer screen; LINKs CRECUST |
| BNK1CRA | 1169 | 0 | | 3270 | Debit/credit screen; LINKs DBCRFUN |
| BNK1DAC | 1162 | 0 | | 3270 | Display/delete account; LINKs INQACC, DELACC |
| BNK1DCS | 2183 | 0 | | 3270 | Display/update/delete customer; LINKs INQCUST, UPDCUST, DELCUS |
| BNK1TFN | 1227 | 0 | | 3270 | Transfer screen; LINKs XFRFUN |
| BNK1UAC | 1408 | 0 | | 3270 | Update account; LINKs INQACC, UPDACC |
| CRDTAGY1-5 | ~260 each | 0 | | CRECUST via `RUN TRANSID` | Simulated credit agencies (channel/containers, random delay) |
| CREACC | 1340 | 9 | | BNK1CAC | Create account; LINKs INQCUST, INQACCCU |
| CRECUST | 1630 | 8 | Y | BNK1CCS, API | Create customer; starts CRDTAGY1-5 |
| DBCRFUN | 869 | 6 | Y | BNK1CRA, API | Debit/credit an account |
| DELACC | 649 | 6 | | BNK1DAC, DELCUS | Delete account |
| DELCUS | 904 | 6 | Y | BNK1DCS, API | Delete customer and its accounts; LINKs INQCUST, INQACCCU, DELACC |
| GETCOMPY | 43 | 0 | | | Returns company name |
| GETSCODE | 46 | 0 | | | Returns sort code |
| INQACC | 1002 | 7 | Y | BNK1DAC, BNK1UAC, API | Inquire one account |
| INQACCCU | 882 | 6 | Y | BNK1CCA, CREACC, DELCUS, API | Accounts for a customer; LINKs INQCUST |
| INQACCS | 413 | 6 | Y | API | All accounts, at most 20 |
| INQCUST | 771 | 4 | Y | BNK1DCS, CREACC, DELCUS, INQACCCU, API | Inquire customer |
| INQTRAND | 437 | 6 | Y | API | One PROCTRAN row by composite key |
| INQTRANL | 660 | 10 | Y | API | PROCTRAN list by account, date filter, limit at most 100 plus offset |
| UPDACC | 406 | 5 | | BNK1UAC | Update account type/rate/overdraft |
| UPDCUST | 457 | 4 | Y | BNK1DCS, API | Update customer |
| XFRFUN | 2069 | 10 | | BNK1TFN | Transfer between two accounts |

XFRFUN, UPDACC, CREACC and DELACC have no API. If the 3270 layer is retired before they get one, nothing can call them.

## 2. BMS maps (`src/base/cics/bms`, 10 files)

| File | Mapset | Map | Used by |
|---|---|---|---|
| BNK1ACC.bms | BNK1ACC | BNK1ACC | BNK1CCA |
| BNK1CAM.bms | BNK1CAM | BNK1CA | BNK1CAC |
| BNK1CCM.bms | BNK1CCM | BNK1CC | BNK1CCS |
| BNK1CDM.bms | BNK1CDM | BNK1CD | BNK1CRA |
| BNK1DAM.bms | BNK1DAM | BNK1DA | BNK1DAC |
| BNK1DCM.bms | BNK1DCM | BNK1DC | BNK1DCS |
| BNK1MAI.bms | BNK1MAI | BNK1ME | BNKMENU |
| BNK1TFM.bms | BNK1TFM | BNK1TF | BNK1TFN |
| BNK1UAM.bms | BNK1UAM | BNK1UA | BNK1UAC |
| BNK1B2M.bms | **BNK1TFM** | BNK1B2 | no program; listed in `.setup/deploy/Development.yml` and `.setup/zconfig/bank-of-z-definitions.yaml` |

`BNK1B2M.bms` declares the same mapset name (`BNK1TFM`) as `BNK1TFM.bms`. Whichever load module is deployed last wins. Confirm which one is live before mapping the transfer screen.

## 3. CICS copybooks (`src/base/cics/copy`, 42 files)

| Copybook | Included by |
|---|---|
| ABNDINFO | every CICS program except BANKDATA, GETCOMPY, GETSCODE |
| SORTCODE | 21 programs (all Db2 programs plus CRDTAGY*, GETSCODE) |
| ACCOUNT | CREACC DBCRFUN DELACC DELCUS INQACC INQACCCU UPDACC XFRFUN |
| CUSTOMER | BANKDATA CRDTAGY1-5 CREACC CRECUST DELCUS INQACCCU INQCUST UPDCUST |
| PROCTRAN | CREACC CRECUST DBCRFUN DELACC DELCUS XFRFUN |
| ACCDB2, CUSTDB2, PROCDB2, CONTDB2 | through `EXEC SQL INCLUDE` from the 15 Db2 programs (BANKDATA, CREACC, CRECUST, DBCRFUN, DELACC, DELCUS, INQACC, INQACCCU, INQACCS, INQCUST, INQTRAND, INQTRANL, UPDACC, UPDCUST, XFRFUN) |
| ACCTCTRL / CUSTCTRL | BANKDATA, CREACC, DELACC / BANKDATA, CRECUST |
| Per-program COMMAREAs: CREACC, CRECUST, DELACC, DELCUS, GETCOMPY, GETSCODE, INQACC, INQACCCU, INQACCS, INQCUSTZ, INQTRAND, INQTRANL, PAYDBCR, UPDACC, UPDCUST, XFRFUN | the program itself, plus the BNK1* screen that LINKs it |
| `*Z` variants: INQACCZ, INQACCCZ, INQACCSZ, PAYDBCRZ | no COBOL program; referenced by z/OS Connect provider files (`src/api/src/main/zosAssets/*/providerFiles`). They differ from the program copybooks: `PIC X(4)` instead of `POINTER` |
| **Not referenced anywhere in the repo**: BANKMAP, BNK1DDM, CONTROLI, CUSTMAP, DELACCZ, NEWACCNO, NEWCUSNO, **PROCISRT**, RESPSTR, STCUSTNO, WAZI | — |

### 3.1 `PROCISRT-COMMAREA` (`PROCISRT.cpy`)

No program in this repository COPYs or INCLUDEs PROCISRT. It looks like the interface of a PROCTRAN-insert service that has been removed. It is documented here, and modelled in Java, because the plan treats it as the reference multi-operation contract. Do not assume a live caller exists.

```
01 PROCISRT-COMMAREA.
   03 PROCISRT-FUNCTION   PIC X.        88 values '1'..'7' below
   03 PROCISRT-PCB-POINTER POINTER.     4 bytes (31-bit); not meaningful off-platform
   03 PROCISRT-DEBIT-STRUCT.            the base area, 126 bytes
      05 ...-ACCNO      PIC 9(8).
      05 ...-SORTCODE   PIC 9(6).
      05 ...-AMOUNT     PIC S9(10)V99.   zoned decimal (USAGE DISPLAY), not COMP-3
      05 FILLER         PIC X(100).
   03 ... six more structures REDEFINES PROCISRT-DEBIT-STRUCT
```

| Code | 88-level | Structure | Fields after ACCNO 9(8), SORTCODE 9(6), AMOUNT/BALANCE S9(10)V99 | Used bytes of 126 |
|:-:|---|---|---|---:|
| 1 | PROCISRT-DEBIT | DEBIT-STRUCT | FILLER X(100) | 126 |
| 2 | PROCISRT-CREDIT | CREDIT-STRUCT | none | 26 |
| 3 | PROCISRT-XFR-LOCAL | XFR-LOCAL-STRUCT | TARGET-ACCNO 9(8) (same sort code) | 34 |
| 4 | PROCISRT-DELETE-CUSTOMER | DELETE-CUST-STRUCT | DOB (YYYY 9999, MM 99, DD 99), NAME X(60), NUMBER 9(10) | 104 |
| 5 | PROCISRT-CREATE-CUSTOMER | CREATE-CUST-STRUCT | same as 4 | 104 |
| 6 | PROCISRT-DELETE-ACCOUNT | DELACC-STRUCT | L-STMNT (YYYY/MM/DD), N-STMNT (YYYY/MM/DD), TYPE X(8), CUSTNO 9(10) | 60 |
| 7 | PROCISRT-CREATE-ACCOUNT | CREACC-STRUCT | same as 6 (group name has a typo, `PROCISRT-CREAET-ACCT-N-STMNT`) | 60 |

Total length is 1 + 4 + 126 = 131 bytes. Exact offsets are in `ProcIsrtCodec`'s Javadoc and in [data-contracts.md](data-contracts.md#procisrt-byte-map). REDEFINES means bytes a shorter layout doesn't define still hold whatever the previous user wrote. The Java codec writes spaces there and ignores them on read.

## 4. IMS (`src/base/ims`)

### 4.1 Programs

| Source | Lang | PSB (PROCOPT) | DL/I calls | API | Notes |
|---|---|---|---|:-:|---|
| pli/IBLOGIN.pli | PL/I | IBLOGIN (CUSTOMER R) | GU IOPCB; GHU CUSTOMER(CUSTID EQ); REPL; ISRT IOPCB | | Deployed login program (`ims_spoc.jcl.j2` PGM IBLOGIN; PSB `LANG=PL/I`) |
| cobol/IBLOGIN1.cbl | COBOL | none named IBLOGIN1 | same as above | | Older COBOL version (PROGRAM-ID IBLOGIN1). No PSB or transaction, so not deployed |
| cobol/IBLOGOUT.cbl | COBOL | IBLOGOUT (CUSTOMER R) | GHU CUSTOMER; REPL | | Sets STATUS back to logged out |
| cobol/IBACSUM.cbl | COBOL | IBACSUM (ACCOUNT G, CUSTACCS G) | GHU/GHN CUSTACCS(CUSTID =); GHU ACCOUNT(ACCID =) | Y | Account summary, at most 6 accounts |
| cobol/IBGCUDAT.cbl | COBOL | IBGCUDAT (CUSTOMER G) | GU CUSTOMER | Y | Get customer data |
| cobol/IBSCUDAT.cbl | COBOL | IBSCUDAT (CUSTOMER R) | GHU CUSTOMER; REPL | Y | Set customer data |
| cobol/IBTRAN.cbl | COBOL | IBTRAN (ACCOUNT R, CUSTACCS G, HISTORY I) | GHU/GHN CUSTACCS; GHU ACCOUNT; REPL ACCOUNT; ISRT HISTORY | Y | Deposit/withdraw (`d`/`w`, upper case accepted); JNI to Java `nazare.jmp.controller.InsertHist` |
| cobol/LOADCUST.cbl | COBOL | IBLOAD (all L) | ISRT CUSTOMER (unqualified SSA) | | Batch DLI load from CSV (`ENTRY 'DLITCBL'`, 9 PCBs, CUSTOMER is PCB 4) |
| cobol/LOADCUSA.cbl | COBOL | IBLOAD | ISRT CUSTACCS | | Same pattern for CUSTACCS (CUSTID, ACCID, ACCNUM) |
| cobol/LOADACCT, LOADHIST, LOADTSTA | COBOL | IBLOAD | ISRT ACCOUNT / HISTORY / TSTAT | | Same pattern |

Load programs: `UNSTRING` the CSV on `","` / `",` / `,"` / `,` / `"`, convert keys with `FUNCTION NUMVAL`, and stop at the first non-blank DL/I status. The input files are in `src/base/ims/LoadData/*.data` (ASCII CSV in git; converted to EBCDIC on upload).

### 4.2 DBDs (`DBD/*.asm`)

All 9 databases are **HDAM/OSAM, root-only (PARENT=0), ENCODING=Cp1047**. Nothing in IMS is a parent-child hierarchy. The "hierarchy" CUSTOMER → accounts is a key relationship through the CUSTACCS database. See [data-contracts.md §4](data-contracts.md#4-ims-segments).

| DBD | Segment (external name) | Bytes | Key |
|---|---|---:|---|
| CUSTOMER | CUSTOMER | 279 | CUSTID INT, unique |
| CUSTACCS | CUSTACCS (CUSTOMERACCS) | 16 | CUSTID INT, **SEQ,M (non-unique)** |
| ACCOUNT | ACCOUNT | 25 | ACCID LONG, unique |
| HISTORY | HISTORY | 56 | TXID LONG |
| TSTAT | TSTAT (TRANSSTATUS) | 59 | TXID LONG |
| ACCTYPE / CUSTTYPE / TSTATTYP / TTYPE | code tables | 21 | CODE CHAR(1) |

### 4.3 PSBs (`PSB/*.asm`)

IB (all AP), IBACSUM, IBGCUDAT, IBLOAD (all L), IBLOGIN, IBLOGOUT, IBSCUDAT, IBTRAN. PROCOPTs are listed in the program table above. The read-only PSBs are **IBGCUDAT** (CUSTOMER) and **IBACSUM** (ACCOUNT, CUSTACCS). The experimental Java reader only accepts those two.

### 4.4 IMS copybooks (`copy/`)

`JNI.cpy` is used by IBTRAN. `IBGHIST.cpy`, `IBSHIST.cpy` and `IBTRAN.cpy` are not COPY'd by any program: the programs declare the same layouts inline, and the Java message classes (`nazare.jmp.message.*`) mirror IBGHIST. Treat the copybooks as documentation that can drift from the code.

### 4.5 Java JMP (`java/`, Gradle, builds `nazare-ims-jmp`)

| Class | Role |
|---|---|
| `nazare.jmp.controller.QueryTransaction` | IBGHIST JMP: reads up to 50 rows from Db2 `IMSBANK.HISTORY` and returns them in 84-byte detail records (4200-byte area) |
| `nazare.jmp.controller.InsertHist` | Called by IBTRAN through JNI to insert a history row |
| `nazare.jmp.controller.IMSBankHistory` | Standalone main that installs a trust-all `X509TrustManager` and hostname verifier (TLS validation disabled). Don't reuse this in migrated code |
| `nazare.jmp.history.TransactionService` | JDBC (`jdbc:db2:<location>`, default DBD1LOC) for HISTORY reads and inserts |
| `nazare.jmp.message.*` | IOMessage layouts |

Build: Java 21 toolchain; dependencies `com.ibm.db2:jcc:12.1.5.0`, `com.ibm.ims:udb:15.166`, `com.ibm.jzos:ibmjzos:2.4.11.3`, `javax.xml.bind:jaxb-api:2.4.0-b180830.0359`. This branch adds test-only JUnit 5 and H2.

## 5. Batch (`src/base/batch`)

| File | Notes |
|---|---|
| jcl/BNKSTMT.jcl | IKJEFT01 `DSN SYSTEM(DBD1) RUN PROGRAM(BNKSTMT) PLAN(BANKZPLN)`. In-stream DATECARD (`202606`) and SORTCODE (`123456`) cards |
| pli/BNKSTMT.pli | 911 lines. Monthly statement: cursor over ACCOUNT by sort code (ORDER BY ACCOUNT_NUMBER), then PROCTRAN for each account (sort code, account number, first to last day of the month) (ORDER BY date, time), and a CUSTOMER lookup per account. Opening balance is `FIXED DEC(12,2)` |

## 6. Hidden behaviour found during the inventory

Each item has a matching row in [edge-case-checklist.md](edge-case-checklist.md).

1. **INQACC "last account" magic number.** `INQACC.cbl:218-222` routes `INQACC-ACCNO = 99999999` to READ-ACCOUNT-LAST, which selects the highest ACCOUNT_NUMBER for the sort code (`ORDER BY ACCOUNT_NUMBER DESC FETCH FIRST 1 ROWS ONLY`, lines 843-872). On that path any non-zero SQLCODE, **including +100 on an empty table**, goes to the abend path with code HNCS (lines 874-935). The normal path handles +100 differently (next item).
2. **INQACC not-found sentinel.** On SQLCODE +100 FETCH-DATA re-INITIALIZEs OUTPUT-DATA (lines 450-457). The main line then tests `ACCOUNT-TYPE = SPACES OR LOW-VALUES` (lines 229-231). In that branch it sets **`INQACC-SUCCESS` to 'Y'**, the same as a hit, and copies no fields. 'N' is only set in the abend handler (line 756). Callers have to look at the account type, not the success flag, to detect not-found.
3. **IBLOGIN DL/I handling.** `IBLOGIN.pli:260` issues `GHU` on CUSTOMER with SSA `CUSTOMER(CUSTID  EQ<fullword>)`. DBSTAT `GB` or `GE` maps to `CUSTOMER DOES NOT EXIST` (lines 261-265). Any other non-blank status produces `BAD STATUS CODE WAS RECEIVED: ` (the 2-byte code is not part of MSG_OUT's first 30 bytes). Then: password mismatch → `PASSWORD INVALID` (checked **before** the logged-in flag); `STATUS = '1'` → `CUSTOMER ALREADY LOGGED IN`; otherwise REPL with STATUS='1' and a new LASTLOGIN.
4. **IBLOGIN LASTLOGIN format mismatch.** IBLOGIN writes `yyyy_MM_dd HH:mm:ss:hh0` (underscores, colon before the hundredths, literal trailing `0`; lines 144-160, 289-301). The CUSTOMER DBD declares LASTLOGIN as TIMESTAMP with `DFSMARSH PATTERN='yyyy-MM-dd HH:mm:ss.SSS'`, and the load data uses `2003-07-23 17:56:00.64`. Typed reads through IMS JDBC may fail to parse rows written by IBLOGIN.
5. **IBLOGIN plaintext password.** Compared as CHAR(16), case-sensitive, stored in the clear in the CUSTOMER segment.
6. **IBLOGIN customer ID.** IN_CUSTID is CHAR(10), overlaid with `PIC'(9)9'` and assigned to `FIXED BIN(31)`. The frontend only sends 9 digits for IMS IDs (`src/frontend/js/utils.js`, `^\d{9}$`). IBACSUM and IBLOGIN1 read the ID as X(9) through `FUNCTION NUMVAL`. Capture golden records for padded and short IDs.
7. **IBACSUM loop semantics.** Stops at the first ACCOUNT GHU that fails. `NO ACCOUNT` only if no account has been found yet; a miss after that silently truncates the list. `NO CUSTOMER` means "no CUSTACCS rows", not "no CUSTOMER segment". On success MSG-OUT is 32 EBCDIC `'0'` characters (`MOVE ZEROS TO OUTPUT-AREA`). `TOTAL-ACCS PIC 9` and `OCCURS 1 TO 6` mean a 7th account overruns the table. The commented-out RULES-CHECK would zero balances for CUSTID < 16.
8. **IBACSUM order.** Accounts come back in HDAM physical/synonym-chain order, not ACCID or ACCNUM order.
9. **No ROUNDED and no ON SIZE ERROR anywhere in `src/base`.** Every arithmetic store truncates excess decimals toward zero and silently drops high-order digits.
10. **Precision differs by stack.** CICS uses S9(10)V99 (Db2 DECIMAL(12,2)); IMS uses S9(13)V9(2) COMP-3 (DECIMAL(15,2)). Interest rate is `9(4)V99` in copybooks and `S9(4)V99 COMP-3` in host variables.
11. **Pointers in COMMAREAs.** `PROCISRT-PCB-POINTER`, `INQACC-PCB1-POINTER`, `DELACC-DEL-PCB1..3` and `COMM-PCB-POINTER` are `POINTER` in program copybooks and `PIC X(4)` in the `*Z` API copybooks: 4 opaque bytes in a 31-bit region.
