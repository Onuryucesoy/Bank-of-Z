# Phase 0: baseline and safety net

Phase 0 freezes what "behaves the same" means before any program is ported. Nothing under
`src/base`, `src/api` or `src/frontend` changes in this phase; everything here is documentation
of the current `main`.

## 1. Frozen external contract

### 1.1 OpenAPI

`src/api/src/main/api/openapi.yaml` is the contract the Java services must keep, byte-compatible
on paths, parameters, status codes and schemas. It currently defines **17 operations on 14 paths**:

| Backend | operationId |
|---|---|
| CICS (Db2) | `createCustomer`, `getCustomer`, `updateCustomer`, `deleteCustomer`, `getCustomerAccounts`, `getAccounts`, `getAccount`, `getAccountBalances`, `depositToAccount`, `getAccountTransactions`, `getTransaction` |
| IMS (DL/I) | `getIMSCustomer`, `updateIMSCustomer`, `getIMSCustomerAccounts`, `getIMSAccount`, `getIMSAccountBalances`, `depositToIMSAccount` |

Schemas the data layer must stay compatible with: `Customer`, `CreateCustomerRequest`,
`CustomerUpdate`, `Address`, `Account`, `Balance`, `Transaction`. How the Phase 1 DTOs relate
to them is in `phase-1-data-layer.md` §5.

Each operation is implemented by a z/OS Connect asset under `src/api/src/main/zosAssets`
(14 assets; mapping in `copybook-inventory.md`).

### 1.2 Shell integration tests (`tests/`)

| Script | What it proves | Operations covered |
|---|---|---|
| `tests/run-all.sh` | Runs every `tests/test_*.sh`, prints pass/fail/skip, exits non-zero on any failure; skips IMS tests when `IMS_DISABLED` is set | (runner) |
| `tests/test_get_customer_cics.sh` | `GET /customers/0000001` returns 200 and `lastName` = `Higins` | `getCustomer` |
| `tests/test_get_customer_ims.sh` | IMS customer `000000015`: customer found, account list non-empty, balances returned | `getIMSCustomer`, `getIMSCustomerAccounts`, `getIMSAccountBalances` |
| `tests/test_frontend.sh` | `GET /admin.html` returns 200 | (frontend) |
| `tests/test-setup.sh` | Loads `.setup/config/setenv.sh`, derives `BASE_URL` / `FRONTEND_URL` | (setup) |

These scripts are the migration correctness baseline: every Java cut-over must keep them green
against the same seed data. They are run against a deployed environment (z/OS Connect + CICS +
IMS); they cannot run on a workstation without one.

### 1.3 Gaps to extend before cut-over

The current tests cover **4 of 17 operations, all read-only**. Checklist:

- [ ] CICS reads: `getAccounts`, `getAccount`, `getAccountBalances`, `getCustomerAccounts`,
      `getAccountTransactions`, `getTransaction` (assert full payloads, not just one field).
- [ ] CICS writes with clean-up: `createCustomer` (new number = last + 1, credit score set by
      the `CRDTAGY*` fan-out), `updateCustomer`, `deleteCustomer` (accounts deleted too),
      `depositToAccount` (available and actual balance both move, PROCTRAN row written).
- [ ] IMS: `getIMSAccount`, `updateIMSCustomer`, `depositToIMSAccount` (TXID =
      `ACCID * 10^10 + LASTTXID + 1`, history row in IMS and in `IMSBANK.HISTORY`).
- [ ] Error paths: unknown customer/account (404 vs 200 with empty body, as today), invalid
      amount, withdrawal beyond balance (IBTRAN does **not** reject it today; keep or decide).
- [ ] Money: deposits with 2 decimals, negative amounts, values near `S9(10)V99` limits.
- [ ] Dates: DOB on 29 February, created date in the past/future, zero dates.
- [ ] Concurrency: two `createCustomer` calls in parallel must get distinct, ascending numbers
      (today serialised by `EXEC CICS ENQ`).
