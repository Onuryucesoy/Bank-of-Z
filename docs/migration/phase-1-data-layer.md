# Phase 1: data layer

Phase 1 adds a relational target schema and a Java model, without changing any running code:

| Path | Content |
|---|---|
| `src/migration/db2/V001__target_schema.sql` | `CUSTOMER`, `ACCOUNT`, `BANK_TRANSACTION`, `BANK_COUNTER` |
| `src/migration/db2/V002__ims_migration.sql` | CICS copy + IMS segment → table `INSERT … SELECT` templates, pre/post checks |
| `src/migration/db2/V003__sequences.sql` | `CUSTOMER_NUMBER_SEQ`, `ACCOUNT_NUMBER_SEQ`, `SEED_BANK_SEQUENCES` |
| `src/migration/java` | Standalone Gradle (Java 21) project, package `com.bankofz.migration.model` |

The `V<n>__<name>.sql` names follow the Flyway convention, so the scripts can be applied in
order by Flyway or by hand (SPUFI/DSNTEP2, Db2 CLP). They target a **new schema** (for
example `BANKZJ`); the `BANKZ` tables used by the COBOL programs are only read (by V002).

## 1. Build and test

```bash
cd src/migration/java
gradle build          # compiles with -Xlint:all -Werror and runs the JUnit 5 tests
```

The tests read the copybooks and SQL scripts straight from the repository:

| Test | Checks |
|---|---|
| `CopybookConformanceTest` | Every `@Pic` record component against its copybook item: PIC shape (digits, scale, sign), `USAGE`, `REDEFINES` group (DAY/MONTH/YEAR order), Java type (`BigDecimal` for `V`/`COMP-3`, `LocalDate` for date groups); enum literals against the 88-levels; IBGHIST = IBSHIST |
| `Db2SchemaTest` | Runs V001 + V003 on H2 (DB2 mode); `CUSTOMER`/`ACCOUNT` columns equal the `EXEC SQL DECLARE` in `CUSTDB2.cpy`/`ACCDB2.cpy` (name, order, type, length, scale, nullability); PKs, FK, CHECK; sequence seeding, max values, `NO CYCLE` |
| `DtoValidationTest` | Scale normalisation, no rounding, PIC overflow, unsigned PICs, key padding, text lengths |
| `CobolDatesTest` | DDMMYYYY vs YYYYMMDD, zero dates, invalid dates, IMS timestamps |
| `PicClauseTest` | PIC parser, storage sizes (`COMP-3` 15 digits = 8 bytes, as in the DBDs) |

H2 is a syntax and semantics check, not Db2. The SQL PL procedure in V003 and the V002
templates are not executed by the tests; they need a Db2 instance.

## 2. Schema decisions (V001)

### 2.1 CUSTOMER and ACCOUNT

- Column names, order, types and nullability are copied from the `EXEC SQL DECLARE` in
  `CUSTDB2.cpy` and `ACCDB2.cpy`, and `Db2SchemaTest` fails if they ever differ. Keeping the
  COBOL names lets the COBOL programs and the Java services run against the same tables during
  a strangler cut-over (bind the COBOL packages to the new schema, or use aliases).
- Primary keys `(CUSTOMER_SORTCODE, CUSTOMER_NUMBER)` and `(ACCOUNT_SORTCODE, ACCOUNT_NUMBER)`.
  The deployed DDL only had unique indexes (`CUSTINDX`, `ACCTINDX`); the same columns become
  PKs here, each with its enforcing unique index (needed on Db2 for z/OS).
- Secondary indexes mirror the deployed ones the programs depend on: customer name search
  (`CUSTNAME`) and accounts by customer (`ACCTCUST`).
- No FK from `ACCOUNT` to `CUSTOMER`: COBOL never relied on one (DELCUS deletes accounts itself)
  and `ACCOUNT_CUSTOMER_NUMBER` is nullable in the declaration. Adding it later is a behaviour
  change to be agreed, not a migration detail.
