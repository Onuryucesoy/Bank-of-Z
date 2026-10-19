# Copybook and z/OS Connect asset inventory

Maps every copybook in `src/base/cics/copy`, `src/base/ims/copy` and every asset in
`src/api/src/main/zosAssets` to the Java type it becomes. Status:

- **P1** = generated in Phase 1, in `src/migration/java` (`com.bankofz.migration.model`),
  checked against the copybook by `CopybookConformanceTest`.
- **P2+** = planned name, generated when the program that owns the interface is ported.
- **none** = no DTO (BMS symbolic map, SQL `DECLARE`, constant, or JNI plumbing).

Flags:

- **RD** = `REDEFINES` date/time group. Account dates are `PIC 9(8)` redefined as `DD` `MM` `YYYY`
  (DDMMYYYY), and become `java.time.LocalDate` via `CobolDates.fromDdMmYyyy`.
  **DG** = the same DDMMYYYY layout written as a plain group without `REDEFINES` (customer dates).
- **P3** = `COMP-3` / `PACKED-DECIMAL` → `BigDecimal` (scale = digits after `V`).
- **ZD** = signed/fractional zoned decimal (`S9(n)V99` DISPLAY) → `BigDecimal`. Not packed, but
  still money, so the same rule applies.
- **ODO** = `OCCURS ... DEPENDING ON` (variable array → `List<>` bounded by the max count).

Flags were collected by scanning the copybook text (not by hand) and spot-checked; field names
below are the COBOL data names.

## CICS copybooks (`src/base/cics/copy`, 42 files)

### Entity records and Db2 declarations