- [ ] Golden files: record JSON responses for the seed data and diff them, instead of grepping
      single fields.
- [ ] Batch: `BNKSTMT` report for a fixed `DATECARD`/`SORTCODE` captured as a golden file.
- [ ] Frontend: one smoke per page (`index.html`, `admin.html`, customer/account views).

## 2. Program characterization

Legend — Data: **SQL** = embedded Db2 SQL, **DL/I** = `CBLTDLI`/`PLITDLI` calls, **—** = none.
IMS MPPs also use `GU`/`ISRT` on the I/O PCB to read the input and send the reply. Every CICS program that fails links to `ABNDPROC` with an `ABNDINFO` commarea and then abends;
that is not repeated per row.

### 2.1 CICS business programs (`src/base/cics/cobol`)

| Program | Input / output | Data access | CICS primitives |
|---|---|---|---|
| `CRECUST` | Commarea `CRECUST.cpy`; channel `CIPCREDCHANN` with containers `CIPA`…`CIPE` to the credit agencies | SQL: `SELECT`/`UPDATE CONTROL`, `INSERT CUSTOMER`, `INSERT PROCTRAN` | `ENQ`/`DEQ` on `BANKZCUST`+sortcode (customer-number serialisation), `RUN TRANSID('OCR1'..'OCR5') CHANNEL` (async API), `FETCH ANY`, `GET`/`PUT CONTAINER`, `DELAY` |
| `CRDTAGY1`–`CRDTAGY5` | Channel `CIPCREDCHANN`, container with the customer data; returns a credit score | — | `GET`/`PUT CONTAINER`, `DELAY` (random 1–3 s simulated agency latency); started by `RUN TRANSID` from CRECUST |
| `CREACC` | Commarea `CREACC.cpy` | SQL: `SELECT`/`UPDATE CONTROL` (`<sortcode>-ACCOUNT-LAST`, `-ACCOUNT-COUNT`), `INSERT ACCOUNT`, `INSERT PROCTRAN` | `ENQ`/`DEQ` on `BANKZACCT`+sortcode, `LINK INQCUST`, `LINK INQACCCU` |
| `INQCUST` | Commarea `INQCUSTZ.cpy` | SQL: `SELECT CUSTOMER` (customer `0000000000` = random, `9999999999` = last via CONTROL) | — |
| `UPDCUST` | Commarea `UPDCUST.cpy` | SQL: `SELECT`/`UPDATE CUSTOMER` | — |
| `DELCUS` | Commarea `DELCUS.cpy` | SQL: `SELECT`/`DELETE CUSTOMER`, `INSERT PROCTRAN` | `LINK INQCUST`, `LINK INQACCCU`, `LINK DELACC` (per account) |
| `INQACC` | Commarea `INQACC.cpy` (`INQACCZ.cpy` for the API) | SQL: cursor on `ACCOUNT` | — |
| `INQACCCU` | Commarea `INQACCCU.cpy` (`INQACCCZ.cpy`), up to 20 accounts | SQL: cursor on `ACCOUNT` by customer | `LINK INQCUST` |
| `INQACCS` | Commarea `INQACCS.cpy` (`INQACCSZ.cpy`), up to 20 accounts | SQL: cursor on `ACCOUNT` | — |
| `UPDACC` | Commarea `UPDACC.cpy` | SQL: `SELECT`/`UPDATE ACCOUNT` | — |
| `DELACC` | Commarea `DELACC.cpy` | SQL: `SELECT`/`DELETE ACCOUNT`, `INSERT PROCTRAN` | — |
| `DBCRFUN` | Commarea `PAYDBCR.cpy` (`PAYDBCRZ.cpy`) | SQL: `SELECT`/`UPDATE ACCOUNT`, `INSERT PROCTRAN` | `SYNCPOINT`, `SYNCPOINT ROLLBACK` on failure |
| `XFRFUN` | Commarea `XFRFUN.cpy` | SQL: `SELECT`/`UPDATE ACCOUNT` ×2, `INSERT PROCTRAN` | `SYNCPOINT`/`SYNCPOINT ROLLBACK`, `DELAY FOR SECONDS(1)` + retry on SQLCODE -911 |
| `INQTRAND` | Commarea `INQTRAND.cpy` | SQL: cursor on `PROCTRAN` | — |
| `INQTRANL` | Commarea `INQTRANL.cpy` (up to 100 rows) | SQL: 2 cursors on `PROCTRAN` | — |
| `GETCOMPY` | Commarea `GETCOMPY.cpy` (company name) | — | — |
| `GETSCODE` | Commarea `GETSCODE.cpy` (sort code) | — | — |
| `ABNDPROC` | Commarea `ABNDINFO.cpy` | VSAM: `WRITE FILE` (abend log) | — |
| `BANKDATA` | JCL `PARM` (`PARM-BUFFER`, up to 256 bytes) | SQL: `DELETE`/`INSERT CUSTOMER`, `ACCOUNT`, `CONTROL`, `COMMIT` | — (batch Db2 data generator; sets the CONTROL counters) |

