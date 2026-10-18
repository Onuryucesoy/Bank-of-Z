# Data contracts (Phase 0 freeze)

Field-level inventory of every copybook used by the Bank of Z transaction
programs, frozen as of this commit. The Java port must reproduce these
layouts byte for byte at every boundary that still talks to COBOL
(COMMAREAs, channels/containers, z/OS Connect, IMS messages).

**How this was produced.** The tables below are generated from the copybook
sources by the parser in `src/java-service` (`com.ibm.bankofz.core.copybook`),
the same code the golden-master harness uses, so offsets and lengths are
computed rather than hand-counted. `CopybookParserTest` parses every
copybook listed here on each build; if a copybook changes, offsets here go
stale and the fixtures in `tests/golden/` will fail.

BMS symbolic maps (`BANKMAP`, `BNK1DDM`, `CUSTMAP`, `CONTROLI`), `RESPSTR`,
`WAZI` and IBM's `JNI.cpy` are screen/tooling artefacts, not transaction
contracts, and are excluded.

## Type mapping rules

| COBOL | Storage | Java type | Boundary handling |
|---|---|---|---|
| `PIC X(n)` | n bytes EBCDIC | `String` | CCSID 037 (the `ccsid` declared by every CICS `zosAsset.yaml`). Encode = left-justify, pad with X'40', COBOL `MOVE` truncates on the right — the Java codec **rejects** over-long values instead (`Ebcdic.encodeExact`). Models hold right-trimmed text. |
| `PIC 9(n)` DISPLAY | n bytes zoned | `int` / `long` (`BigDecimal` if `V`) | Digits X'F0'–X'F9'. Spaces/low-values in a numeric field are a data error, not zero. |
| `PIC S9(n)V9(m)` DISPLAY | n+m bytes zoned | `BigDecimal` scale m | Sign overpunched in the zone of the last byte: X'C' positive, X'D' negative, X'F' accepted as unsigned positive. `V` occupies no storage. |
| `PIC S9(n) SIGN LEADING SEPARATE` | n+1 bytes | `long` | First byte EBCDIC `+` (X'4E') / `-` (X'60'). Only `ABNDINFO`. |
| `COMP-3` / `PACKED-DECIMAL` | n/2+1 bytes | `BigDecimal` | Two digits per byte, sign in low nibble of last byte (C/D, F unsigned; A/E/B tolerated on read). Even digit counts carry a leading zero nibble. |
| `COMP` / `BINARY` / `COMP-4` | 2/4/8 bytes | `short` / `int` / `long` | Big-endian two's complement, value limited to PIC digits (TRUNC(STD)). |
| `COMP-5` | 2/4/8 bytes | `short` / `int` / `long` | Big-endian, full native binary range (IMS messages: `LL`, `ZZ`, IDs). |
| `POINTER` | 8 bytes (AMODE 64 layout as declared) | — | Address only; never serialised to Java. |
| Date groups `DD`/`MM`/`YYYY` | zoned | `CobolDate` | Kept as raw day/month/year so all-zero and invalid dates survive round-trip; `toLocalDate()` is optional. Note the **orders differ**: `ACCOUNT` dates are `DDMMYYYY`, `PROCTRAN-DATE` is `YYYYMMDD`, Db2 `CUSTOMER_*` dates are `INTEGER` `YYYYMMDD`, Db2 `ACCOUNT`/`PROCTRAN` `DATE` columns are moved through `DD.MM.YYYY` host variables. |

## Known contract discrepancies (do not "fix" during migration)

| Where | Discrepancy | Authority for Java |
|---|---|---|
| `ACCDB2.cpy` vs `.setup/jcl/cics/Db2-create.j2` | Copybook declares `ACCOUNT_INTEREST_RATE DECIMAL(4,2)`, balances `DECIMAL(10,2)`; DDL creates `DECIMAL(6,2)` and `DECIMAL(12,2)`. Host variables are `S9(4)V99` / `S9(10)V99 COMP-3`, matching the DDL. | DDL (`AccountRow` javadoc). |
| `INQACC.cpy` (107 bytes) vs `INQACCZ.cpy` / z/OS Connect `INQACC` gen copybook (103 bytes) | CICS copybook ends with an 8-byte `POINTER`; the API copybook ends with 4 bytes. | Both frozen; Java must accept either length on input. |
| `CUSTOMER.cpy` vs Db2 `CUSTOMER` | Record keys are `PIC 9`; Db2 keys are `CHAR` (zero-padded). Dates are groups in the record, `INTEGER YYYYMMDD` in Db2. | `CustomerRowMapper`. |
| `DELCUS.cpy` / `UPDCUST.cpy` / `INQCUSTZ.cpy` | Sort code and customer number are `PIC X` here but `PIC 9` in `CRECUST.cpy` / `CUSTOMER.cpy`. | Per-copybook; fixtures encode each as declared. |
| `PAYDBCR.cpy` `COMM-ACCNO` | `PIC X(8)` (account number as text) while everywhere else it is `PIC 9(8)`. | As declared. |

## Copybook inventory


### ABNDINFO

- **Location:** `src/base/cics/copy/ABNDINFO.cpy`
- **Consumers:** `ABNDPROC`, `BNK1CAC`, `BNK1CCA`, `BNK1CCS`, `BNK1CRA`, `BNK1DAC`, `BNK1DCS`, `BNK1TFN`, `BNK1UAC`, `BNKMENU`, `CRDTAGY1`, `CRDTAGY2`, `CRDTAGY3`, `CRDTAGY4`, `CRDTAGY5`, `CREACC`, `CRECUST`, `DBCRFUN`, `DELACC`, `DELCUS`, `INQACC`, `INQACCCU`, `INQACCS`, `INQCUST`, `INQTRAND`, `INQTRANL`, `UPDACC`, `UPDCUST`, `XFRFUN`


**ABNDINFO** — 681 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `ABND-VSAM-KEY` | 0 | 12 |  | group | group |  |
| 5 | &nbsp;&nbsp;`ABND-UTIME-KEY` | 0 | 8 | `S9(15)` | PACKED | BigDecimal | COMP-3 packed, sign nibble C/D/F |
| 5 | &nbsp;&nbsp;`ABND-TASKNO-KEY` | 8 | 4 | `9(4)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `ABND-APPLID` | 12 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 3 | `ABND-TRANID` | 20 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `ABND-DATE` | 24 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 3 | `ABND-TIME` | 34 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 3 | `ABND-CODE` | 42 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `ABND-PROGRAM` | 46 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 3 | `ABND-RESPCODE` | 54 | 9 | `S9(8)` | DISPLAY | int | zoned, separate leading sign byte |
| 3 | `ABND-RESP2CODE` | 63 | 9 | `S9(8)` | DISPLAY | int | zoned, separate leading sign byte |
| 3 | `ABND-SQLCODE` | 72 | 9 | `S9(8)` | DISPLAY | int | zoned, separate leading sign byte |
| 3 | `ABND-FREEFORM` | 81 | 600 | `X(600)` | DISPLAY | String | CP037 text, space padded |

### ACCDB2

- **Location:** `src/base/cics/copy/ACCDB2.cpy`
- **Consumers:** `BANKDATA`, `CREACC`, `DBCRFUN`, `DELACC`, `INQACC`, `INQACCCU`, `INQACCS`, `UPDACC`, `XFRFUN`

- **Contents:** `EXEC SQL DECLARE` for `BANKZ.ACCOUNT` — no storage layout; see [Db2 tables](#db2-tables) for columns and the per-program host variables.


### ACCOUNT

- **Location:** `src/base/cics/copy/ACCOUNT.cpy`
- **Consumers:** `CREACC`, `DBCRFUN`, `DELACC`, `DELCUS`, `INQACC`, `INQACCCU`, `UPDACC`, `XFRFUN`


**ACCOUNT** — 98 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `ACCOUNT-DATA` | 0 | 98 |  | group | group |  |
| 5 | &nbsp;&nbsp;`ACCOUNT-EYE-CATCHER` | 0 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`ACCOUNT-CUST-NO` | 4 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`ACCOUNT-KEY` | 14 | 14 |  | group | group |  |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`ACCOUNT-SORT-CODE` | 14 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`ACCOUNT-NUMBER` | 20 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`ACCOUNT-TYPE` | 28 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`ACCOUNT-INTEREST-RATE` | 36 | 6 | `9(4)V99` | DISPLAY | BigDecimal | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`ACCOUNT-OPENED` | 42 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`ACCOUNT-OPENED-GROUP` | 42 | 8 | ` REDEFINES ACCOUNT-OPENED` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`ACCOUNT-OPENED-DAY` | 42 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`ACCOUNT-OPENED-MONTH` | 44 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`ACCOUNT-OPENED-YEAR` | 46 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`ACCOUNT-OVERDRAFT-LIMIT` | 50 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`ACCOUNT-LAST-STMT-DATE` | 58 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`ACCOUNT-LAST-STMT-GROUP` | 58 | 8 | ` REDEFINES ACCOUNT-LAST-STMT-DATE` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`ACCOUNT-LAST-STMT-DAY` | 58 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`ACCOUNT-LAST-STMT-MONTH` | 60 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`ACCOUNT-LAST-STMT-YEAR` | 62 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`ACCOUNT-NEXT-STMT-DATE` | 66 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`ACCOUNT-NEXT-STMT-GROUP` | 66 | 8 | ` REDEFINES ACCOUNT-NEXT-STMT-DATE` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`ACCOUNT-NEXT-STMT-DAY` | 66 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`ACCOUNT-NEXT-STMT-MONTH` | 68 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`ACCOUNT-NEXT-STMT-YEAR` | 70 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`ACCOUNT-AVAILABLE-BALANCE` | 74 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 5 | &nbsp;&nbsp;`ACCOUNT-ACTUAL-BALANCE` | 86 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |

### ACCTCTRL

- **Location:** `src/base/cics/copy/ACCTCTRL.cpy`
- **Consumers:** `BANKDATA`, `CREACC`, `DELACC`


**ACCTCTRL** — 98 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `ACCOUNT-CONTROL-RECORD` | 0 | 98 |  | group | group |  |
| 5 | &nbsp;&nbsp;`ACCOUNT-CONTROL-EYE-CATCHER` | 0 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`FILLER` | 4 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`ACCOUNT-CONTROL-KEY` | 14 | 14 |  | group | group |  |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`ACCOUNT-CONTROL-SORT-CODE` | 14 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`ACCOUNT-CONTROL-NUMBER` | 20 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`NUMBER-OF-ACCOUNTS` | 28 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`LAST-ACCOUNT-NUMBER` | 36 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`ACCOUNT-CONTROL-SUCCESS-FLAG` | 44 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`ACCOUNT-CONTROL-FAIL-CODE` | 45 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`FILLER` | 46 | 6 | `9(4)V99` | DISPLAY | BigDecimal | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`FILLER` | 52 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`FILLER` | 60 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`FILLER` | 68 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`FILLER` | 76 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`FILLER` | 84 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 5 | &nbsp;&nbsp;`FILLER` | 96 | 2 | `X(2)` | DISPLAY | String | CP037 text, space padded |

### CONTDB2

- **Location:** `src/base/cics/copy/CONTDB2.cpy`
- **Consumers:** `BANKDATA`, `CRECUST`

- **Contents:** `EXEC SQL DECLARE` for `BANKZ.CONTROL` — no storage layout; see [Db2 tables](#db2-tables) for columns and the per-program host variables.


### CREACC

- **Location:** `src/base/cics/copy/CREACC.cpy`
- **Consumers:** `CREACC`


**CREACC** — 100 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `COMM-EYECATCHER` | 0 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-CUSTNO` | 4 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 3 | `COMM-KEY` | 14 | 14 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-SORTCODE` | 14 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-NUMBER` | 20 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-ACC-TYPE` | 28 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-INT-RT` | 36 | 6 | `9(4)V99` | DISPLAY | BigDecimal | zoned X'F0'-X'F9' |
| 3 | `COMM-OPENED` | 42 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-OPENED-GROUP` | 42 | 8 | ` REDEFINES COMM-OPENED` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-OPENED-DAY` | 42 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-OPENED-MONTH` | 44 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-OPENED-YEAR` | 46 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `COMM-OVERDR-LIM` | 50 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-LAST-STMT-DT` | 58 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-LAST-STMNT-GROUP` | 58 | 8 | ` REDEFINES COMM-LAST-STMT-DT` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-LASTST-DAY` | 58 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-LASTST-MONTH` | 60 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-LASTST-YEAR` | 62 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `COMM-NEXT-STMT-DT` | 66 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-NEXT-STMNT-GROUP` | 66 | 8 | ` REDEFINES COMM-NEXT-STMT-DT` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-NEXTST-DAY` | 66 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-NEXTST-MONTH` | 68 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-NEXTST-YEAR` | 70 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `COMM-AVAIL-BAL` | 74 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `COMM-ACT-BAL` | 86 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `COMM-SUCCESS` | 98 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-FAIL-CODE` | 99 | 1 | `X` | DISPLAY | String | CP037 text, space padded |