| Copybook | Used by | Java type | Status | Flags |
|---|---|---|---|---|
| `CUSTOMER.cpy` | BANKDATA, CRDTAGY1–5, CREACC, CRECUST, DELCUS, INQACCCU, INQCUST, UPDCUST | `CustomerRecord` + `Address` + `CustomerStatus` | P1 | DG: `CUSTOMER-DOB`, `CUSTOMER-CREATED-DATE`, `CUSTOMER-CS-REVIEW-DATE` |
| `ACCOUNT.cpy` | CREACC, DBCRFUN, DELACC, DELCUS, INQACC, INQACCCU, UPDACC, XFRFUN | `AccountRecord` | P1 | RD: `ACCOUNT-OPENED`, `ACCOUNT-LAST-STMT-DATE`, `ACCOUNT-NEXT-STMT-DATE`; ZD: `ACCOUNT-INTEREST-RATE 9(4)V99`, `ACCOUNT-AVAILABLE-BALANCE`/`ACCOUNT-ACTUAL-BALANCE S9(10)V99` |
| `CUSTDB2.cpy` | BANKDATA, CRECUST, DELCUS, INQCUST, UPDCUST (`EXEC SQL INCLUDE`) | Db2 table `CUSTOMER` (V001) | P1 | SQL `DECLARE`; dates are `INTEGER` YYYYMMDD |
| `ACCDB2.cpy` | BANKDATA, CREACC, DBCRFUN, DELACC, INQACC, INQACCCU, INQACCS, UPDACC, XFRFUN (`INCLUDE`) | Db2 table `ACCOUNT` (V001) | P1 | SQL `DECLARE`; `DECIMAL(4,2)`/`DECIMAL(10,2)` (narrower than ACCOUNT.cpy, see phase-1 §2.2) |
| `PROCTRAN.cpy` | CREACC, CRECUST, DBCRFUN, DELACC, DELCUS, XFRFUN | `ProcessedTransactionRecord` | P2+ | RD: `PROC-TRAN-DATE-GRP`, `PROC-TRAN-TIME-GRP`; RD (non-date) description variants `PROC-TRAN-DESC-XFR/-DELACC/-CREACC/-DELCUS/-CRECUS` → sealed interface; ZD: `PROC-TRAN-AMOUNT` |
| `PROCDB2.cpy` | CREACC, CRECUST, DBCRFUN, DELACC, DELCUS, INQTRAND, INQTRANL, XFRFUN (`INCLUDE`) | Db2 table `PROCTRAN` | P2+ | SQL `DECLARE` (out of Phase 1 scope: CICS audit trail, not IMS history) |
| `PROCISRT.cpy` | not copied by any program | `ProcessedTransactionRecord` variants | P2+ | RD: 6 × `PROCISRT-DEBIT-STRUCT` (structural); ZD: 7 amount/balance fields |
| `CONTDB2.cpy` | BANKDATA, CRECUST (`INCLUDE`; CREACC declares CONTROL host variables inline) | Db2 table `BANK_COUNTER` (V001, replaces `CONTROL`) | P1 | SQL `DECLARE` (`STTESTER.CONTROL`) |
| `CONTROLI.cpy` | not copied by any program | `BANK_COUNTER` rows | P1 (as table) | P3: `CONTROL-CUSTOMER-COUNT`/`-LAST 9(10)`, `CONTROL-ACCOUNT-COUNT`/`-LAST 9(8)` |
| `CUSTCTRL.cpy` | BANKDATA, CRECUST | `BANK_COUNTER` rows | P1 (as table) | DISPLAY counters `9(10)` |
| `ACCTCTRL.cpy` | BANKDATA, CREACC, DELACC | `BANK_COUNTER` rows | P1 (as table) | DISPLAY counters `9(8)` |
| `NEWCUSNO.cpy` | **no program copies it** | `NewCustomerNumberRequest`/`Response` + `CounterFunction` | P1 | `FUNCTION X` 88 G/R/C, `CUSTOMER-NUMBER 9(10)` |
| `NEWACCNO.cpy` | **no program copies it** | `NewAccountNumberRequest`/`Response` + `CounterFunction` | P1 | `FUNCTION X` 88 G/R/C, `ACCOUNT-NUMBER 9(8)` |
| `SORTCODE.cpy` | every non-BMS CICS program except ABNDPROC and GETCOMPY | constant `987654` (config) | none | 77-level constant |
| `STCUSTNO.cpy` | not copied by any program | `String` key | none | `CNO-KEY 9(10)` |
| `ABNDINFO.cpy` | ABNDPROC and every CICS program that links to it | `AbendRecord` | P2+ | P3: `ABND-UTIME-KEY S9(15)` |

### Commarea interfaces (one per linked program)