### 2.2 CICS BMS front-end programs (3270, replaced by `src/frontend`)

| Program | Transaction | BMS map | Links to |
|---|---|---|---|
| `BNKMENU` | `OMEN` | `BNK1MAI` | — (`RETURN TRANSID` to the option chosen) |
| `BNK1CCS` | `OCCS` | `BNK1CCM` | `CRECUST` |
| `BNK1DCS` | `ODCS` | `BNK1DCM` | `INQCUST`, `UPDCUST`, `DELCUS` |
| `BNK1CAC` | `OCAC` | `BNK1CAM` | `CREACC` |
| `BNK1CCA` | `OCCA` | `BNK1ACC` | `INQACCCU` |
| `BNK1DAC` | `ODAC` | `BNK1DAM` | `INQACC`, `DELACC` |
| `BNK1UAC` | `OUAC` | `BNK1UAM` | `INQACC`, `UPDACC` |
| `BNK1CRA` | `OCRA` | `BNK1CDM` | `DBCRFUN` |
| `BNK1TFN` | `OTFN` | `BNK1TFM` | `XFRFUN` |

All use `SEND MAP`/`RECEIVE MAP`, a private `DFHCOMMAREA`, pseudo-conversational `RETURN
TRANSID ... COMMAREA`, and `LINK` for the business logic. No SQL or DL/I.

### 2.3 IMS programs (`src/base/ims`)

| Program | Type | Input / output message | Data access (PSB) |
|---|---|---|---|
| `IBTRAN` | COBOL MPP | In: `TRAN-CODE`, `IN-ACCID X(18)`, `IN-AMOUNT X(16)`, `IN-TRXTYPE X(1)` (d/D/w/W), `IN-CUSTID X(9)`; out: message or balance + 1–6 account summaries (`IBTRAN.cpy`) | DL/I: `GHU`/`REPL ACCOUNT` (balance, `LASTTXID`), `GU CUSTACCS`, `ISRT HISTORY`; JNI call to Java `nazare.jmp.controller.InsertHist` → Db2 `IMSBANK.HISTORY` |
| `IBACSUM` | COBOL MPP | In: `CUSTID X(9)`; out: message + 1–6 × (`BALANCE` COMP-3, `ACCTYPE`, `ACCID` COMP-5) | DL/I: `GU`/`GHU` on CUSTACCS and ACCOUNT (read only) |
| `IBGCUDAT` | COBOL MPP | In: `CUSTID X(9)`; out: customer fields | DL/I: `GU CUSTOMER` |
| `IBSCUDAT` | COBOL MPP | In: `CUSTID` COMP-5 + customer fields | DL/I: `GHU`/`REPL CUSTOMER` |
| `IBLOGIN1` | COBOL MPP | In: `CUSTID X(9)`, `PASSWD X(16)` | DL/I: `GHU`/`REPL CUSTOMER` (STATUS, LASTLOGIN) |
| `IBLOGIN` | PL/I MPP | Same as IBLOGIN1 (`IBLOGIN.pli`) | DL/I via `PLITDLI` |
| `IBLOGOUT` | COBOL MPP | In: `CUSTID` | DL/I: `GHU`/`REPL CUSTOMER` |
| `LOADCUST`, `LOADCUSA`, `LOADACCT`, `LOADHIST`, `LOADTSTA` | COBOL BMP loaders | CSV files from `src/base/ims/LoadData` (`UNSTRING` + `NUMVAL`) | DL/I: `ISRT` into CUSTOMER, CUSTACCS, ACCOUNT, HISTORY, TSTAT (PSB `PROCOPT=L`) |
| `IBGHIST` | JMP (Java) | In: `IN-ACCID X(18)`; out: history rows (`IBGHIST.cpy`) | JDBC to Db2 `IMSBANK.HISTORY` (`ORDER BY TIMESTMP DESC FETCH FIRST 50`) |