- Keys stay `CHAR`: `CUSTOMER_NUMBER CHAR(10)` holds the zero-padded `PIC 9(10)`; the Java
  records keep them as zero-padded `String`s for byte-for-byte round trips.
- Dates follow the declarations: customer dates are `INTEGER` `YYYYMMDD`, account dates are
  `DATE`. They are not "improved" to `DATE` because INQCUST/CRECUST compute them arithmetically.

### 2.2 Precision drift (needs a decision before data is copied)

| Column | `ACCDB2.cpy` (used by V001) | Deployed `Db2-create.j2` | `ACCOUNT.cpy` record |
|---|---|---|---|
| `ACCOUNT_INTEREST_RATE` | `DECIMAL(4,2)` (max 99.99) | `DECIMAL(6,2)` | `9(4)V99` (max 9999.99) |
| `ACCOUNT_AVAILABLE_BALANCE` / `ACCOUNT_ACTUAL_BALANCE` | `DECIMAL(10,2)` (max 99,999,999.99) | `DECIMAL(12,2)` | `S9(10)V99` |

V001 follows the copybook declaration, as specified. V002 contains a pre-flight query that lists
any row that would overflow. The Java `AccountRecord` follows the **record layout**
(`S9(10)V99`), which is the wider of the two, so the DTO can carry every value the COBOL can.
If the deployed precision is the one to keep, change V001 to `DECIMAL(6,2)`/`DECIMAL(12,2)` and
`ACCDB2.cpy` together, and the schema test keeps them aligned.

### 2.3 BANK_TRANSACTION

Models the IMS HISTORY segment (DBD `HISTORY.asm`, 56 bytes; Java/zosAsset view `IBGHIST.cpy`,
identical to `IBSHIST.cpy`; the `IBTRAN.cpy` balance uses the same `S9(13)V9(2) COMP-3` picture):

| IMS field | Copybook PIC | Column |
|---|---|---|
| `TXID` | `S9(18) COMP-5` | `TRANSACTION_ID BIGINT` PK (kept as is) |
| `TIME` / `TIMESTMP` | `X(23)` | `TRANSACTION_TIMESTAMP TIMESTAMP` |
| `TRANSTYP` | `X(1)` | `TRANSACTION_TYPE CHAR(1)`, CHECK `d D w W t` |
| `AMOUNT` | `S9(13)V9(2) COMP-3` | `AMOUNT DECIMAL(15,2)` |
| `REFTXID` | `S9(18) COMP-5` | `REFERENCE_TRANSACTION_ID BIGINT`, default 0 = none |
| `ACCID` | `S9(18) COMP-5` | `SOURCE_ACCID BIGINT` + FK `(ACCOUNT_SORTCODE, ACCOUNT_NUMBER)` → `ACCOUNT` |
| (Db2 `IMSBANK.HISTORY.BALANCE`) | — | `BALANCE_AFTER DECIMAL(15,2)` nullable |

- The FK uses the account's composite key, so IMS accounts must exist in `ACCOUNT` first (V002).
- `TRANSACTION_TYPE` keeps the byte as IBTRAN stored it (it accepts upper and lower case) instead
  of normalising, so history compares 1:1 with IMS.
- `TXID` is `ACCID * 10^10 + n` (IBTRAN), already globally unique, so no new key is needed.
- `REFTXID` is not an FK: the load data uses 0 for "none" and transfers can reference the other
  side before it is loaded.
- CICS `PROCTRAN` is a different, CICS-only audit trail and stays out of Phase 1.

### 2.4 BANK_COUNTER

`COUNTER_NAME VARCHAR(32)` PK, `COUNTER_VALUE BIGINT`. Names are the legacy `CONTROL_NAME`
values, so V002 copies them unchanged:

| Counter name | Meaning | Today written by |
|---|---|---|
| `'BANKZCUST' || sortcode || '  '` (17 bytes, two trailing blanks) | last customer number | CRECUST, BANKDATA |
| `sortcode || '-ACCOUNT-LAST'` | last account number | CREACC, BANKDATA |
| `sortcode || '-ACCOUNT-COUNT'` | number of accounts | CREACC, BANKDATA |
| `'IMS-LASTTXID-' || ACCID` | IMS `ACCOUNT.LASTTXID` (per account) | IBTRAN |

## 3. Named-counter replacement (V003)

What exists today: `NEWCUSNO.cpy`/`NEWACCNO.cpy` define a counter interface (function `G`
GETNEW, `R` ROLLBACK, `C` CURRENT; number; success flag; fail code), but **no program copies
them and no program uses `EXEC CICS GET COUNTER`**. CRECUST and CREACC do: `ENQ` on
`BANKZCUST`/`BANKZACCT` + sortcode → `SELECT` the CONTROL row → +1 → `UPDATE` → insert the
customer/account → `DEQ`. A failure abends and backs the unit of work out.

Replacement:

| Function | Java / SQL | Notes |
|---|---|---|
| `G` GETNEW | `VALUES NEXT VALUE FOR CUSTOMER_NUMBER_SEQ` (or `ACCOUNT_NUMBER_SEQ`), then `UPDATE BANK_COUNTER … WHERE COUNTER_VALUE < :new` in the same unit of work | No ENQ needed: sequences are atomic across threads and data-sharing members (`ORDER`) |
| `C` CURRENT | `SELECT COUNTER_VALUE FROM BANK_COUNTER` | High-water mark, also used for INQCUST's "last customer" (`9999999999`) |
| `R` ROLLBACK | No-op | A drawn number is never returned; a failed create leaves a gap (same as a real CICS named counter). The current CONTROL-row scheme can back out instead; any test that expects gap-free numbers after a failed create must be relaxed |

Sequence options: `NO CYCLE` (numbers never reused), `MAXVALUE` = PIC width (`99999999` for
accounts; `9999999998` for customers because INQCUST reserves `9999999999`), `NO CACHE` (no
jumps after a restart, like the CONTROL row; `CACHE n` is a later tuning step), `ORDER`.

Seeding: Db2 does not allow `START WITH (subquery)`, so the sequences start at 1 and
`CALL SEED_BANK_SEQUENCES('987654')` restarts them at `BANK_COUNTER + 1` after V002 has copied
`CONTROL`. Run it once at cut-over, while the COBOL writers are quiesced; if COBOL keeps creating
customers after that, either rerun it immediately before Java takes over or point COBOL at the
same sequence (a COBOL change, not part of this phase).

The Java side of the interface is `CounterFunction` (G/R/C) and
`NewCustomerNumberRequest`/`Response`, `NewAccountNumberRequest`/`Response`.

## 4. IMS segment → table mapping (V002)

Source: the IMS DBDs in `src/base/ims/DBD` and the load files in `src/base/ims/LoadData`, which
are the format the `LOAD*` programs read (`CUSTOMER.data`, `ACCOUNT.data`, `CUSTACCS.data`,
`HISTORY.data`). An IMS unload is converted to the same CSV, loaded into `IMS_STG_*` staging
tables, checked, then copied with `INSERT … SELECT`.

### 4.1 Key stability

| IMS key | Target key | Example |
|---|---|---|
| `CUSTID` (INT) | `CUSTOMER_SORTCODE = ${ims_sortcode}`, `CUSTOMER_NUMBER = DIGITS(CUSTID)` | `15` → `'0000000015'` (API id `000000015`) |
| `ACCID` (BIGINT) | `ACCOUNT_SORTCODE = ${ims_sortcode}`, `ACCOUNT_NUMBER = RIGHT(DIGITS(ACCID), 8)`; raw value in `BANK_TRANSACTION.SOURCE_ACCID` | `101` → `'00000101'` |
| `TXID` (BIGINT) | `BANK_TRANSACTION.TRANSACTION_ID` unchanged | `1010000000001` |

