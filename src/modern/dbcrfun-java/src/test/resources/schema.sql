-- ACCOUNT and PROCTRAN as defined in .setup/jcl/cics/Db2-create.j2 (tablespace clauses removed)
CREATE TABLE ACCOUNT (
    ACCOUNT_EYECATCHER             CHAR(4),
    ACCOUNT_CUSTOMER_NUMBER        CHAR(10),
    ACCOUNT_SORTCODE               CHAR(6) NOT NULL,
    ACCOUNT_NUMBER                 CHAR(8) NOT NULL,
    ACCOUNT_TYPE                   CHAR(8),
    ACCOUNT_INTEREST_RATE          DECIMAL(6, 2),
    ACCOUNT_OPENED                 DATE,
    ACCOUNT_OVERDRAFT_LIMIT        INTEGER,
    ACCOUNT_LAST_STATEMENT         DATE,
    ACCOUNT_NEXT_STATEMENT         DATE,
    ACCOUNT_AVAILABLE_BALANCE      DECIMAL(12, 2),
    ACCOUNT_ACTUAL_BALANCE         DECIMAL(12, 2)
);
CREATE TABLE PROCTRAN (
    PROCTRAN_EYECATCHER            CHAR(4),
    PROCTRAN_SORTCODE              CHAR(6) NOT NULL,
    PROCTRAN_NUMBER                CHAR(8) NOT NULL,
    PROCTRAN_DATE                  DATE,
    PROCTRAN_TIME                  CHAR(6),
    PROCTRAN_REF                   CHAR(12),
    PROCTRAN_TYPE                  CHAR(3),
    PROCTRAN_DESC                  CHAR(40),
    PROCTRAN_AMOUNT                DECIMAL(12, 2)
);