| Copybook | Program | Java type (request/response) | Status | Flags |
|---|---|---|---|---|
| `CRECUST.cpy` | CRECUST | `CreateCustomerCommand` / `CustomerRecord` | P2+ | DG: `COMM-DOB`, created, CS review |
| `INQCUSTZ.cpy` | INQCUST | `CustomerRecord` (lookup by key) | P2+ (maps to P1 DTO) | DG: `INQCUST-DOB`, `INQCUST-CS-REVIEW-DT`; key `INQCUST-SCODE X(6)` / `INQCUST-CUSTNO 9(10)` |
| `UPDCUST.cpy` | UPDCUST | `UpdateCustomerCommand` | P2+ | DG dates; key `COMM-SCODE X(6)`/`COMM-CUSTNO X(10)` (alphanumeric here) |
| `DELCUS.cpy` | DELCUS | `DeleteCustomerCommand` / `CustomerRecord` | P2+ | DG dates; keys alphanumeric |
| `CREACC.cpy` | CREACC | `CreateAccountCommand` / `AccountRecord` | P2+ | RD: `COMM-OPENED-GROUP`, `COMM-LAST-STMNT-GROUP`, `COMM-NEXT-STMNT-GROUP`; ZD: `COMM-INT-RT`, `COMM-AVAIL-BAL`, `COMM-ACT-BAL` |
| `INQACC.cpy` | INQACC | `AccountRecord` | P2+ (maps to P1 DTO) | RD: `INQACC-OPENED-GROUP`, `-LAST-STMT-GROUP`, `-NEXT-STMT-GROUP`; ZD: rate, balances |
| `INQACCZ.cpy` | INQACC (API variant) | `AccountRecord` | P2+ | same as INQACC.cpy |
| `INQACCCU.cpy` | INQACCCU | `List<AccountRecord>` | P2+ | ODO 1–20 on `NUMBER-OF-ACCOUNTS`; RD `COMM-*-GROUP`; ZD |
| `INQACCCZ.cpy` | INQACCCU (API variant) | `List<AccountRecord>` | P2+ | same |
| `INQACCS.cpy` | INQACCS | `List<AccountRecord>` | P2+ | ODO 1–20; RD; ZD |
| `INQACCSZ.cpy` | INQACCS (API variant) | `List<AccountRecord>` | P2+ | same |
| `DELACC.cpy` | DELACC | `DeleteAccountCommand` / `AccountRecord` | P2+ | RD: `DELACC-OPENED-GROUP` etc.; ZD |
| `DELACCZ.cpy` | DELACC (API variant) | `List<AccountRecord>` | P2+ | ODO 1–20; RD; ZD |
| `UPDACC.cpy` | UPDACC | `UpdateAccountCommand` | P2+ | RD: `COMM-OPENED-GROUP`, `COMM-LAST-STMNT-GROUP`, `COMM-NEXT-STMNT-GROUP`; ZD |
| `PAYDBCR.cpy` | DBCRFUN | `PaymentCommand` / `BalanceResult` | P2+ | ZD: `COMM-AMT`, `COMM-AV-BAL`, `COMM-ACT-BAL` |
| `PAYDBCRZ.cpy` | DBCRFUN (API variant) | same | P2+ | same |
| `XFRFUN.cpy` | XFRFUN | `TransferCommand` / `TransferResult` | P2+ | ZD: `COMM-AMT`, `COMM-FAVBAL`, `COMM-FACTBAL`, `COMM-TAVBAL`, `COMM-TACTBAL` |
| `INQTRAND.cpy` | INQTRAND | `ProcessedTransactionRecord` | P2+ | RD: `INQTRAND-TRAN-DATE-GRP`, `-TIME-GRP`; ZD: `INQTRAND-TRAN-AMOUNT` |
| `INQTRANL.cpy` | INQTRANL | `List<ProcessedTransactionRecord>` | P2+ | `OCCURS 100`; RD date/time groups; ZD amount |
| `GETCOMPY.cpy` | GETCOMPY | `String companyName` (`x(40)`) | P2+ | — |
| `GETSCODE.cpy` | GETSCODE | `String sortCode` (`X(6)`) | P2+ | — |

### No DTO

| Copybook | Why |
|---|---|
| `BANKMAP.cpy`, `BNK1DDM.cpy`, `CUSTMAP.cpy` | BMS symbolic maps (input/output map `REDEFINES` FILLER); replaced by the web frontend, not ported |
| `RESPSTR.cpy` | EIBRESP → text table for messages |
| `WAZI.cpy` | debugger/test hook, no data items |

## IMS copybooks (`src/base/ims/copy`, 4 files)

| Copybook | Used by | Java type | Status | Flags |
|---|---|---|---|---|
| `IBGHIST.cpy` | JMP `IBGHIST` (Java `QueryTransaction`) / zosAsset | `BankTransactionRecord` | P1 | P3: `AMOUNT S9(13)V9(2)`; `TXID`/`REFTXID`/`ACCID S9(18) COMP-5` → `long`; `TIMESTMP X(23)` → `LocalDateTime` |
| `IBSHIST.cpy` | (no COBOL `COPY`) | `BankTransactionRecord` | P1 | identical to IBGHIST.cpy (asserted by test) |
| `IBTRAN.cpy` | IBTRAN zosAsset (IBTRAN.cbl inlines its own copy) | `ImsTransactionRequest` / `AccountSummary` list | P2+ | P3: `BALANCE-AS S9(13)V9(2)`; ODO 1–6 on `TOTAL-ACCS`; `BAL REDEFINES MSG-OUT` (non-date); `IN-AMOUNT X(16)` is text → parse to `BigDecimal` |
| `JNI.cpy` | IBTRAN (Java call) | none | none | `JNI-RC S9(9) COMP-5` 88 OK/ERR |