`${ims_sortcode}` must not be a CICS sort code: CICS customer numbers and IMS `CUSTID`s
overlap. Pre-flight queries reject keys that do not fit (`CUSTID > 999999999`,
`ACCID > 99999999`), collisions, truncation, orphan history and accounts with two owners.

### 4.2 Field mapping

| IMS segment.field | Target column | Rule |
|---|---|---|
| CUSTOMER.LASTNAME / FIRSTNAME | `CUSTOMER_LAST_NAME` / `CUSTOMER_FIRST_NAME` | 50 → 50 |
| CUSTOMER.ADDRESS (80) | `CUSTOMER_ADDR_LINE1` + `CUSTOMER_ADDR_LINE2` | bytes 1–50 / 51–80 |
| CUSTOMER.CITY (25) + STATE (2) | `CUSTOMER_CITY` | `'CITY, ST'` (no state column) |
| CUSTOMER.ZIPCODE (15) | `CUSTOMER_POSTCODE` (10) | pre-flight rejects > 10 |
| CUSTOMER.PHONE (12) | `CUSTOMER_PHONE` (20) | as is |
| — | `CUSTOMER_COUNTRY` | `'USA'` |
| CUSTOMER.STATUS | — | login flag, **not** customer status; all rows `ACTIVE` |
| CUSTOMER.LASTLOGIN | `CUSTOMER_CREATED_DATE` | `YYYYMMDD` of last login (best available), else 0 |
| CUSTOMER.PASSWORD | — | **not migrated** (plaintext); users re-enrol with hashed credentials |
| CUSTOMER.CUSTTYPE | — | only `c` (customer) rows; `t` (teller) are users, not customers |
| ACCOUNT.ACCTYPE | `ACCOUNT_TYPE` | `c` → `CURRENT`, `s` → `SAVING` (CICS names from BANKDATA) |
| ACCOUNT.BALANCE | `ACCOUNT_AVAILABLE_BALANCE`, `ACCOUNT_ACTUAL_BALANCE` | single IMS balance → both |
| ACCOUNT.LASTTXID | `BANK_COUNTER 'IMS-LASTTXID-<ACCID>'` | keeps IBTRAN's TXID sequence |
| CUSTACCS (CUSTID, ACCID) | `ACCOUNT_CUSTOMER_NUMBER` | owner of the account |
| HISTORY.* | `BANK_TRANSACTION.*` | see §2.3; `BALANCE_AFTER` from `IMSBANK.HISTORY` when present |
| (no IMS source) | DOB, title, credit score, CS review date, interest rate, overdraft | 0 / blank, **never NULL**: the COBOL host variables have no null indicators |
| (no IMS source) | `ACCOUNT_OPENED`, `ACCOUNT_LAST_STATEMENT`, `ACCOUNT_NEXT_STATEMENT` | date of first history row (else current date); next = +1 month |

Data observation: in the sample `HISTORY.data` the first deposit of account 101 is
`88300000.00` while `ACCOUNT.data` has a balance of `8830.00`, and such amounts would not fit
`DECIMAL(10,2)` balances. Check this against production data before relying on the sample.

## 5. DTOs and the OpenAPI schemas

The DTOs are derived from the copybooks (the system of record), not from `openapi.yaml`.
OpenAPI is the contract they are mapped *to* in a later phase:

```
copybook (PIC, 88, REDEFINES)  ──@Pic + tests──▶  Java record  ──mapper (Phase 2)──▶  OpenAPI schema
CUSTDB2/ACCDB2 DECLARE         ──schema test──▶  V001 DDL
```

