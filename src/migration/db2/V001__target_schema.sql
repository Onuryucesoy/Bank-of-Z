-- =====================================================================
-- V001__target_schema.sql  --  Bank of Z Java migration, Phase 1
--
-- Target relational schema for the Java data layer. Additive only: this
-- script does NOT replace .setup/jcl/cics/Db2-create.j2 or
-- .setup/jcl/ims/Db2-create.j2, and it must be run into a NEW schema
-- (set CURRENT SCHEMA / Flyway defaultSchema, e.g. BANKZJ) so the
-- existing BANKZ tables used by the COBOL programs stay untouched.
--
-- Canonical sources (column names, types, nullability copied 1:1):
--   CUSTOMER  <- src/base/cics/copy/CUSTDB2.cpy  (EXEC SQL DECLARE CUSTOMER)
--   ACCOUNT   <- src/base/cics/copy/ACCDB2.cpy   (EXEC SQL DECLARE ACCOUNT)
-- New tables (no CICS equivalent):
--   BANK_TRANSACTION <- IMS HISTORY segment (src/base/ims/DBD/HISTORY.asm)
--                       as exposed by src/base/ims/copy/IBGHIST.cpy,
--                       IBSHIST.cpy and src/base/ims/copy/IBTRAN.cpy
--   BANK_COUNTER     <- replaces the CICS "named counter" interfaces
--                       NEWCUSNO.cpy / NEWACCNO.cpy (implemented today as
--                       CICS ENQ + rows in the Db2 CONTROL table)
--
-- KNOWN DRIFT (intentionally NOT "fixed" here, see
-- docs/migration/phase-1-data-layer.md section 2.2):
--   ACCDB2.cpy declares ACCOUNT_INTEREST_RATE DECIMAL(4,2) and both
--   balances DECIMAL(10,2). The deployed DDL in .setup/jcl/cics/Db2-create.j2
--   uses DECIMAL(6,2) / DECIMAL(12,2), and the COBOL record layout
--   ACCOUNT.cpy uses PIC 9(4)V99 / PIC S9(10)V99 (6 and 12 digits).
--   This script follows the copybook DECLARE exactly, as required.
--
-- Primary keys: the copybooks declare no PK; the deployed DDL uses unique
-- indexes CUSTINDX / ACCTINDX on (sortcode, number). They are promoted to
-- PRIMARY KEYs here. On Db2 for z/OS a PK in an explicitly created table
-- space needs an enforcing unique index, so one is created per table.
--
-- Dialect: Db2 for z/OS 12+ / Db2 LUW 11.5. Storage clauses (IN database.
-- tablespace, CCSID, BUFFERPOOL) are environment specific and omitted.
-- =====================================================================

-- ---------------------------------------------------------------------
-- CUSTOMER  (CUSTDB2.cpy, 17 columns, same order)
-- Date columns are INTEGER in YYYYMMDD form (CRECUST computes
-- year*10000 + month*100 + day; INQCUST reverses it). 0 means "no date".
-- ---------------------------------------------------------------------
CREATE TABLE CUSTOMER (
    CUSTOMER_EYECATCHER        CHAR(4),
    CUSTOMER_SORTCODE          CHAR(6)  NOT NULL,
    CUSTOMER_NUMBER            CHAR(10) NOT NULL,
    CUSTOMER_TITLE             CHAR(10),
    CUSTOMER_FIRST_NAME        CHAR(50),
    CUSTOMER_LAST_NAME         CHAR(50),
    CUSTOMER_DATE_OF_BIRTH     INTEGER,
    CUSTOMER_PHONE             CHAR(20),
    CUSTOMER_ADDR_LINE1        CHAR(50),
    CUSTOMER_ADDR_LINE2        CHAR(50),
    CUSTOMER_CITY              CHAR(50),
    CUSTOMER_POSTCODE          CHAR(10),
    CUSTOMER_COUNTRY           CHAR(50),
    CUSTOMER_STATUS            CHAR(10),
    CUSTOMER_CREATED_DATE      INTEGER,
    CUSTOMER_CREDIT_SCORE      SMALLINT,
    CUSTOMER_CS_REVIEW_DATE    INTEGER,
    CONSTRAINT CUSTOMER_PK PRIMARY KEY (CUSTOMER_SORTCODE, CUSTOMER_NUMBER)
);

CREATE UNIQUE INDEX CUSTOMER_PKX
    ON CUSTOMER (CUSTOMER_SORTCODE, CUSTOMER_NUMBER);

-- Mirrors CUSTNAME in the deployed DDL (name search in INQCUST/BNK1DCS).
CREATE INDEX CUSTOMER_NAMEX
    ON CUSTOMER (CUSTOMER_LAST_NAME, CUSTOMER_FIRST_NAME);

-- ---------------------------------------------------------------------
-- ACCOUNT  (ACCDB2.cpy, 12 columns, same order)
-- No FK to CUSTOMER: the COBOL programs never relied on one (DELCUS
-- deletes accounts itself via DELACC) and ACCOUNT_CUSTOMER_NUMBER is
-- nullable in the copybook. Adding it would change failure behaviour.
-- ---------------------------------------------------------------------
CREATE TABLE ACCOUNT (
    ACCOUNT_EYECATCHER         CHAR(4),
    ACCOUNT_CUSTOMER_NUMBER    CHAR(10),
    ACCOUNT_SORTCODE           CHAR(6)  NOT NULL,
    ACCOUNT_NUMBER             CHAR(8)  NOT NULL,
    ACCOUNT_TYPE               CHAR(8),
    ACCOUNT_INTEREST_RATE      DECIMAL(4, 2),
    ACCOUNT_OPENED             DATE,
    ACCOUNT_OVERDRAFT_LIMIT    INTEGER,
    ACCOUNT_LAST_STATEMENT     DATE,
    ACCOUNT_NEXT_STATEMENT     DATE,
    ACCOUNT_AVAILABLE_BALANCE  DECIMAL(10, 2),
    ACCOUNT_ACTUAL_BALANCE     DECIMAL(10, 2),
    CONSTRAINT ACCOUNT_PK PRIMARY KEY (ACCOUNT_SORTCODE, ACCOUNT_NUMBER)
);

