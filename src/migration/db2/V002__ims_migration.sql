-- =====================================================================
-- V002__ims_migration.sql  --  Bank of Z Java migration, Phase 1
--
-- TEMPLATE. Copies the existing data into the V001 target tables:
--   Part A  CICS Db2 tables (BANKZ.CUSTOMER / ACCOUNT / CONTROL)  -> 1:1
--   Part B  IMS DB segments  (CUSTOMER / ACCOUNT / CUSTACCS / HISTORY)
--           -> staging tables -> CUSTOMER / ACCOUNT / BANK_TRANSACTION
--
-- Placeholders (Flyway syntax, substitute before running by hand):
--   ${legacy_schema}  schema of the running COBOL tables, normally BANKZ
--   ${ims_schema}     schema of the Db2 IMS history copy, normally IMSBANK
--   ${ims_sortcode}   6-digit sort code assigned to IMS-origin rows. It
--                     MUST differ from every CICS sort code (987654 in
--                     SORTCODE.cpy) because IMS CUSTID 1..99 and CICS
--                     customer numbers 1..n overlap.
--
-- KEY STABILITY RULES (the whole point of this script):
--   * CICS rows keep (SORTCODE, NUMBER) byte-for-byte.
--   * IMS CUSTID  (INTEGER)  -> CUSTOMER_NUMBER = 10-digit zero-padded
--     CUSTID, e.g. 15 -> '0000000015'. The API id '000000015' (IBGCUDAT
--     IN-CUSTID PIC X(9)) is the same number with 9 digits.
--   * IMS ACCID   (BIGINT)   -> ACCOUNT_NUMBER  = 8-digit zero-padded ACCID,
--     e.g. 101 -> '00000101'. SOURCE_ACCID keeps the raw value.
--   * IMS TXID    (BIGINT)   -> BANK_TRANSACTION.TRANSACTION_ID unchanged.
--   Pre-flight checks (Part B.2) must return zero rows before Part B.3.
--
-- IMS source layouts (src/base/ims/DBD/*.asm, load format = the CSV files
-- in src/base/ims/LoadData, the same layout the LOAD* COBOL programs
-- UNSTRING; an IMS unload (DFSURGU0 or a JDBC SELECT through the IMS
-- Universal driver) should be converted to that CSV form):
--   CUSTOMER.data  CUSTID,"LASTNAME","FIRSTNAME","ADDRESS","CITY","STATE",
--                  "ZIPCODE","PHONE","STATUS","PASSWORD","CUSTTYPE","LASTLOGIN"
--   ACCOUNT.data   ACCID,"ACCTYPE",BALANCE,LASTTXID
--   CUSTACCS.data  ACCID,CUSTID,ACCNUM
--   HISTORY.data   TXID,"TIMESTAMP","TRANSTYP",AMOUNT,REFTXID,ACCID
-- =====================================================================

-- =====================================================================
-- Part A  --  CICS Db2 -> target (same column names, so straight copy)
-- =====================================================================

-- A.1 Precision pre-flight. ACCDB2.cpy (and therefore V001) declares
-- DECIMAL(4,2)/DECIMAL(10,2); the deployed BANKZ.ACCOUNT is DECIMAL(6,2)/
-- DECIMAL(12,2). Any row returned here would overflow (SQLCODE -406).
--   SELECT ACCOUNT_SORTCODE, ACCOUNT_NUMBER
--     FROM ${legacy_schema}.ACCOUNT
--    WHERE ABS(ACCOUNT_INTEREST_RATE)     >= 100
--       OR ABS(ACCOUNT_AVAILABLE_BALANCE) >= 100000000
--       OR ABS(ACCOUNT_ACTUAL_BALANCE)    >= 100000000;

INSERT INTO CUSTOMER
     ( CUSTOMER_EYECATCHER, CUSTOMER_SORTCODE, CUSTOMER_NUMBER,
       CUSTOMER_TITLE, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME,
       CUSTOMER_DATE_OF_BIRTH, CUSTOMER_PHONE,
       CUSTOMER_ADDR_LINE1, CUSTOMER_ADDR_LINE2, CUSTOMER_CITY,
       CUSTOMER_POSTCODE, CUSTOMER_COUNTRY, CUSTOMER_STATUS,
       CUSTOMER_CREATED_DATE, CUSTOMER_CREDIT_SCORE, CUSTOMER_CS_REVIEW_DATE )
SELECT CUSTOMER_EYECATCHER, CUSTOMER_SORTCODE, CUSTOMER_NUMBER,
       CUSTOMER_TITLE, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME,
       CUSTOMER_DATE_OF_BIRTH, CUSTOMER_PHONE,
       CUSTOMER_ADDR_LINE1, CUSTOMER_ADDR_LINE2, CUSTOMER_CITY,
       CUSTOMER_POSTCODE, CUSTOMER_COUNTRY, CUSTOMER_STATUS,
       CUSTOMER_CREATED_DATE, CUSTOMER_CREDIT_SCORE, CUSTOMER_CS_REVIEW_DATE
  FROM ${legacy_schema}.CUSTOMER;

INSERT INTO ACCOUNT
     ( ACCOUNT_EYECATCHER, ACCOUNT_CUSTOMER_NUMBER, ACCOUNT_SORTCODE,
       ACCOUNT_NUMBER, ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE, ACCOUNT_OPENED,
       ACCOUNT_OVERDRAFT_LIMIT, ACCOUNT_LAST_STATEMENT, ACCOUNT_NEXT_STATEMENT,
       ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE )
SELECT ACCOUNT_EYECATCHER, ACCOUNT_CUSTOMER_NUMBER, ACCOUNT_SORTCODE,
       ACCOUNT_NUMBER, ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE, ACCOUNT_OPENED,
       ACCOUNT_OVERDRAFT_LIMIT, ACCOUNT_LAST_STATEMENT, ACCOUNT_NEXT_STATEMENT,
       ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE
  FROM ${legacy_schema}.ACCOUNT;

-- A.2 Counters. CONTROL_NAME is CHAR(32); RTRIM would break the customer
-- key 'BANKZCUST987654  ' (two trailing blanks are part of the name), so
-- only blanks beyond the legacy 17/19/20-character names are trimmed.
INSERT INTO BANK_COUNTER (COUNTER_NAME, COUNTER_VALUE)
SELECT CASE WHEN CONTROL_NAME LIKE 'BANKZCUST%'
            THEN SUBSTR(CONTROL_NAME, 1, 17)
            ELSE RTRIM(CONTROL_NAME) END,
       BIGINT(CONTROL_VALUE_NUM)
  FROM ${legacy_schema}.CONTROL
 WHERE CONTROL_NAME LIKE 'BANKZCUST%'
    OR CONTROL_NAME LIKE '%-ACCOUNT-LAST%'
    OR CONTROL_NAME LIKE '%-ACCOUNT-COUNT%';

-- =====================================================================
-- Part B  --  IMS DB -> target
-- =====================================================================

-- B.1 Staging tables, one per segment, columns = DBD FIELD names.
-- CUSTOMER.PASSWORD is deliberately NOT staged: it is plaintext in IMS and
-- must be re-enrolled/hashed by the Java auth service, never copied.
CREATE TABLE IMS_STG_CUSTOMER (
    CUSTID     INTEGER      NOT NULL,   -- FIELD CUSTID   F, 4 bytes, key
    LASTNAME   VARCHAR(50)  NOT NULL,   -- FIELD LASTNAME C, 50
    FIRSTNAME  VARCHAR(50)  NOT NULL,   -- FIELD FIRSTNM  C, 50
    ADDRESS    VARCHAR(80)  NOT NULL,   -- FIELD ADDRESS  C, 80
    CITY       VARCHAR(25)  NOT NULL,   -- FIELD CITY     C, 25
    STATE      CHAR(2)      NOT NULL,   -- FIELD STATE    C, 2
    ZIPCODE    VARCHAR(15)  NOT NULL,   -- FIELD ZIPCODE  C, 15
    PHONE      VARCHAR(12)  NOT NULL,   -- FIELD PHONE    C, 12
    STATUS     CHAR(1)      NOT NULL,   -- FIELD STATUS   login flag 0/1
    CUSTTYPE   CHAR(1)      NOT NULL,   -- FIELD CUSTTYPE c=customer t=teller
    LASTLOGIN  TIMESTAMP,               -- FIELD LASTLOGIN 23-byte timestamp
    PRIMARY KEY (CUSTID)
);

CREATE TABLE IMS_STG_ACCOUNT (
    ACCID      BIGINT        NOT NULL,  -- FIELD ACCID   F, 8 bytes, key
    ACCTYPE    CHAR(1)       NOT NULL,  -- FIELD ACCTYPE s=savings c=checking
    BALANCE    DECIMAL(15,2) NOT NULL,  -- FIELD BALANCE P, 8 bytes packed
    LASTTXID   BIGINT        NOT NULL,  -- FIELD LASTTXID per-account seq
    PRIMARY KEY (ACCID)
);

CREATE TABLE IMS_STG_CUSTACCS (
    ACCID      BIGINT        NOT NULL,
    CUSTID     INTEGER       NOT NULL,
    ACCNUM     INTEGER       NOT NULL,  -- ordinal of the account (1..6)
    PRIMARY KEY (CUSTID, ACCID)
);

CREATE TABLE IMS_STG_HISTORY (
    TXID       BIGINT        NOT NULL,
    TIMESTMP   TIMESTAMP     NOT NULL,
    TRANSTYP   CHAR(1)       NOT NULL,
    AMOUNT     DECIMAL(15,2) NOT NULL,
    REFTXID    BIGINT        NOT NULL,
    ACCID      BIGINT        NOT NULL,
    PRIMARY KEY (TXID)
);

-- Load the staging tables here (Db2 LOAD / IMPORT ... OF DEL from the CSV
-- unloads, or the Java loader in a later phase). Example (Db2 LUW CLP):
--   IMPORT FROM ACCOUNT.data OF DEL INSERT INTO IMS_STG_ACCOUNT;

-- B.2 Pre-flight checks: every query must return no rows.
-- Keys that do not fit the CICS key widths:
--   SELECT CUSTID FROM IMS_STG_CUSTOMER WHERE CUSTID < 0 OR CUSTID > 999999999;
--   SELECT ACCID  FROM IMS_STG_ACCOUNT  WHERE ACCID  < 0 OR ACCID  > 99999999;
-- Collisions with CICS rows already copied in Part A:
--   SELECT 1 FROM CUSTOMER WHERE CUSTOMER_SORTCODE = '${ims_sortcode}';
--   SELECT 1 FROM ACCOUNT  WHERE ACCOUNT_SORTCODE  = '${ims_sortcode}';
-- Values that would be truncated by the CICS-shaped columns:
--   SELECT CUSTID FROM IMS_STG_CUSTOMER WHERE LENGTH(RTRIM(ZIPCODE)) > 10;
--   SELECT ACCID  FROM IMS_STG_ACCOUNT  WHERE ABS(BALANCE) >= 100000000;
--   SELECT TXID   FROM IMS_STG_HISTORY  WHERE ACCID NOT IN
--                                         (SELECT ACCID FROM IMS_STG_ACCOUNT);
-- Accounts owned by more than one customer (ACCOUNT has a single owner):
--   SELECT ACCID FROM IMS_STG_CUSTACCS GROUP BY ACCID HAVING COUNT(*) > 1;

-- B.3 IMS CUSTOMER segment -> CUSTOMER
--   CUSTID      -> CUSTOMER_NUMBER (DIGITS(INTEGER) = 10 digits, zero-padded)
--   ADDRESS 80  -> ADDR_LINE1 (bytes 1-50) + ADDR_LINE2 (bytes 51-80)
--   CITY, STATE -> CUSTOMER_CITY 'CITY, ST' (no state column in CUSTDB2)
--   ZIPCODE     -> CUSTOMER_POSTCODE ; PHONE -> CUSTOMER_PHONE
--   COUNTRY     -> 'USA' (IMS data set is US-only, STATE is a US state)
--   STATUS      -> NOT mapped: IMS STATUS is a login flag, not the
--                  ACTIVE/INACTIVE/SUSPENDED status; all rows are ACTIVE.
--   Dates with no IMS source are 0 (never NULL: the COBOL host variables
--   have no null indicators, a NULL would give SQLCODE -305 in INQCUST).
INSERT INTO CUSTOMER
     ( CUSTOMER_EYECATCHER, CUSTOMER_SORTCODE, CUSTOMER_NUMBER,
       CUSTOMER_TITLE, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME,
       CUSTOMER_DATE_OF_BIRTH, CUSTOMER_PHONE,
       CUSTOMER_ADDR_LINE1, CUSTOMER_ADDR_LINE2, CUSTOMER_CITY,
       CUSTOMER_POSTCODE, CUSTOMER_COUNTRY, CUSTOMER_STATUS,
       CUSTOMER_CREATED_DATE, CUSTOMER_CREDIT_SCORE, CUSTOMER_CS_REVIEW_DATE )
SELECT 'CUST',
       '${ims_sortcode}',
       DIGITS(C.CUSTID),
       ' ',
       C.FIRSTNAME,
       C.LASTNAME,
       0,
       C.PHONE,
       SUBSTR(CHAR(C.ADDRESS, 80), 1, 50),
       SUBSTR(CHAR(C.ADDRESS, 80), 51, 30),
       RTRIM(C.CITY) || ', ' || C.STATE,
       RTRIM(C.ZIPCODE),
       'USA',
       'ACTIVE',
       COALESCE(YEAR(C.LASTLOGIN) * 10000 + MONTH(C.LASTLOGIN) * 100
                + DAY(C.LASTLOGIN), 0),
       0,
       0
  FROM IMS_STG_CUSTOMER C
 WHERE C.CUSTTYPE = 'c';        -- tellers ('t') are users, not customers

-- B.4 IMS ACCOUNT + CUSTACCS segments -> ACCOUNT
--   ACCID    -> ACCOUNT_NUMBER  RIGHT(DIGITS(ACCID), 8)
--   CUSTACCS -> ACCOUNT_CUSTOMER_NUMBER (owner, DIGITS(CUSTID))
--   ACCTYPE  -> 'c' -> 'CURRENT ', 's' -> 'SAVING  ' (BANKDATA type names)
--   BALANCE  -> both AVAILABLE and ACTUAL (IMS has a single balance)
--   No IMS source for rate/overdraft/statement dates: 0 and dates derived
--   from the first HISTORY row (account opening), never NULL (see B.3).
INSERT INTO ACCOUNT
     ( ACCOUNT_EYECATCHER, ACCOUNT_CUSTOMER_NUMBER, ACCOUNT_SORTCODE,
       ACCOUNT_NUMBER, ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE, ACCOUNT_OPENED,
       ACCOUNT_OVERDRAFT_LIMIT, ACCOUNT_LAST_STATEMENT, ACCOUNT_NEXT_STATEMENT,
       ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE )
SELECT 'ACCT',
       DIGITS(CA.CUSTID),
       '${ims_sortcode}',
       RIGHT(DIGITS(A.ACCID), 8),
       CASE A.ACCTYPE WHEN 'c' THEN 'CURRENT'
                      WHEN 's' THEN 'SAVING'
                      ELSE A.ACCTYPE END,
       0.00,
       COALESCE(DATE(H.FIRST_TS), CURRENT DATE),
       0,
       COALESCE(DATE(H.FIRST_TS), CURRENT DATE),
       COALESCE(DATE(H.FIRST_TS), CURRENT DATE) + 1 MONTH,
       A.BALANCE,
       A.BALANCE
  FROM IMS_STG_ACCOUNT A
  JOIN IMS_STG_CUSTACCS CA ON CA.ACCID = A.ACCID
  LEFT JOIN (SELECT ACCID, MIN(TIMESTMP) AS FIRST_TS
               FROM IMS_STG_HISTORY GROUP BY ACCID) H
         ON H.ACCID = A.ACCID;

-- B.5 IMS ACCOUNT.LASTTXID -> BANK_COUNTER (IBTRAN computes the next TXID
-- as ACCID * 10^10 + LASTTXID + 1; the Java port reads/updates this row).
INSERT INTO BANK_COUNTER (COUNTER_NAME, COUNTER_VALUE)
SELECT 'IMS-LASTTXID-' || RTRIM(CHAR(A.ACCID)), A.LASTTXID
  FROM IMS_STG_ACCOUNT A;

-- B.6 IMS HISTORY segment -> BANK_TRANSACTION (TXID preserved).
-- BALANCE_AFTER comes from the Db2 copy written by nazare.jmp InsertHist
-- (${ims_schema}.HISTORY.BALANCE) when present; IMS itself has no balance.
INSERT INTO BANK_TRANSACTION
     ( TRANSACTION_ID, ACCOUNT_SORTCODE, ACCOUNT_NUMBER, SOURCE_ACCID,
       TRANSACTION_TIMESTAMP, TRANSACTION_TYPE, AMOUNT,
       REFERENCE_TRANSACTION_ID, BALANCE_AFTER )
SELECT H.TXID,
       '${ims_sortcode}',
       RIGHT(DIGITS(H.ACCID), 8),
       H.ACCID,
       H.TIMESTMP,
       H.TRANSTYP,
       H.AMOUNT,
       H.REFTXID,
       D.BALANCE
  FROM IMS_STG_HISTORY H
  LEFT JOIN ${ims_schema}.HISTORY D ON D.TXID = H.TXID;

-- Rows that exist only in the Db2 copy (IMS insert failed after the JNI
-- call in IBTRAN) are reported, not migrated:
--   SELECT TXID FROM ${ims_schema}.HISTORY
--    WHERE TXID NOT IN (SELECT TXID FROM IMS_STG_HISTORY);

-- B.7 Post-checks (counts must match the IMS unload):
--   SELECT COUNT(*) FROM CUSTOMER WHERE CUSTOMER_SORTCODE = '${ims_sortcode}';
--   SELECT COUNT(*) FROM ACCOUNT  WHERE ACCOUNT_SORTCODE  = '${ims_sortcode}';
--   SELECT COUNT(*) FROM BANK_TRANSACTION
--    WHERE ACCOUNT_SORTCODE = '${ims_sortcode}';
-- Staging tables are dropped after sign-off:
--   DROP TABLE IMS_STG_HISTORY; DROP TABLE IMS_STG_CUSTACCS;
--   DROP TABLE IMS_STG_ACCOUNT; DROP TABLE IMS_STG_CUSTOMER;