| Record component | Copybook | OpenAPI | Difference to handle in the mapper |
|---|---|---|---|
| `CustomerRecord.sortCode` + `customerNumber` | `CUSTOMER-SORTCODE 9(6)`, `CUSTOMER-NUMBER 9(10)` | path `customerId` | API ids are shorter strings (`0000001`) |
| `title`, `firstName`, `lastName`, `phone` | `X(10)`, `X(50)`, `X(50)`, `X(20)` | same names; `maxLength` 50/50/20 | API `title` is an enum, copybook is free text |
| `dateOfBirth` | `CUSTOMER-DOB` DD/MM/YYYY group, Db2 INTEGER YYYYMMDD | `format: date` | none (`LocalDate`) |
| `address` (`Address`) | `CUSTOMER-ADDRESS` group | `Address` (addressLine1/2, city, postalCode, country) | `postcode` ↔ `postalCode` |
| `status` (`CustomerStatus`) | 88-levels ACTIVE/INACTIVE/SUSPENDED | `customerStatus` enum, same values | none |
| `createdDate` | DD/MM/YYYY group | `format: date-time` | API has a time part; COBOL has only a date |
| `creditScore` | `999` | integer | none |
| `AccountRecord.interestRate`, balances | `9(4)V99`, `S9(10)V99` | `number` (`double`) | **must not** go through `double`; serialise `BigDecimal` |
| `AccountRecord.accountType` | `X(8)` (`CURRENT`, `SAVING`, `ISA`, `LOAN`, `MORTGAGE`) | enum `CURRENT`, `SAVINGS`, `CREDIT_CARD`, `LOAN` | value sets differ (`SAVING` vs `SAVINGS`, `ISA`/`MORTGAGE`) |
| `AccountRecord.opened` | `ACCOUNT-OPENED 9(8)` REDEFINES DDMMYYYY | `openingDate` `format: date` | none |
| `BankTransactionRecord.amount` | `S9(13)V9(2) COMP-3` | `Transaction.amount` (`double`) | as for balances |

Why derived, not copied: each component carries `@Pic(copybook, field, value, usage,
redefinedBy)`, the record's compact constructor enforces that PIC at runtime, and
`CopybookConformanceTest` re-parses the copybook on every build. A changed copybook breaks the
build instead of silently diverging; a field cannot be added to a DTO without naming the
copybook item it comes from.

## 6. Dates

| Representation | Where | Example (23 July 2003) | Converter |
|---|---|---|---|
| Group DD/MM/YYYY (no REDEFINES) | CUSTOMER.cpy, customer commareas | `23072003` | `CobolDates.fromDdMmYyyy(String)` |
| `PIC 9(8)` REDEFINES DD/MM/YYYY | ACCOUNT.cpy, account commareas | `23072003` | `CobolDates.fromDdMmYyyy(int)` |
| Db2 `INTEGER` YYYYMMDD | `CUSTOMER_*_DATE` columns | `20030723` | `CobolDates.fromDb2Integer` |
| Db2 `DATE` via `X(10)` `DD.MM.YYYY` | CREACC/UPDACC host variables | `23.07.2003` | `CobolDates.fromDottedDdMmYyyy` |
| IMS `X(23)` timestamp | HISTORY.TIME, CUSTOMER.LASTLOGIN | `2003-07-23 17:56:00.64` | `CobolDates.fromImsTimestamp` |

The record-layout groups are DDMMYYYY, but the Db2 integer is YYYYMMDD. Mixing the two
up is the most likely date bug in the port, so the converters are kept separate (the test
`theTwoIntegerFormsAreNotInterchangeable` checks this). Zero is "no date" → `null`.
Invalid calendar dates throw instead of being adjusted.

## 7. Out of scope for Phase 1

- Porting any program, or changing COBOL/PL/I, zosAssets, `openapi.yaml` or existing builds.
- `PROCTRAN`, `TSTAT` and the commarea DTOs marked P2+ in `copybook-inventory.md`.
- Executing V002/V003 against a real Db2 (needs a z/OS or Db2 LUW environment).