IMS segment layouts that have no copybook (DBD only, `src/base/ims/DBD`): CUSTOMER, ACCOUNT, CUSTACCS,
HISTORY, TSTAT and lookup DBDs ACCTYPE/CUSTTYPE/TTYPE/TSTATTYP. Their relational mapping is in
`src/migration/db2/V002__ims_migration.sql` and `phase-1-data-layer.md` §4.

## z/OS Connect assets (`src/api/src/main/zosAssets`, 14 assets)

The source copybook of each asset is the `fileName` in its provider `.dai` file. Each asset
also has generated `gen/*_request_0.cpy` / `*_response_0.cpy` and JSON schemas; those are
derived artefacts and map to the same DTO.

| Asset | Backend | Copybook (`fileName`) | Java type | Flags |
|---|---|---|---|---|
| `CRECUST` | CICS | `CRECUST.cpy` | `CreateCustomerCommand` / `CustomerRecord` | DG |
| `DBCRFUN` | CICS | `PAYDBCRZ.cpy` | `PaymentCommand` / `BalanceResult` | ZD |
| `DELCUS` | CICS | `DELCUS.cpy` | `DeleteCustomerCommand` | DG |
| `INQACC` | CICS | `INQACCZ.cpy` | `AccountRecord` | RD, ZD |
| `INQACCCU` | CICS | `INQACCCZ.cpy` | `List<AccountRecord>` | ODO, RD, ZD |
| `INQACCS` | CICS | `INQACCSZ.cpy` | `List<AccountRecord>` | ODO, RD, ZD |
| `INQCUST` | CICS | `INQCUSTZ.cpy` | `CustomerRecord` | DG (generated `CUSTOMER_response_0.cpy` adds `REDEFINES` DOB/CS groups) |
| `INQTRAND` | CICS | `INQTRAND.cpy` | `ProcessedTransactionRecord` | RD, ZD |
| `INQTRANL` | CICS | `INQTRANL.cpy` | `List<ProcessedTransactionRecord>` | RD, ZD, `OCCURS 100` |
| `UPDCUST` | CICS | `UPDCUST.cpy` | `UpdateCustomerCommand` | DG |
| `IBACSUM` | IMS | `IBACSUM.cbl` (inline layout) | `List<AccountSummary>` | P3 balance, ODO 1–6 |
| `IBGCUDAT` | IMS | `IBGCUDAT.cbl` (inline layout) | `ImsCustomerRecord` | — |
| `IBSCUDAT` | IMS | `IBSCUDAT.cbl` (inline layout) | `ImsCustomerUpdate` | `CUSTID COMP-5` |
| `IBTRAN` | IMS | `IBTRAN.cpy` | `ImsTransactionRequest` / `AccountSummary` | P3, ODO 1–6 |

## Rules applied to every DTO

1. Every `V` picture and every `COMP-3` field is `BigDecimal` with the PIC scale; values are
   never rounded (`CobolFields.decimal` rejects extra decimals and overflow).
2. Keys that are `PIC 9(n)` in the record but `CHAR(n)` in Db2 are zero-padded `String`s.
3. `REDEFINES`/group dates (DDMMYYYY) and Db2 `INTEGER` dates (YYYYMMDD) are both `LocalDate`,
   converted only through `CobolDates`; zero is `null`.
4. 88-levels become enums whose literals are asserted against the copybook.