### 2.4 Batch

| Program | Input / output | Data access |
|---|---|---|
| `BNKSTMT` (PL/I, `src/base/batch/pli/BNKSTMT.pli`, JCL `src/base/batch/jcl/BNKSTMT.jcl`) | `DATECARD` (statement month), `SORTCODE` card; `SYSPRINT` statement report | SQL: cursor `ACCT_CURSOR` on `ACCOUNT` by sort code, cursor `TRAN_CURSOR` on `PROCTRAN` by account and date range, `SELECT CUSTOMER`; runs under `IKJEFT01 DSN RUN PROGRAM(BNKSTMT) PLAN(BANKZPLN)` |

### 2.5 Findings that affect the migration

- **Named counters are interface-only.** `NEWCUSNO.cpy` / `NEWACCNO.cpy` are not copied by any
  program and there is no `EXEC CICS GET COUNTER` in the code base. Customer and account numbers
  are allocated by `ENQ` + `SELECT`/`UPDATE` of Db2 `CONTROL` rows (`'BANKZCUST'||sortcode||'  '`,
  `<sortcode>-ACCOUNT-LAST`). Phase 1 replaces that mechanism and keeps the G/R/C interface.
- **Two transaction histories.** CICS writes `PROCTRAN` (Db2); IMS writes the HISTORY segment and,
  through JNI, a Db2 copy in `IMSBANK.HISTORY`. Phase 1 models the IMS one as `BANK_TRANSACTION`.
- **Precision drift.** `ACCDB2.cpy` declares `DECIMAL(4,2)`/`DECIMAL(10,2)`; the deployed DDL
  uses `DECIMAL(6,2)`/`DECIMAL(12,2)`; `ACCOUNT.cpy` holds `9(4)V99`/`S9(10)V99`.
- **Overlapping keys.** IMS `CUSTID` 1..99 and CICS customer numbers 1..n overlap; any merged
  store needs a discriminator (Phase 1 uses a separate sort code for IMS rows).
- **Sort code.** `SORTCODE.cpy` is `987654`, but `BNKSTMT.jcl` passes `123456`.

## 3. Java toolchain

- **Java 21** (LTS), the same toolchain as the existing JMP build
  `src/base/ims/java/build.gradle` (`nazare.jmp`, `JavaLanguageVersion.of(21)`, Gradle `java`
  plugin, Maven Central).
- New Java code lives in standalone Gradle projects under `src/migration/java` so it does not
  touch the DBB build or the `nazare.jmp` build. Build with `gradle build` (Gradle 8.x).
- The JMP runtime dependencies (`com.ibm.db2:jcc`, IMS Universal drivers, JZOS) are not needed by
  the Phase 1 model and are not pulled in.