### CRECUST

- **Location:** `src/base/cics/copy/CRECUST.cpy`
- **Consumers:** `CRECUST`; z/OS Connect asset `CRECUST` (`providerFiles/*.dai` maps `CRECUST.cpy`)


**CRECUST** — 399 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `COMM-EYECATCHER` | 0 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-KEY` | 4 | 16 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-SORTCODE` | 4 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-NUMBER` | 10 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 3 | `COMM-NAME` | 20 | 110 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-TITLE` | 20 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-FIRST-NAME` | 30 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-LAST-NAME` | 80 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-DOB` | 130 | 8 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-DOB-DAY` | 130 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-DOB-MONTH` | 132 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-DOB-YEAR` | 134 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-PHONE` | 138 | 20 | `X(20)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-ADDR` | 158 | 210 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-ADDR-LINE1` | 158 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-ADDR-LINE2` | 208 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-CITY` | 258 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-POSTCODE` | 308 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-COUNTRY` | 318 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-STATUS` | 368 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-CREATED-DATE` | 378 | 8 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-CREATED-DAY` | 378 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-CREATED-MONTH` | 380 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-CREATED-YEAR` | 382 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-CREDIT-SCORE` | 386 | 3 | `999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-CS-REVIEW-DATE` | 389 | 8 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-CS-REVIEW-DAY` | 389 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-CS-REVIEW-MONTH` | 391 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-CS-REVIEW-YEAR` | 393 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-SUCCESS` | 397 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-FAIL-CODE` | 398 | 1 | `X` | DISPLAY | String | CP037 text, space padded |

### CUSTCTRL

- **Location:** `src/base/cics/copy/CUSTCTRL.cpy`
- **Consumers:** `BANKDATA`, `CRECUST`


**CUSTCTRL** — 259 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `CUSTOMER-CONTROL-RECORD` | 0 | 259 |  | group | group |  |
| 5 | &nbsp;&nbsp;`CUSTOMER-CONTROL-EYECATCHER` | 0 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`CUSTOMER-CONTROL-KEY` | 4 | 16 |  | group | group |  |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-CONTROL-SORTCODE` | 4 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-CONTROL-NUMBER` | 10 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`NUMBER-OF-CUSTOMERS` | 20 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`LAST-CUSTOMER-NUMBER` | 30 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`CUSTOMER-CONTROL-SUCCESS-FLAG` | 40 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`CUSTOMER-CONTROL-FAIL-CODE` | 41 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`FILLER` | 42 | 38 | `X(38)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`FILLER` | 80 | 160 | `X(160)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`FILLER` | 240 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`FILLER` | 248 | 3 | `999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`FILLER` | 251 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |

### CUSTDB2

- **Location:** `src/base/cics/copy/CUSTDB2.cpy`
- **Consumers:** `BANKDATA`, `CRECUST`, `DELCUS`, `INQCUST`, `UPDCUST`