CREATE UNIQUE INDEX ACCOUNT_PKX
    ON ACCOUNT (ACCOUNT_SORTCODE, ACCOUNT_NUMBER);

-- Mirrors ACCTCUST in the deployed DDL (INQACCCU: accounts of a customer).
CREATE INDEX ACCOUNT_CUSTX
    ON ACCOUNT (ACCOUNT_SORTCODE, ACCOUNT_CUSTOMER_NUMBER);

-- ---------------------------------------------------------------------
-- BANK_TRANSACTION  (IMS HISTORY segment, 56 bytes)
--   IMS field (DBD / IBGHIST.cpy)          -> column
--   TXID     LONG / S9(18) COMP-5          -> TRANSACTION_ID   (PK, value kept)
--   TIME     23-byte TIMESTAMP / X(23)     -> TRANSACTION_TIMESTAMP
--   TRANSTYP CHAR(1) / X(1)                -> TRANSACTION_TYPE
--   AMOUNT   DECIMAL(15,2)/S9(13)V99 COMP-3-> AMOUNT
--   REFTXID  LONG / S9(18) COMP-5          -> REFERENCE_TRANSACTION_ID (0 = none)
--   ACCID    LONG / S9(18) COMP-5          -> SOURCE_ACCID + FK pair below
--   (IMSBANK.HISTORY.BALANCE, written by nazare.jmp InsertHist)
--                                          -> BALANCE_AFTER
-- IMS TXIDs are ACCID * 10^10 + per-account sequence (IBTRAN.cbl), so they
-- are globally unique and are preserved verbatim as the primary key.
-- TRANSACTION_TYPE: IBTRAN accepts d/D (deposit) and w/W (withdrawal) and
-- stores the byte as received; TTYPE lookup DBD also defines t (transfer).
-- ---------------------------------------------------------------------
CREATE TABLE BANK_TRANSACTION (
    TRANSACTION_ID             BIGINT        NOT NULL,
    ACCOUNT_SORTCODE           CHAR(6)       NOT NULL,
    ACCOUNT_NUMBER             CHAR(8)       NOT NULL,
    SOURCE_ACCID               BIGINT        NOT NULL,
    TRANSACTION_TIMESTAMP      TIMESTAMP     NOT NULL,
    TRANSACTION_TYPE           CHAR(1)       NOT NULL,
    AMOUNT                     DECIMAL(15, 2) NOT NULL,
    REFERENCE_TRANSACTION_ID   BIGINT        DEFAULT 0 NOT NULL,
    BALANCE_AFTER              DECIMAL(15, 2),
    CONSTRAINT BANK_TRANSACTION_PK PRIMARY KEY (TRANSACTION_ID),
    CONSTRAINT BANK_TRANSACTION_ACCOUNT_FK
        FOREIGN KEY (ACCOUNT_SORTCODE, ACCOUNT_NUMBER)
        REFERENCES ACCOUNT (ACCOUNT_SORTCODE, ACCOUNT_NUMBER)
        ON DELETE RESTRICT,
    CONSTRAINT BANK_TRANSACTION_TYPE_CK
        CHECK (TRANSACTION_TYPE IN ('d', 'D', 'w', 'W', 't'))
);

CREATE UNIQUE INDEX BANK_TRANSACTION_PKX
    ON BANK_TRANSACTION (TRANSACTION_ID);

-- History is always read per account, newest first (QueryTransaction /
-- IMSBankHistory: ORDER BY TIMESTMP DESC FETCH FIRST 50 ROWS ONLY).
CREATE INDEX BANK_TRANSACTION_ACCTX
    ON BANK_TRANSACTION (ACCOUNT_SORTCODE, ACCOUNT_NUMBER, TRANSACTION_TIMESTAMP);

-- ---------------------------------------------------------------------
-- BANK_COUNTER  (replaces CICS ENQ + CONTROL rows behind NEWCUSNO/NEWACCNO)
-- COUNTER_NAME keeps the legacy CONTROL_NAME values so the data can be
-- copied over unchanged (see V003):
--   'BANKZCUST' || sortcode || '  '  last customer number   (CRECUST/BANKDATA)
--   sortcode || '-ACCOUNT-LAST'      last account number    (CREACC/BANKDATA)
--   sortcode || '-ACCOUNT-COUNT'     number of accounts     (CREACC/BANKDATA)
--   'IMS-LASTTXID-' || ACCID         IMS ACCOUNT.LASTTXID   (IBTRAN, see V002)
-- VARCHAR(32) matches CONTROL.CONTROL_NAME CHAR(32).
-- ---------------------------------------------------------------------
CREATE TABLE BANK_COUNTER (
    COUNTER_NAME               VARCHAR(32)   NOT NULL,
    COUNTER_VALUE              BIGINT        DEFAULT 0 NOT NULL,
    CONSTRAINT BANK_COUNTER_PK PRIMARY KEY (COUNTER_NAME)
);

CREATE UNIQUE INDEX BANK_COUNTER_PKX
    ON BANK_COUNTER (COUNTER_NAME);