- **Contents:** `EXEC SQL DECLARE` for `BANKZ.CUSTOMER` — no storage layout; see [Db2 tables](#db2-tables) for columns and the per-program host variables.


### CUSTOMER

- **Location:** `src/base/cics/copy/CUSTOMER.cpy`
- **Consumers:** `BANKDATA`, `CRDTAGY1`, `CRDTAGY2`, `CRDTAGY3`, `CRDTAGY4`, `CRDTAGY5`, `CREACC`, `CRECUST`, `DELCUS`, `INQACCCU`, `INQCUST`, `UPDCUST`; z/OS Connect asset `INQCUST` generated copybook `CUSTOMER_response_0.cpy` only (the asset's `.dai` maps `INQCUSTZ.cpy`)


**CUSTOMER** — 397 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `CUSTOMER-RECORD` | 0 | 397 |  | group | group |  |
| 5 | &nbsp;&nbsp;`CUSTOMER-EYECATCHER` | 0 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`CUSTOMER-KEY` | 4 | 16 |  | group | group |  |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-SORTCODE` | 4 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-NUMBER` | 10 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`CUSTOMER-NAME` | 20 | 110 |  | group | group |  |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-TITLE` | 20 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-FIRST-NAME` | 30 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-LAST-NAME` | 80 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`CUSTOMER-DOB` | 130 | 8 |  | group | group |  |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-DOB-DAY` | 130 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-DOB-MONTH` | 132 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-DOB-YEAR` | 134 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`CUSTOMER-PHONE` | 138 | 20 | `X(20)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`CUSTOMER-ADDRESS` | 158 | 210 |  | group | group |  |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-ADDR-LINE1` | 158 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-ADDR-LINE2` | 208 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-CITY` | 258 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-POSTCODE` | 308 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-COUNTRY` | 318 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`CUSTOMER-STATUS` | 368 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`CUSTOMER-CREATED-DATE` | 378 | 8 |  | group | group |  |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-CREATED-DAY` | 378 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-CREATED-MONTH` | 380 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-CREATED-YEAR` | 382 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`CUSTOMER-CREDIT-SCORE` | 386 | 3 | `999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`CUSTOMER-CS-REVIEW-DATE` | 389 | 8 |  | group | group |  |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-CS-REVIEW-DAY` | 389 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-CS-REVIEW-MONTH` | 391 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`CUSTOMER-CS-REVIEW-YEAR` | 393 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9' |

### DELACC

- **Location:** `src/base/cics/copy/DELACC.cpy`
- **Consumers:** `DELACC`


**DELACC-COMMAREA** — 134 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `DELACC-EYE` | 0 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `DELACC-CUSTNO` | 4 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 3 | `DELACC-SCODE` | 14 | 6 | `X(6)` | DISPLAY | String | CP037 text, space padded |
| 3 | `DELACC-ACCNO` | 20 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `DELACC-ACC-TYPE` | 28 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 3 | `DELACC-INT-RATE` | 36 | 6 | `9(4)V99` | DISPLAY | BigDecimal | zoned X'F0'-X'F9' |
| 3 | `DELACC-OPENED` | 42 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `DELACC-OPENED-GROUP` | 42 | 8 | ` REDEFINES DELACC-OPENED` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`DELACC-OPENED-DAY` | 42 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`DELACC-OPENED-MONTH` | 44 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`DELACC-OPENED-YEAR` | 46 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `DELACC-OVERDRAFT` | 50 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `DELACC-LAST-STMT-DT` | 58 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `DELACC-LAST-STMT-GROUP` | 58 | 8 | ` REDEFINES DELACC-LAST-STMT-DT` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`DELACC-LAST-STMT-DAY` | 58 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`DELACC-LAST-STMT-MONTH` | 60 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`DELACC-LAST-STMT-YEAR` | 62 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `DELACC-NEXT-STMT-DT` | 66 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `DELACC-NEXT-STMT-GROUP` | 66 | 8 | ` REDEFINES DELACC-NEXT-STMT-DT` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`DELACC-NEXT-STMT-DAY` | 66 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`DELACC-NEXT-STMT-MONTH` | 68 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`DELACC-NEXT-STMT-YEAR` | 70 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `DELACC-AVAIL-BAL` | 74 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `DELACC-ACTUAL-BAL` | 86 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `DELACC-SUCCESS` | 98 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `DELACC-FAIL-CD` | 99 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `DELACC-DEL-SUCCESS` | 100 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `DELACC-DEL-FAIL-CD` | 101 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `DELACC-DEL-APPLID` | 102 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 3 | `DELACC-DEL-PCB1` | 110 | 8 |  | POINTER | pointer (not migrated) | address; not carried across the boundary |
| 3 | `DELACC-DEL-PCB2` | 118 | 8 |  | POINTER | pointer (not migrated) | address; not carried across the boundary |
| 3 | `DELACC-DEL-PCB3` | 126 | 8 |  | POINTER | pointer (not migrated) | address; not carried across the boundary |

### DELACCZ

- **Location:** `src/base/cics/copy/DELACCZ.cpy`
- **Consumers:** _no COPY in COBOL source_


**DELACCZ** — 1981 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `NUMBER-OF-ACCOUNTS` | 0 | 4 | `S9(8)` | BINARY | int | big-endian binary, TRUNC to PIC digits |
| 3 | `CUSTOMER-NUMBER` | 4 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 3 | `COMM-SUCCESS` | 14 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-FAIL-CODE` | 15 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `CUSTOMER-FOUND` | 16 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-PCB-POINTER` | 17 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `ACCOUNT-DETAILS` | 21 | 98 | ` OCCURS 1 TO 20 DEPENDING ON NUMBER-OF-ACCOUNTS` | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-EYE` | 21 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-CUSTNO` | 25 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-SCODE` | 35 | 6 | `X(6)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-ACCNO` | 41 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-ACC-TYPE` | 49 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-INT-RATE` | 57 | 6 | `9(4)V99` | DISPLAY | BigDecimal | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-OPENED` | 63 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-OPENED-GROUP` | 63 | 8 | ` REDEFINES COMM-OPENED` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-OPENED-DAY` | 63 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-OPENED-MONTH` | 65 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-OPENED-YEAR` | 67 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-OVERDRAFT` | 71 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-LAST-STMT-DT` | 79 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-LAST-STMT-GROUP` | 79 | 8 | ` REDEFINES COMM-LAST-STMT-DT` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-LAST-STMT-DAY` | 79 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-LAST-STMT-MONTH` | 81 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-LAST-STMT-YEAR` | 83 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-NEXT-STMT-DT` | 87 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-NEXT-STMT-GROUP` | 87 | 8 | ` REDEFINES COMM-NEXT-STMT-DT` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-NEXT-STMT-DAY` | 87 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-NEXT-STMT-MONTH` | 89 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-NEXT-STMT-YEAR` | 91 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-AVAIL-BAL` | 95 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 5 | &nbsp;&nbsp;`COMM-ACTUAL-BAL` | 107 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |

### DELCUS

- **Location:** `src/base/cics/copy/DELCUS.cpy`
- **Consumers:** `BNK1DCS`, `DELCUS`; z/OS Connect asset `DELCUS` (`providerFiles/*.dai` maps `DELCUS.cpy`)


**DELCUS** — 399 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `COMM-EYE` | 0 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-SCODE` | 4 | 6 | `X(6)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-CUSTNO` | 10 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-NAME` | 20 | 110 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-TITLE` | 20 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-FIRST-NAME` | 30 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-LAST-NAME` | 80 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-DOB` | 130 | 8 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-DOB-DAY` | 130 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-DOB-MONTH` | 132 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-DOB-YEAR` | 134 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-PHONE` | 138 | 20 | `X(20)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-ADDR` | 158 | 210 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-ADDR-LINE1` | 158 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-ADDR-LINE2` | 208 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-CITY` | 258 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-POSTCODE` | 308 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-COUNTRY` | 318 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-STATUS` | 368 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-CREATED-DATE` | 378 | 8 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-CREATED-DAY` | 378 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-CREATED-MONTH` | 380 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-CREATED-YEAR` | 382 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-CREDIT-SCORE` | 386 | 3 | `9(3)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-CS-REVIEW-DATE` | 389 | 8 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-CS-REVIEW-DAY` | 389 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-CS-REVIEW-MONTH` | 391 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-CS-REVIEW-YEAR` | 393 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-DEL-SUCCESS` | 397 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-DEL-FAIL-CD` | 398 | 1 | `X` | DISPLAY | String | CP037 text, space padded |

### GETCOMPY

- **Location:** `src/base/cics/copy/GETCOMPY.cpy`
- **Consumers:** `GETCOMPY`


**GETCOMPY** — 40 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `GETCOMPANYOPERATION` | 0 | 40 |  | group | group |  |
| 6 | &nbsp;&nbsp;`COMPANY-NAME` | 0 | 40 | `x(40)` | DISPLAY | String | CP037 text, space padded |

### GETSCODE

- **Location:** `src/base/cics/copy/GETSCODE.cpy`
- **Consumers:** `GETSCODE`


**GETSCODE** — 6 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `GETSORTCODEOPERATION` | 0 | 6 |  | group | group |  |
| 6 | &nbsp;&nbsp;`SORTCODE` | 0 | 6 | `xXXXXX` | DISPLAY | String | CP037 text, space padded |

### INQACC

- **Location:** `src/base/cics/copy/INQACC.cpy`
- **Consumers:** `BNK1DAC`, `INQACC`


**INQACC-COMMAREA** — 107 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `INQACC-EYE` | 0 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQACC-CUSTNO` | 4 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 3 | `INQACC-SCODE` | 14 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQACC-ACCNO` | 20 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQACC-ACC-TYPE` | 28 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQACC-INT-RATE` | 36 | 6 | `9(4)V99` | DISPLAY | BigDecimal | zoned X'F0'-X'F9' |
| 3 | `INQACC-OPENED` | 42 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQACC-OPENED-GROUP` | 42 | 8 | ` REDEFINES INQACC-OPENED` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-OPENED-DAY` | 42 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-OPENED-MONTH` | 44 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-OPENED-YEAR` | 46 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `INQACC-OVERDRAFT` | 50 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQACC-LAST-STMT-DT` | 58 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQACC-LAST-STMT-GROUP` | 58 | 8 | ` REDEFINES INQACC-LAST-STMT-DT` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-LAST-STMT-DAY` | 58 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-LAST-STMT-MONTH` | 60 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-LAST-STMT-YEAR` | 62 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `INQACC-NEXT-STMT-DT` | 66 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQACC-NEXT-STMT-GROUP` | 66 | 8 | ` REDEFINES INQACC-NEXT-STMT-DT` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-NEXT-STMT-DAY` | 66 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-NEXT-STMT-MONTH` | 68 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-NEXT-STMT-YEAR` | 70 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `INQACC-AVAIL-BAL` | 74 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `INQACC-ACTUAL-BAL` | 86 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `INQACC-SUCCESS` | 98 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQACC-PCB1-POINTER` | 99 | 8 |  | POINTER | pointer (not migrated) | address; not carried across the boundary |

### INQACCCU

- **Location:** `src/base/cics/copy/INQACCCU.cpy`
- **Consumers:** `BNK1CCA`, `CREACC`, `DELCUS`, `INQACCCU`


**INQACCCU** — 1985 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `NUMBER-OF-ACCOUNTS` | 0 | 4 | `S9(8)` | BINARY | int | big-endian binary, TRUNC to PIC digits |
| 3 | `CUSTOMER-NUMBER` | 4 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 3 | `COMM-SUCCESS` | 14 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-FAIL-CODE` | 15 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `CUSTOMER-FOUND` | 16 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-PCB-POINTER` | 17 | 8 |  | POINTER | pointer (not migrated) | address; not carried across the boundary |
| 3 | `ACCOUNT-DETAILS` | 25 | 98 | ` OCCURS 1 TO 20 DEPENDING ON NUMBER-OF-ACCOUNTS` | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-EYE` | 25 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-CUSTNO` | 29 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-SCODE` | 39 | 6 | `X(6)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-ACCNO` | 45 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-ACC-TYPE` | 53 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-INT-RATE` | 61 | 6 | `9(4)V99` | DISPLAY | BigDecimal | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-OPENED` | 67 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-OPENED-GROUP` | 67 | 8 | ` REDEFINES COMM-OPENED` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-OPENED-DAY` | 67 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-OPENED-MONTH` | 69 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-OPENED-YEAR` | 71 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-OVERDRAFT` | 75 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-LAST-STMT-DT` | 83 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-LAST-STMT-GROUP` | 83 | 8 | ` REDEFINES COMM-LAST-STMT-DT` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-LAST-STMT-DAY` | 83 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-LAST-STMT-MONTH` | 85 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-LAST-STMT-YEAR` | 87 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-NEXT-STMT-DT` | 91 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-NEXT-STMT-GROUP` | 91 | 8 | ` REDEFINES COMM-NEXT-STMT-DT` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-NEXT-STMT-DAY` | 91 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-NEXT-STMT-MONTH` | 93 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-NEXT-STMT-YEAR` | 95 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-AVAIL-BAL` | 99 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 5 | &nbsp;&nbsp;`COMM-ACTUAL-BAL` | 111 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |

### INQACCCZ

- **Location:** `src/base/cics/copy/INQACCCZ.cpy`
- **Consumers:** _no COPY in COBOL source_; z/OS Connect asset `INQACCCU` (`providerFiles/*.dai` maps `INQACCCZ.cpy`)


**INQACCCZ** — 1981 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `NUMBER-OF-ACCOUNTS` | 0 | 4 | `S9(8)` | BINARY | int | big-endian binary, TRUNC to PIC digits |
| 3 | `CUSTOMER-NUMBER` | 4 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 3 | `COMM-SUCCESS` | 14 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-FAIL-CODE` | 15 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `CUSTOMER-FOUND` | 16 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-PCB-POINTER` | 17 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `ACCOUNT-DETAILS` | 21 | 98 | ` OCCURS 1 TO 20 DEPENDING ON NUMBER-OF-ACCOUNTS` | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-EYE` | 21 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-CUSTNO` | 25 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-SCODE` | 35 | 6 | `X(6)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-ACCNO` | 41 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-ACC-TYPE` | 49 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-INT-RATE` | 57 | 6 | `9(4)V99` | DISPLAY | BigDecimal | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-OPENED` | 63 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-OPENED-GROUP` | 63 | 8 | ` REDEFINES COMM-OPENED` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-OPENED-DAY` | 63 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-OPENED-MONTH` | 65 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-OPENED-YEAR` | 67 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-OVERDRAFT` | 71 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-LAST-STMT-DT` | 79 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-LAST-STMT-GROUP` | 79 | 8 | ` REDEFINES COMM-LAST-STMT-DT` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-LAST-STMT-DAY` | 79 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-LAST-STMT-MONTH` | 81 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-LAST-STMT-YEAR` | 83 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-NEXT-STMT-DT` | 87 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-NEXT-STMT-GROUP` | 87 | 8 | ` REDEFINES COMM-NEXT-STMT-DT` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-NEXT-STMT-DAY` | 87 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-NEXT-STMT-MONTH` | 89 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-NEXT-STMT-YEAR` | 91 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-AVAIL-BAL` | 95 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 5 | &nbsp;&nbsp;`COMM-ACTUAL-BAL` | 107 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |

### INQACCS

- **Location:** `src/base/cics/copy/INQACCS.cpy`
- **Consumers:** `INQACCS`; z/OS Connect asset `INQACCS` generated copybook `INQACCS_request_0.cpy`/`INQACCS_response_0.cpy` only (the asset's `.dai` maps `INQACCSZ.cpy`)


**INQACCS** — 1970 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `NUMBER-OF-ACCOUNTS` | 0 | 4 | `S9(8)` | BINARY | int | big-endian binary, TRUNC to PIC digits |
| 3 | `COMM-SUCCESS` | 4 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-FAIL-CODE` | 5 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-PCB-POINTER` | 6 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `ACCOUNT-DETAILS` | 10 | 98 | ` OCCURS 1 TO 20 DEPENDING ON NUMBER-OF-ACCOUNTS` | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-EYE` | 10 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-CUSTNO` | 14 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-SCODE` | 24 | 6 | `X(6)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-ACCNO` | 30 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-ACC-TYPE` | 38 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-INT-RATE` | 46 | 6 | `9(4)V99` | DISPLAY | BigDecimal | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-OPENED` | 52 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-OPENED-GROUP` | 52 | 8 | ` REDEFINES COMM-OPENED` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-OPENED-DAY` | 52 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-OPENED-MONTH` | 54 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-OPENED-YEAR` | 56 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-OVERDRAFT` | 60 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-LAST-STMT-DT` | 68 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-LAST-STMT-GROUP` | 68 | 8 | ` REDEFINES COMM-LAST-STMT-DT` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-LAST-STMT-DAY` | 68 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-LAST-STMT-MONTH` | 70 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-LAST-STMT-YEAR` | 72 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-NEXT-STMT-DT` | 76 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-NEXT-STMT-GROUP` | 76 | 8 | ` REDEFINES COMM-NEXT-STMT-DT` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-NEXT-STMT-DAY` | 76 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-NEXT-STMT-MONTH` | 78 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-NEXT-STMT-YEAR` | 80 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-AVAIL-BAL` | 84 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 5 | &nbsp;&nbsp;`COMM-ACTUAL-BAL` | 96 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |

### INQACCSZ

- **Location:** `src/base/cics/copy/INQACCSZ.cpy`
- **Consumers:** _no COPY in COBOL source_; z/OS Connect asset `INQACCS` (`providerFiles/*.dai` maps `INQACCSZ.cpy`)


**INQACCS** — 1970 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `NUMBER-OF-ACCOUNTS` | 0 | 4 | `S9(8)` | BINARY | int | big-endian binary, TRUNC to PIC digits |
| 3 | `COMM-SUCCESS` | 4 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-FAIL-CODE` | 5 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-PCB-POINTER` | 6 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `ACCOUNT-DETAILS` | 10 | 98 | ` OCCURS 1 TO 20 DEPENDING ON NUMBER-OF-ACCOUNTS` | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-EYE` | 10 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-CUSTNO` | 14 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-SCODE` | 24 | 6 | `X(6)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-ACCNO` | 30 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-ACC-TYPE` | 38 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-INT-RATE` | 46 | 6 | `9(4)V99` | DISPLAY | BigDecimal | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-OPENED` | 52 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-OPENED-GROUP` | 52 | 8 | ` REDEFINES COMM-OPENED` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-OPENED-DAY` | 52 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-OPENED-MONTH` | 54 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-OPENED-YEAR` | 56 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-OVERDRAFT` | 60 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-LAST-STMT-DT` | 68 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-LAST-STMT-GROUP` | 68 | 8 | ` REDEFINES COMM-LAST-STMT-DT` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-LAST-STMT-DAY` | 68 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-LAST-STMT-MONTH` | 70 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-LAST-STMT-YEAR` | 72 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-NEXT-STMT-DT` | 76 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-NEXT-STMT-GROUP` | 76 | 8 | ` REDEFINES COMM-NEXT-STMT-DT` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-NEXT-STMT-DAY` | 76 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-NEXT-STMT-MONTH` | 78 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`COMM-NEXT-STMT-YEAR` | 80 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-AVAIL-BAL` | 84 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 5 | &nbsp;&nbsp;`COMM-ACTUAL-BAL` | 96 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |

### INQACCZ

- **Location:** `src/base/cics/copy/INQACCZ.cpy`
- **Consumers:** _no COPY in COBOL source_; z/OS Connect asset `INQACC` (`providerFiles/*.dai` maps `INQACCZ.cpy`)


**INQACC-COMMAREA** — 103 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `INQACC-EYE` | 0 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQACC-CUSTNO` | 4 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 3 | `INQACC-SCODE` | 14 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQACC-ACCNO` | 20 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQACC-ACC-TYPE` | 28 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQACC-INT-RATE` | 36 | 6 | `9(4)V99` | DISPLAY | BigDecimal | zoned X'F0'-X'F9' |
| 3 | `INQACC-OPENED` | 42 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQACC-OPENED-GROUP` | 42 | 8 | ` REDEFINES INQACC-OPENED` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-OPENED-DAY` | 42 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-OPENED-MONTH` | 44 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-OPENED-YEAR` | 46 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `INQACC-OVERDRAFT` | 50 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQACC-LAST-STMT-DT` | 58 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQACC-LAST-STMT-GROUP` | 58 | 8 | ` REDEFINES INQACC-LAST-STMT-DT` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-LAST-STMT-DAY` | 58 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-LAST-STMT-MONTH` | 60 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-LAST-STMT-YEAR` | 62 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `INQACC-NEXT-STMT-DT` | 66 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQACC-NEXT-STMT-GROUP` | 66 | 8 | ` REDEFINES INQACC-NEXT-STMT-DT` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-NEXT-STMT-DAY` | 66 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-NEXT-STMT-MONTH` | 68 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQACC-NEXT-STMT-YEAR` | 70 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `INQACC-AVAIL-BAL` | 74 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `INQACC-ACTUAL-BAL` | 86 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `INQACC-SUCCESS` | 98 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQACC-PCB1-POINTER` | 99 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |

### INQCUSTZ

- **Location:** `src/base/cics/copy/INQCUSTZ.cpy`
- **Consumers:** `BNK1DCS`, `CREACC`, `DELCUS`, `INQACCCU`, `INQCUST`; z/OS Connect asset `INQCUST` (`providerFiles/*.dai` maps `INQCUSTZ.cpy`)


**INQCUSTZ** — 403 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `INQCUST-EYE` | 0 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQCUST-SCODE` | 4 | 6 | `X(6)` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQCUST-CUSTNO` | 10 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 3 | `INQCUST-NAME` | 20 | 110 |  | group | group |  |
| 5 | &nbsp;&nbsp;`INQCUST-TITLE` | 20 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`INQCUST-FIRST-NAME` | 30 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`INQCUST-LAST-NAME` | 80 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQCUST-DOB` | 130 | 8 |  | group | group |  |
| 5 | &nbsp;&nbsp;`INQCUST-DOB-DD` | 130 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`INQCUST-DOB-MM` | 132 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`INQCUST-DOB-YYYY` | 134 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQCUST-PHONE` | 138 | 20 | `X(20)` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQCUST-ADDR` | 158 | 210 |  | group | group |  |
| 5 | &nbsp;&nbsp;`INQCUST-ADDR-LINE1` | 158 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`INQCUST-ADDR-LINE2` | 208 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`INQCUST-CITY` | 258 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`INQCUST-POSTCODE` | 308 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`INQCUST-COUNTRY` | 318 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQCUST-STATUS` | 368 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQCUST-CREATED-DATE` | 378 | 8 |  | group | group |  |
| 5 | &nbsp;&nbsp;`INQCUST-CREATED-DD` | 378 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`INQCUST-CREATED-MM` | 380 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`INQCUST-CREATED-YYYY` | 382 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQCUST-CREDIT-SCORE` | 386 | 3 | `999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQCUST-CS-REVIEW-DT` | 389 | 8 |  | group | group |  |
| 5 | &nbsp;&nbsp;`INQCUST-CS-REVIEW-DD` | 389 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`INQCUST-CS-REVIEW-MM` | 391 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`INQCUST-CS-REVIEW-YYYY` | 393 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQCUST-INQ-SUCCESS` | 397 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQCUST-INQ-FAIL-CD` | 398 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQCUST-PCB-POINTER` | 399 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |

### INQTRAND

- **Location:** `src/base/cics/copy/INQTRAND.cpy`
- **Consumers:** `INQTRAND`; z/OS Connect asset `INQTRAND` (`providerFiles/*.dai` maps `INQTRAND.cpy`)


**INQTRAND-COMMAREA** — 191 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `INQTRAND-EYE` | 0 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQTRAND-SORTCODE` | 4 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQTRAND-ACCNO` | 10 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQTRAND-DATE` | 18 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQTRAND-TIME` | 26 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQTRAND-REF` | 32 | 12 | `9(12)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 3 | `INQTRAND-SUCCESS` | 44 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQTRAND-FOUND` | 45 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQTRAND-TRAN-ID` | 46 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQTRAND-TRAN-SORTCODE` | 96 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQTRAND-TRAN-ACCNO` | 102 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQTRAND-TRAN-DATE` | 110 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQTRAND-TRAN-DATE-GRP` | 110 | 8 | ` REDEFINES INQTRAND-TRAN-DATE` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQTRAND-TRAN-DATE-YYYY` | 110 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQTRAND-TRAN-DATE-MM` | 114 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQTRAND-TRAN-DATE-DD` | 116 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `INQTRAND-TRAN-TIME` | 118 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQTRAND-TRAN-TIME-GRP` | 118 | 6 | ` REDEFINES INQTRAND-TRAN-TIME` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQTRAND-TRAN-TIME-HH` | 118 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQTRAND-TRAN-TIME-MM` | 120 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQTRAND-TRAN-TIME-SS` | 122 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `INQTRAND-TRAN-REF` | 124 | 12 | `9(12)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 3 | `INQTRAND-TRAN-TYPE` | 136 | 3 | `X(3)` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQTRAND-TRAN-DESC` | 139 | 40 | `X(40)` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQTRAND-TRAN-AMOUNT` | 179 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |

### INQTRANL

- **Location:** `src/base/cics/copy/INQTRANL.cpy`
- **Consumers:** `INQTRANL`; z/OS Connect asset `INQTRANL` (`providerFiles/*.dai` maps `INQTRANL.cpy`)


**INQTRANL-COMMAREA** — 14551 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `INQTRANL-EYE` | 0 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQTRANL-SORTCODE` | 4 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQTRANL-ACCNO` | 10 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQTRANL-FROM-DATE` | 18 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQTRANL-TO-DATE` | 26 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQTRANL-LIMIT` | 34 | 3 | `9(3)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQTRANL-OFFSET` | 37 | 5 | `9(5)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQTRANL-TOTAL-COUNT` | 42 | 5 | `9(5)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQTRANL-RETURNED-COUNT` | 47 | 3 | `9(3)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `INQTRANL-SUCCESS` | 50 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `INQTRANL-TRANSACTIONS` | 51 | 145 | ` OCCURS 100` | group | group |  |
| 5 | &nbsp;&nbsp;`INQTRANL-TRAN-ID` | 51 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`INQTRANL-TRAN-SORTCODE` | 101 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`INQTRANL-TRAN-ACCNO` | 107 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`INQTRANL-TRAN-DATE` | 115 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`INQTRANL-TRAN-DATE-GRP` | 115 | 8 | ` REDEFINES INQTRANL-TRAN-DATE` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`INQTRANL-TRAN-DATE-YYYY` | 115 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`INQTRANL-TRAN-DATE-MM` | 119 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`INQTRANL-TRAN-DATE-DD` | 121 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQTRANL-TRAN-TIME` | 123 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`INQTRANL-TRAN-TIME-GRP` | 123 | 6 | ` REDEFINES INQTRANL-TRAN-TIME` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`INQTRANL-TRAN-TIME-HH` | 123 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`INQTRANL-TRAN-TIME-MM` | 125 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`INQTRANL-TRAN-TIME-SS` | 127 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`INQTRANL-TRAN-REF` | 129 | 12 | `9(12)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`INQTRANL-TRAN-TYPE` | 141 | 3 | `X(3)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`INQTRANL-TRAN-DESC` | 144 | 40 | `X(40)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`INQTRANL-TRAN-AMOUNT` | 184 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |

### NEWACCNO

- **Location:** `src/base/cics/copy/NEWACCNO.cpy`
- **Consumers:** _no COPY in COBOL source_


**NEWACCNO** — 11 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `NEWACCNO-FUNCTION` | 0 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `ACCOUNT-NUMBER` | 1 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `NEWACCNO-SUCCESS` | 9 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `NEWACCNO-FAIL-CODE` | 10 | 1 | `X` | DISPLAY | String | CP037 text, space padded |

### NEWCUSNO

- **Location:** `src/base/cics/copy/NEWCUSNO.cpy`
- **Consumers:** _no COPY in COBOL source_


**NEWCUSNO** — 13 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `NEWCUSNO-FUNCTION` | 0 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `CUSTOMER-NUMBER` | 1 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 3 | `NEWCUSNO-SUCCESS` | 11 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `NEWCUSNO-FAIL-CODE` | 12 | 1 | `X` | DISPLAY | String | CP037 text, space padded |

### PAYDBCR

- **Location:** `src/base/cics/copy/PAYDBCR.cpy`
- **Consumers:** `DBCRFUN`


**PAYDBCR** — 132 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `COMM-ACCNO` | 0 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-AMT` | 8 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `COMM-SORTC` | 20 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-AV-BAL` | 26 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `COMM-ACT-BAL` | 38 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `COMM-ORIGIN` | 50 | 80 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-APPLID` | 50 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-USERID` | 58 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-FACILITY-NAME` | 66 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-NETWRK-ID` | 74 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-FACILTYPE` | 82 | 4 | `S9(8)` | BINARY | int | big-endian binary, TRUNC to PIC digits |
| 5 | &nbsp;&nbsp;`FILLER` | 86 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-DESCRIPTION` | 90 | 40 | `X(40)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-SUCCESS` | 130 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-FAIL-CODE` | 131 | 1 | `X` | DISPLAY | String | CP037 text, space padded |

### PAYDBCRZ

- **Location:** `src/base/cics/copy/PAYDBCRZ.cpy`
- **Consumers:** _no COPY in COBOL source_; z/OS Connect asset `DBCRFUN` (`providerFiles/*.dai` maps `PAYDBCRZ.cpy`)


**PAYDBCRZ** — 132 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `COMM-ACCNO` | 0 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-AMT` | 8 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `COMM-SORTC` | 20 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-AV-BAL` | 26 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `COMM-ACT-BAL` | 38 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `COMM-ORIGIN` | 50 | 80 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-APPLID` | 50 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-USERID` | 58 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-FACILITY-NAME` | 66 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-NETWRK-ID` | 74 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-FACILTYPE` | 82 | 4 | `S9(8)` | BINARY | int | big-endian binary, TRUNC to PIC digits |
| 5 | &nbsp;&nbsp;`FILLER` | 86 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-DESCRIPTION` | 90 | 40 | `X(40)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-SUCCESS` | 130 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-FAIL-CODE` | 131 | 1 | `X` | DISPLAY | String | CP037 text, space padded |

### PROCDB2

- **Location:** `src/base/cics/copy/PROCDB2.cpy`
- **Consumers:** `CREACC`, `CRECUST`, `DBCRFUN`, `DELACC`, `DELCUS`, `INQTRAND`, `INQTRANL`, `XFRFUN`

- **Contents:** `EXEC SQL DECLARE` for `BANKZ.PROCTRAN` — no storage layout; see [Db2 tables](#db2-tables) for columns and the per-program host variables.


### PROCISRT

- **Location:** `src/base/cics/copy/PROCISRT.cpy`
- **Consumers:** _no COPY in COBOL source_


**PROCISRT-COMMAREA** — 135 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `PROCISRT-FUNCTION` | 0 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `PROCISRT-PCB-POINTER` | 1 | 8 |  | POINTER | pointer (not migrated) | address; not carried across the boundary |
| 3 | `PROCISRT-DEBIT-STRUCT` | 9 | 126 |  | group | group |  |
| 5 | &nbsp;&nbsp;`PROCISRT-DEBIT-ACCNO` | 9 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`PROCISRT-DEBIT-SORTCODE` | 17 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`PROCISRT-DEBIT-AMOUNT` | 23 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 5 | &nbsp;&nbsp;`FILLER` | 35 | 100 | `X(100)` | DISPLAY | String | CP037 text, space padded |
| 3 | `PROCISRT-CREDIT-STRUCT` | 9 | 26 | ` REDEFINES PROCISRT-DEBIT-STRUCT` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-CREDIT-ACCNO` | 9 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-CREDIT-SORTCODE` | 17 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-CREDIT-AMOUNT` | 23 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D); alternate view (REDEFINES) |
| 3 | `PROCISRT-XFR-LOCAL-STRUCT` | 9 | 34 | ` REDEFINES PROCISRT-DEBIT-STRUCT` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-XFR-L-ACCNO` | 9 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-XFR-L-SORTCODE` | 17 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-XFR-L-AMOUNT` | 23 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D); alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-XFR-L-TARGET-ACCNO` | 35 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `PROCISRT-DELETE-CUST-STRUCT` | 9 | 104 | ` REDEFINES PROCISRT-DEBIT-STRUCT` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-DELETE-CUST-ACCNO` | 9 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-DELETE-CUST-SORTCODE` | 17 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-DELETE-CUST-BALANCE` | 23 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D); alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-DELETE-CUST-DOB` | 35 | 8 |  | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-DELETE-CUST-DOB-YYYY` | 35 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-DELETE-CUST-DOB-MM` | 39 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-DELETE-CUST-DOB-DD` | 41 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-DELETE-CUST-NAME` | 43 | 60 | `X(60)` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-DELETE-CUST-NUMBER` | 103 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `PROCISRT-CREATE-CUST-STRUCT` | 9 | 104 | ` REDEFINES PROCISRT-DEBIT-STRUCT` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-CREATE-CUST-ACCNO` | 9 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-CREATE-CUST-SORTCODE` | 17 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-CREATE-CUST-BALANCE` | 23 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D); alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-CREATE-CUST-DOB` | 35 | 8 |  | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-CREATE-CUST-DOB-YYYY` | 35 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-CREATE-CUST-DOB-MM` | 39 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-CREATE-CUST-DOB-DD` | 41 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-CREATE-CUST-NAME` | 43 | 60 | `X(60)` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-CREATE-CUST-NUMBER` | 103 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `PROCISRT-DELACC-STRUCT` | 9 | 60 | ` REDEFINES PROCISRT-DEBIT-STRUCT` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-DELACC-ACCNO` | 9 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-DELACC-SORTCODE` | 17 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-DELACC-BALANCE` | 23 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D); alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-DELACC-L-STMNT` | 35 | 8 |  | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-DELACC-L-STMNT-YYYY` | 35 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-DELACC-L-STMNT-MM` | 39 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-DELACC-L-STMNT-DD` | 41 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-DELACC-N-STMNT` | 43 | 8 |  | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-DELACC-N-STMNT-YYYY` | 43 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-DELACC-N-STMNT-MM` | 47 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-DELACC-N-STMNT-DD` | 49 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-DELACC-TYPE` | 51 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-DELACC-CUSTNO` | 59 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `PROCISRT-CREACC-STRUCT` | 9 | 60 | ` REDEFINES PROCISRT-DEBIT-STRUCT` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-CREACC-ACCNO` | 9 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-CREACC-SORTCODE` | 17 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-CREACC-BALANCE` | 23 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D); alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-CREACC-L-STMNT` | 35 | 8 |  | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-CREACC-L-STMNT-YYYY` | 35 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-CREACC-L-STMNT-MM` | 39 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-CREACC-L-STMNT-DD` | 41 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-CREAET-ACCT-N-STMNT` | 43 | 8 |  | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-CREACC-N-STMNT-YYYY` | 43 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-CREACC-N-STMNT-MM` | 47 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROCISRT-CREACC-N-STMNT-DD` | 49 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-CREACC-TYPE` | 51 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROCISRT-CREACC-CUSTNO` | 59 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9'; alternate view (REDEFINES) |

### PROCTRAN

- **Location:** `src/base/cics/copy/PROCTRAN.cpy`
- **Consumers:** `CREACC`, `CRECUST`, `DBCRFUN`, `DELACC`, `DELCUS`, `XFRFUN`


**PROCTRAN** — 99 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `PROC-TRAN-DATA` | 0 | 99 |  | group | group |  |
| 5 | &nbsp;&nbsp;`PROC-TRAN-EYE-CATCHER` | 0 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`PROC-TRAN-LOGICAL-DELETE-AREA` | 0 | 4 | ` REDEFINES PROC-TRAN-EYE-CATCHER` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-TRAN-LOGICAL-DELETE-FLAG` | 0 | 1 | `X` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`FILLER` | 1 | 3 | `X(3)` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROC-TRAN-ID` | 4 | 14 |  | group | group |  |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-TRAN-SORT-CODE` | 4 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-TRAN-NUMBER` | 10 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`PROC-TRAN-DATE` | 18 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`PROC-TRAN-DATE-GRP` | 18 | 8 | ` REDEFINES PROC-TRAN-DATE` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-TRAN-DATE-GRP-YYYY` | 18 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-TRAN-DATE-GRP-MM` | 22 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-TRAN-DATE-GRP-DD` | 24 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROC-TRAN-TIME` | 26 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`PROC-TRAN-TIME-GRP` | 26 | 6 | ` REDEFINES PROC-TRAN-TIME` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-TRAN-TIME-GRP-HH` | 26 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-TRAN-TIME-GRP-MM` | 28 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-TRAN-TIME-GRP-SS` | 30 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROC-TRAN-REF` | 32 | 12 | `9(12)` | DISPLAY | long | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`PROC-TRAN-TYPE` | 44 | 3 | `X(3)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`PROC-TRAN-DESC` | 47 | 40 | `X(40)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`PROC-TRAN-DESC-XFR` | 47 | 40 | ` REDEFINES PROC-TRAN-DESC` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-TRAN-DESC-XFR-HEADER` | 47 | 26 | `X(26)` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-TRAN-DESC-XFR-SORTCODE` | 73 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-TRAN-DESC-XFR-ACCOUNT` | 79 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROC-TRAN-DESC-DELACC` | 47 | 40 | ` REDEFINES PROC-TRAN-DESC` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELACC-CUSTOMER` | 47 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELACC-ACCTYPE` | 57 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELACC-LAST-DD` | 65 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELACC-LAST-MM` | 67 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELACC-LAST-YYYY` | 69 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELACC-NEXT-DD` | 73 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELACC-NEXT-MM` | 75 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELACC-NEXT-YYYY` | 77 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELACC-FOOTER` | 81 | 6 | `X(6)` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROC-TRAN-DESC-CREACC` | 47 | 40 | ` REDEFINES PROC-TRAN-DESC` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CREACC-CUSTOMER` | 47 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CREACC-ACCTYPE` | 57 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CREACC-LAST-DD` | 65 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CREACC-LAST-MM` | 67 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CREACC-LAST-YYYY` | 69 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CREACC-NEXT-DD` | 73 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CREACC-NEXT-MM` | 75 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CREACC-NEXT-YYYY` | 77 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CREACC-FOOTER` | 81 | 6 | `X(6)` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROC-TRAN-DESC-DELCUS` | 47 | 40 | ` REDEFINES PROC-TRAN-DESC` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELCUS-SORTCODE` | 47 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELCUS-CUSTOMER` | 53 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELCUS-NAME` | 63 | 14 | `X(14)` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELCUS-DOB-YYYY` | 77 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELCUS-FILLER` | 81 | 1 | `X` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELCUS-DOB-MM` | 82 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELCUS-FILLER2` | 84 | 1 | `X` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-DELCUS-DOB-DD` | 85 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROC-TRAN-DESC-CRECUS` | 47 | 40 | ` REDEFINES PROC-TRAN-DESC` | group | group | alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CRECUS-SORTCODE` | 47 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CRECUS-CUSTOMER` | 53 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CRECUS-NAME` | 63 | 14 | `X(14)` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CRECUS-DOB-YYYY` | 77 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CRECUS-FILLER` | 81 | 1 | `X` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CRECUS-DOB-MM` | 82 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CRECUS-FILLER2` | 84 | 1 | `X` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 7 | &nbsp;&nbsp;&nbsp;&nbsp;`PROC-DESC-CRECUS-DOB-DD` | 85 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`PROC-TRAN-AMOUNT` | 87 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |

### SORTCODE

- **Location:** `src/base/cics/copy/SORTCODE.cpy`
- **Consumers:** `BANKDATA`, `CRDTAGY1`, `CRDTAGY2`, `CRDTAGY3`, `CRDTAGY4`, `CRDTAGY5`, `CREACC`, `CRECUST`, `DBCRFUN`, `DELACC`, `DELCUS`, `GETSCODE`, `INQACC`, `INQACCCU`, `INQACCS`, `INQCUST`, `INQTRAND`, `INQTRANL`, `UPDACC`, `UPDCUST`, `XFRFUN`


**SORTCODE** — 6 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|

### STCUSTNO

- **Location:** `src/base/cics/copy/STCUSTNO.cpy`
- **Consumers:** _no COPY in COBOL source_


**STCUSTNO** — 10 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 5 | `CUSTOMER-NUMBER-KEY` | 0 | 10 |  | group | group |  |
| 10 | &nbsp;&nbsp;`CNO-KEY` | 0 | 10 | `9(10)` | DISPLAY | long | zoned X'F0'-X'F9' |

### UPDACC

- **Location:** `src/base/cics/copy/UPDACC.cpy`
- **Consumers:** `UPDACC`


**UPDACC** — 99 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `COMM-EYE` | 0 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-CUSTNO` | 4 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-SCODE` | 14 | 6 | `X(6)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-ACCNO` | 20 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-ACC-TYPE` | 28 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-INT-RATE` | 36 | 6 | `9(4)V99` | DISPLAY | BigDecimal | zoned X'F0'-X'F9' |
| 3 | `COMM-OPENED` | 42 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-OPENED-GROUP` | 42 | 8 | ` REDEFINES COMM-OPENED` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-OPENED-DAY` | 42 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-OPENED-MONTH` | 44 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-OPENED-YEAR` | 46 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `COMM-OVERDRAFT` | 50 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-LAST-STMT-DT` | 58 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-LAST-STMNT-GROUP` | 58 | 8 | ` REDEFINES COMM-LAST-STMT-DT` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-LASTST-DAY` | 58 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-LASTST-MONTH` | 60 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-LASTST-YEAR` | 62 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `COMM-NEXT-STMT-DT` | 66 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-NEXT-STMNT-GROUP` | 66 | 8 | ` REDEFINES COMM-NEXT-STMT-DT` | group | group | alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-NEXTST-DAY` | 66 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-NEXTST-MONTH` | 68 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 5 | &nbsp;&nbsp;`COMM-NEXTST-YEAR` | 70 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9'; alternate view (REDEFINES) |
| 3 | `COMM-AVAIL-BAL` | 74 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `COMM-ACTUAL-BAL` | 86 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `COMM-SUCCESS` | 98 | 1 | `X` | DISPLAY | String | CP037 text, space padded |

### UPDCUST

- **Location:** `src/base/cics/copy/UPDCUST.cpy`
- **Consumers:** `BNK1DCS`, `UPDCUST`; z/OS Connect asset `UPDCUST` (`providerFiles/*.dai` maps `UPDCUST.cpy`)


**UPDCUST** — 399 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `COMM-EYE` | 0 | 4 | `X(4)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-SCODE` | 4 | 6 | `X(6)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-CUSTNO` | 10 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-NAME` | 20 | 110 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-TITLE` | 20 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-FIRST-NAME` | 30 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-LAST-NAME` | 80 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-DOB` | 130 | 8 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-DOB-DAY` | 130 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-DOB-MONTH` | 132 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-DOB-YEAR` | 134 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-PHONE` | 138 | 20 | `X(20)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-ADDR` | 158 | 210 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-ADDR-LINE1` | 158 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-ADDR-LINE2` | 208 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-CITY` | 258 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-POSTCODE` | 308 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 5 | &nbsp;&nbsp;`COMM-COUNTRY` | 318 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-STATUS` | 368 | 10 | `X(10)` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-CREATED-DATE` | 378 | 8 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-CREATED-DAY` | 378 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-CREATED-MONTH` | 380 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-CREATED-YEAR` | 382 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-CREDIT-SCORE` | 386 | 3 | `9(3)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-CS-REVIEW-DATE` | 389 | 8 |  | group | group |  |
| 5 | &nbsp;&nbsp;`COMM-CS-REVIEW-DAY` | 389 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-CS-REVIEW-MONTH` | 391 | 2 | `99` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | &nbsp;&nbsp;`COMM-CS-REVIEW-YEAR` | 393 | 4 | `9999` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-UPD-SUCCESS` | 397 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-UPD-FAIL-CD` | 398 | 1 | `X` | DISPLAY | String | CP037 text, space padded |

### XFRFUN

- **Location:** `src/base/cics/copy/XFRFUN.cpy`
- **Consumers:** `XFRFUN`


**XFRFUN** — 90 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 3 | `COMM-FACCNO` | 0 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-FSCODE` | 8 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-TACCNO` | 14 | 8 | `9(8)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-TSCODE` | 22 | 6 | `9(6)` | DISPLAY | int | zoned X'F0'-X'F9' |
| 3 | `COMM-AMT` | 28 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `COMM-FAVBAL` | 40 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `COMM-FACTBAL` | 52 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `COMM-TAVBAL` | 64 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `COMM-TACTBAL` | 76 | 12 | `S9(10)V99` | DISPLAY | BigDecimal | zoned, sign overpunched in last byte (C/D) |
| 3 | `COMM-FAIL-CODE` | 88 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 3 | `COMM-SUCCESS` | 89 | 1 | `X` | DISPLAY | String | CP037 text, space padded |

### IBGHIST

- **Location:** `src/base/ims/copy/IBGHIST.cpy`
- **Consumers:** _no COPY in COBOL source_


**INPUT-AREA** — 22 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 5 | `LL-IN` | 0 | 2 | `9(04)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `ZZ-IN` | 2 | 2 | `9(04)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `IN-ACCID` | 4 | 18 | `X(18)` | DISPLAY | String | CP037 text, space padded |

**OUTPUT-AREA** — 103 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 5 | `LL-OUT` | 0 | 2 | `9(04)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `ZZ-OUT` | 2 | 2 | `9(04)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `MSG-OUT` | 4 | 43 | `X(43)` | DISPLAY | String | CP037 text, space padded |
| 5 | `TXID` | 47 | 8 | `S9(18)` | NATIVE_BINARY | long | big-endian binary, full native range |
| 5 | `TIMESTMP` | 55 | 23 | `X(23)` | DISPLAY | String | CP037 text, space padded |
| 5 | `TRANSTYP` | 78 | 1 | `X(1)` | DISPLAY | String | CP037 text, space padded |
| 5 | `AMOUNT` | 79 | 8 | `S9(13)V9(2)` | PACKED | BigDecimal | COMP-3 packed, sign nibble C/D/F |
| 5 | `REFTXID` | 87 | 8 | `S9(18)` | NATIVE_BINARY | long | big-endian binary, full native range |
| 5 | `ACCID` | 95 | 8 | `S9(18)` | NATIVE_BINARY | long | big-endian binary, full native range |

### IBSHIST

- **Location:** `src/base/ims/copy/IBSHIST.cpy`
- **Consumers:** _no COPY in COBOL source_


**INPUT-AREA** — 22 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 5 | `LL-IN` | 0 | 2 | `9(04)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `ZZ-IN` | 2 | 2 | `9(04)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `IN-ACCID` | 4 | 18 | `X(18)` | DISPLAY | String | CP037 text, space padded |

**OUTPUT-AREA** — 103 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 5 | `LL-OUT` | 0 | 2 | `9(04)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `ZZ-OUT` | 2 | 2 | `9(04)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `MSG-OUT` | 4 | 43 | `X(43)` | DISPLAY | String | CP037 text, space padded |
| 5 | `TXID` | 47 | 8 | `S9(18)` | NATIVE_BINARY | long | big-endian binary, full native range |
| 5 | `TIMESTMP` | 55 | 23 | `X(23)` | DISPLAY | String | CP037 text, space padded |
| 5 | `TRANSTYP` | 78 | 1 | `X(1)` | DISPLAY | String | CP037 text, space padded |
| 5 | `AMOUNT` | 79 | 8 | `S9(13)V9(2)` | PACKED | BigDecimal | COMP-3 packed, sign nibble C/D/F |
| 5 | `REFTXID` | 87 | 8 | `S9(18)` | NATIVE_BINARY | long | big-endian binary, full native range |
| 5 | `ACCID` | 95 | 8 | `S9(18)` | NATIVE_BINARY | long | big-endian binary, full native range |

### IBTRAN

- **Location:** `src/base/ims/copy/IBTRAN.cpy`
- **Consumers:** _no COPY in COBOL source_; z/OS Connect asset `IBTRAN` (`providerFiles/*.dai` maps `IBTRAN.cpy`)


**INPUT-AREA** — 56 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 5 | `LL-IN` | 0 | 2 | `9(04)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `ZZ-IN` | 2 | 2 | `9(04)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `TRAN-CODE` | 4 | 8 | `X(08)` | DISPLAY | String | CP037 text, space padded |
| 5 | `IN-ACCID` | 12 | 18 | `X(18)` | DISPLAY | String | CP037 text, space padded |
| 5 | `IN-AMOUNT` | 30 | 16 | `X(16)` | DISPLAY | String | CP037 text, space padded |
| 5 | `IN-TRXTYPE` | 46 | 1 | `X(01)` | DISPLAY | String | CP037 text, space padded |
| 5 | `IN-CUSTID` | 47 | 9 | `X(09)` | DISPLAY | String | CP037 text, space padded |

**OUTPUT-AREA** — 150 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 5 | `LL-OUT` | 0 | 2 | `9(04)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `ZZ-OUT` | 2 | 2 | `9(04)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `MSG-OUT` | 4 | 43 | `X(43)` | DISPLAY | String | CP037 text, space padded |
| 5 | `BAL` | 4 | 43 | ` REDEFINES MSG-OUT` | group | group | alternate view (REDEFINES) |
| 10 | &nbsp;&nbsp;`BALANCE-ZONED1` | 4 | 16 | `Z(13).99` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 10 | &nbsp;&nbsp;`FILLER` | 20 | 27 | `X(27)` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 5 | `TOTAL-ACCS` | 47 | 1 | `9` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | `ACCOUNT-SUMMARY` | 48 | 17 | ` OCCURS 1 TO 6 DEPENDING ON TOTAL-ACCS` | group | group |  |
| 10 | &nbsp;&nbsp;`BALANCE-AS` | 48 | 8 | `S9(13)V9(2)` | PACKED | BigDecimal | COMP-3 packed, sign nibble C/D/F |
| 10 | &nbsp;&nbsp;`ACCTYPE-AS` | 56 | 1 | `X(1)` | DISPLAY | String | CP037 text, space padded |
| 10 | &nbsp;&nbsp;`ACCID-AS` | 57 | 8 | `S9(18)` | NATIVE_BINARY | long | big-endian binary, full native range |

### IBACSUM — INPUT-AREA_request_0

- **Location:** `src/api/src/main/zosAssets/IBACSUM/providerFiles/gen/INPUT-AREA_request_0.cpy`
- **Consumers:** IMS MPP `IBACSUM` (z/OS Connect IMS asset `IBACSUM`)


**INPUT-AREA** — 21 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 5 | `LL-IN` | 0 | 2 | `9(4)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `ZZ-IN` | 2 | 2 | `9(4)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `TRAN-CODE` | 4 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | `IN-CUSTID` | 12 | 9 | `X(9)` | DISPLAY | String | CP037 text, space padded |

### IBACSUM — OUTPUT-AREA_response_0

- **Location:** `src/api/src/main/zosAssets/IBACSUM/providerFiles/gen/OUTPUT-AREA_response_0.cpy`
- **Consumers:** IMS MPP `IBACSUM` (z/OS Connect IMS asset `IBACSUM`)


**OUTPUT-AREA** — 139 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 5 | `LL-OUT` | 0 | 2 | `9(4)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `ZZ-OUT` | 2 | 2 | `9(4)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `MSG-OUT` | 4 | 32 | `X(32)` | DISPLAY | String | CP037 text, space padded |
| 5 | `TOTAL-ACCS` | 36 | 1 | `9` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | `ACCOUNT-SUMMARY` | 37 | 17 | ` OCCURS 1 TO 6 DEPENDING ON TOTAL-ACCS` | group | group |  |
| 10 | &nbsp;&nbsp;`BALANCE-AS` | 37 | 8 | `S9(13)V9(2)` | PACKED | BigDecimal | COMP-3 packed, sign nibble C/D/F |
| 10 | &nbsp;&nbsp;`ACCTYPE-AS` | 45 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 10 | &nbsp;&nbsp;`ACCID-AS` | 46 | 8 | `S9(18)` | NATIVE_BINARY | long | big-endian binary, full native range |

### IBGCUDAT — INPUT-AREA_request_0

- **Location:** `src/api/src/main/zosAssets/IBGCUDAT/providerFiles/gen/INPUT-AREA_request_0.cpy`
- **Consumers:** IMS MPP `IBGCUDAT` (z/OS Connect IMS asset `IBGCUDAT`)


**INPUT-AREA** — 21 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 5 | `LL-IN` | 0 | 2 | `9(4)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `ZZ-IN` | 2 | 2 | `9(4)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `TRAN-CODE` | 4 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | `IN-CUSTID` | 12 | 9 | `X(9)` | DISPLAY | String | CP037 text, space padded |

### IBGCUDAT — OUTPUT-AREA_response_0

- **Location:** `src/api/src/main/zosAssets/IBGCUDAT/providerFiles/gen/OUTPUT-AREA_response_0.cpy`
- **Consumers:** IMS MPP `IBGCUDAT` (z/OS Connect IMS asset `IBGCUDAT`)


**OUTPUT-AREA** — 279 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 5 | `LL-OUT` | 0 | 2 | `9(4)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `ZZ-OUT` | 2 | 2 | `9(4)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `MSG-OUT` | 4 | 32 | `X(32)` | DISPLAY | String | CP037 text, space padded |
| 5 | `FIRSTNAME-OUT` | 36 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | `LASTNAME-OUT` | 86 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 5 | `ADDRESS-OUT` | 136 | 80 | `X(80)` | DISPLAY | String | CP037 text, space padded |
| 5 | `CITY-OUT` | 216 | 25 | `X(25)` | DISPLAY | String | CP037 text, space padded |
| 5 | `STATE-OUT` | 241 | 2 | `X(2)` | DISPLAY | String | CP037 text, space padded |
| 5 | `ZIPCODE-OUT` | 243 | 15 | `X(15)` | DISPLAY | String | CP037 text, space padded |
| 5 | `PHONE-OUT` | 258 | 12 | `X(12)` | DISPLAY | String | CP037 text, space padded |
| 5 | `CUSTID-OUT` | 270 | 9 | `X(9)` | DISPLAY | String | CP037 text, space padded |

### IBSCUDAT — INPUT-AREA_request_0

- **Location:** `src/api/src/main/zosAssets/IBSCUDAT/providerFiles/gen/INPUT-AREA_request_0.cpy`
- **Consumers:** IMS MPP `IBSCUDAT` (z/OS Connect IMS asset `IBSCUDAT`)


**INPUT-AREA** — 250 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 5 | `LL-IN` | 0 | 2 | `9(4)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `ZZ-IN` | 2 | 2 | `9(4)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `TRAN-CODE` | 4 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | `CUSTOMER-IN` | 12 | 238 |  | group | group |  |
| 10 | &nbsp;&nbsp;`CUSTID-IN` | 12 | 4 | `S9(9)` | NATIVE_BINARY | int | big-endian binary, full native range |
| 10 | &nbsp;&nbsp;`LASTNAME-IN` | 16 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 10 | &nbsp;&nbsp;`FIRSTNAME-IN` | 66 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 10 | &nbsp;&nbsp;`ADDRESS-IN` | 116 | 80 | `X(80)` | DISPLAY | String | CP037 text, space padded |
| 10 | &nbsp;&nbsp;`CITY-IN` | 196 | 25 | `X(25)` | DISPLAY | String | CP037 text, space padded |
| 10 | &nbsp;&nbsp;`STATE-IN` | 221 | 2 | `X(2)` | DISPLAY | String | CP037 text, space padded |
| 10 | &nbsp;&nbsp;`ZIPCODE-IN` | 223 | 15 | `X(15)` | DISPLAY | String | CP037 text, space padded |
| 10 | &nbsp;&nbsp;`PHONE-IN` | 238 | 12 | `X(12)` | DISPLAY | String | CP037 text, space padded |

### IBSCUDAT — OUTPUT-AREA_response_0

- **Location:** `src/api/src/main/zosAssets/IBSCUDAT/providerFiles/gen/OUTPUT-AREA_response_0.cpy`
- **Consumers:** IMS MPP `IBSCUDAT` (z/OS Connect IMS asset `IBSCUDAT`)


**OUTPUT-AREA** — 274 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 5 | `LL-OUT` | 0 | 2 | `9(4)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `ZZ-OUT` | 2 | 2 | `9(4)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `MSG-OUT` | 4 | 32 | `X(32)` | DISPLAY | String | CP037 text, space padded |
| 5 | `CUSTOMER-OUT` | 36 | 238 |  | group | group |  |
| 10 | &nbsp;&nbsp;`CUSTID-OUT` | 36 | 4 | `S9(9)` | NATIVE_BINARY | int | big-endian binary, full native range |
| 10 | &nbsp;&nbsp;`LASTNAME-OUT` | 40 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 10 | &nbsp;&nbsp;`FIRSTNAME-OUT` | 90 | 50 | `X(50)` | DISPLAY | String | CP037 text, space padded |
| 10 | &nbsp;&nbsp;`ADDRESS-OUT` | 140 | 80 | `X(80)` | DISPLAY | String | CP037 text, space padded |
| 10 | &nbsp;&nbsp;`CITY-OUT` | 220 | 25 | `X(25)` | DISPLAY | String | CP037 text, space padded |
| 10 | &nbsp;&nbsp;`STATE-OUT` | 245 | 2 | `X(2)` | DISPLAY | String | CP037 text, space padded |
| 10 | &nbsp;&nbsp;`ZIPCODE-OUT` | 247 | 15 | `X(15)` | DISPLAY | String | CP037 text, space padded |
| 10 | &nbsp;&nbsp;`PHONE-OUT` | 262 | 12 | `X(12)` | DISPLAY | String | CP037 text, space padded |

### IBTRAN — INPUT-AREA_request_0

- **Location:** `src/api/src/main/zosAssets/IBTRAN/providerFiles/gen/INPUT-AREA_request_0.cpy`
- **Consumers:** IMS MPP `IBTRAN` (z/OS Connect IMS asset `IBTRAN`)


**INPUT-AREA** — 56 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 5 | `LL-IN` | 0 | 2 | `9(4)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `ZZ-IN` | 2 | 2 | `9(4)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `TRAN-CODE` | 4 | 8 | `X(8)` | DISPLAY | String | CP037 text, space padded |
| 5 | `IN-ACCID` | 12 | 18 | `X(18)` | DISPLAY | String | CP037 text, space padded |
| 5 | `IN-AMOUNT` | 30 | 16 | `X(16)` | DISPLAY | String | CP037 text, space padded |
| 5 | `IN-TRXTYPE` | 46 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 5 | `IN-CUSTID` | 47 | 9 | `X(9)` | DISPLAY | String | CP037 text, space padded |

### IBTRAN — OUTPUT-AREA_response_0

- **Location:** `src/api/src/main/zosAssets/IBTRAN/providerFiles/gen/OUTPUT-AREA_response_0.cpy`
- **Consumers:** IMS MPP `IBTRAN` (z/OS Connect IMS asset `IBTRAN`)


**OUTPUT-AREA** — 150 bytes

| Lvl | Field | Off | Len | PIC / clauses | Usage | Java | Encoding notes |
|---|---|---:|---:|---|---|---|---|
| 5 | `LL-OUT` | 0 | 2 | `9(4)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `ZZ-OUT` | 2 | 2 | `9(4)` | BINARY | short | big-endian binary, TRUNC to PIC digits |
| 5 | `MSG-OUT` | 4 | 43 | `X(43)` | DISPLAY | String | CP037 text, space padded |
| 5 | `BAL` | 4 | 43 | ` REDEFINES MSG-OUT` | group | group | alternate view (REDEFINES) |
| 10 | &nbsp;&nbsp;`BALANCE-ZONED1` | 4 | 16 | `Z(13).9(2)` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 10 | &nbsp;&nbsp;`FILLER` | 20 | 27 | `X(27)` | DISPLAY | String | CP037 text, space padded; alternate view (REDEFINES) |
| 5 | `TOTAL-ACCS` | 47 | 1 | `9` | DISPLAY | int | zoned X'F0'-X'F9' |
| 5 | `ACCOUNT-SUMMARY` | 48 | 17 | ` OCCURS 1 TO 6 DEPENDING ON TOTAL-ACCS` | group | group |  |
| 10 | &nbsp;&nbsp;`BALANCE-AS` | 48 | 8 | `S9(13)V9(2)` | PACKED | BigDecimal | COMP-3 packed, sign nibble C/D/F |
| 10 | &nbsp;&nbsp;`ACCTYPE-AS` | 56 | 1 | `X` | DISPLAY | String | CP037 text, space padded |
| 10 | &nbsp;&nbsp;`ACCID-AS` | 57 | 8 | `S9(18)` | NATIVE_BINARY | long | big-endian binary, full native range |

## Db2 tables

Source of truth: `.setup/jcl/cics/Db2-create.j2` (schema `BANKZ`). The `*DB2.cpy`
copybooks only contain `EXEC SQL DECLARE TABLE`; programs declare their own
host variables (shown in the right-hand column, taken from `CRECUST.cbl`,
`INQACC.cbl`, `BANKDATA.cbl`).

| Table | Column | Db2 type | COBOL host variable | Java (`*Row`) |
|---|---|---|---|---|
| CUSTOMER | CUSTOMER_EYECATCHER | CHAR(4) | X(4) | String |
| CUSTOMER | CUSTOMER_SORTCODE | CHAR(6) NOT NULL | X(6) | String |
| CUSTOMER | CUSTOMER_NUMBER | CHAR(10) NOT NULL | X(10) | String (zero-padded) |
| CUSTOMER | CUSTOMER_TITLE / FIRST_NAME / LAST_NAME | CHAR(10) / CHAR(50) / CHAR(50) | X(n) | String |
| CUSTOMER | CUSTOMER_DATE_OF_BIRTH | INTEGER | S9(9) COMP, YYYYMMDD | Integer |
| CUSTOMER | CUSTOMER_PHONE / ADDR_LINE1 / ADDR_LINE2 / CITY / POSTCODE / COUNTRY / STATUS | CHAR(20/50/50/50/10/50/10) | X(n) | String |
| CUSTOMER | CUSTOMER_CREATED_DATE / CS_REVIEW_DATE | INTEGER | S9(9) COMP, YYYYMMDD | Integer |
| CUSTOMER | CUSTOMER_CREDIT_SCORE | SMALLINT | S9(4) COMP | Short |
| ACCOUNT | ACCOUNT_EYECATCHER / CUSTOMER_NUMBER / SORTCODE / NUMBER / TYPE | CHAR(4/10/6/8/8) | X(n) | String |
| ACCOUNT | ACCOUNT_INTEREST_RATE | DECIMAL(6,2) | S9(4)V99 COMP-3 | BigDecimal |
| ACCOUNT | ACCOUNT_OPENED / LAST_STATEMENT / NEXT_STATEMENT | DATE | X(10) `DD.MM.YYYY` | LocalDate |
| ACCOUNT | ACCOUNT_OVERDRAFT_LIMIT | INTEGER | S9(9) COMP | Integer |
| ACCOUNT | ACCOUNT_AVAILABLE_BALANCE / ACTUAL_BALANCE | DECIMAL(12,2) | S9(10)V99 COMP-3 | BigDecimal |
| PROCTRAN | PROCTRAN_EYECATCHER / SORTCODE / NUMBER | CHAR(4/6/8) | X(n) | String |
| PROCTRAN | PROCTRAN_DATE | DATE | X(10) `DD.MM.YYYY` | LocalDate |
| PROCTRAN | PROCTRAN_TIME / REF / TYPE / DESC | CHAR(6/12/3/40) | X(n) | String |
| PROCTRAN | PROCTRAN_AMOUNT | DECIMAL(12,2) | S9(10)V99 COMP-3 | BigDecimal |
| CONTROL | CONTROL_NAME | CHAR(32) | X(32) | `CounterName.controlName()` |
| CONTROL | CONTROL_VALUE_NUM | INTEGER | S9(9) COMP | long |
| CONTROL | CONTROL_VALUE_STR | CHAR(40) | X(40) | unused by Java |

