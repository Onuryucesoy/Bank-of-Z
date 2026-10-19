-- =====================================================================
-- V003__sequences.sql  --  Bank of Z Java migration, Phase 1
--
-- Db2 sequences that replace the GETNEW function of the counter
-- interfaces src/base/cics/copy/NEWCUSNO.cpy and NEWACCNO.cpy.
--
-- Legacy behaviour being mirrored (CRECUST.cbl / CREACC.cbl):
--   1. EXEC CICS ENQ RESOURCE('BANKZCUST'||sortcode) (or 'BANKZACCT'...)
--   2. SELECT CONTROL_VALUE_NUM FROM CONTROL WHERE CONTROL_NAME = ...
--   3. value + 1 -> UPDATE CONTROL                         (= GETNEW 'G')
--   4. INSERT CUSTOMER / ACCOUNT with the new number, then DEQ
--   On failure the program abends (HNCS) and the unit of work backs out,
--   which is the only "ROLLBACK" ('R') that exists today; 'C' (CURRENT)
--   is a plain read of the row (INQCUST uses it for customer 9999999999).
--
-- Mapping onto sequences:
--   'G' GETNEW   -> VALUES NEXT VALUE FOR CUSTOMER_NUMBER_SEQ
--   'C' CURRENT  -> SELECT COUNTER_VALUE FROM BANK_COUNTER (the high-water
--                   mark), or PREVIOUS VALUE FOR ... inside the same session
--   'R' ROLLBACK -> no-op. A sequence value is never handed back; a
--                   rolled-back create leaves a gap, exactly like a CICS
--                   named counter. See phase-1-data-layer.md section 3.
--
-- Properties chosen to match PIC 9(10) (customer) and PIC 9(8) (account):
--   NO CYCLE    numbers are never reused
--   MAXVALUE    9999999999 / 99999999 (the PIC width); 9999999999 is also
--               reserved by INQCUST as "last customer", so the customer
--               sequence stops one short of it
--   NO CACHE    strictly ascending without gaps on restart, like the
--               ENQ-serialised CONTROL row. CACHE n may be enabled later if
--               gaps after a Db2 restart are acceptable.
--   ORDER       ascending allocation order across data-sharing members
--
-- One sequence per entity, not per sort code: the application only ever
-- runs with the single sort code in SORTCODE.cpy (987654). A multi-sort-
-- code deployment would need one sequence per sort code (or BANK_COUNTER
-- with SELECT ... FOR UPDATE).
--
-- Seeding: Db2 does not allow START WITH <subquery>, so the sequences are
-- created at 1 and then restarted from BANK_COUNTER by the SQL procedure
-- SEED_BANK_SEQUENCES, which must be CALLed once after V002 has copied the
-- CONTROL rows into BANK_COUNTER and before Java writers go live.
-- =====================================================================

CREATE SEQUENCE CUSTOMER_NUMBER_SEQ
    AS BIGINT
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9999999998
    NO CYCLE
    NO CACHE
    ORDER;

CREATE SEQUENCE ACCOUNT_NUMBER_SEQ
    AS BIGINT
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 99999999
    NO CYCLE
    NO CACHE
    ORDER;

-- Make sure the two counters the sequences are seeded from exist, even in
-- an empty target database (MERGE keeps any value V002 already copied).
MERGE INTO BANK_COUNTER T
    USING (VALUES ('BANKZCUST987654  ')) AS S (COUNTER_NAME)
    ON T.COUNTER_NAME = S.COUNTER_NAME
    WHEN NOT MATCHED THEN INSERT (COUNTER_NAME, COUNTER_VALUE)
        VALUES (S.COUNTER_NAME, 0);

MERGE INTO BANK_COUNTER T
    USING (VALUES ('987654-ACCOUNT-LAST')) AS S (COUNTER_NAME)
    ON T.COUNTER_NAME = S.COUNTER_NAME
    WHEN NOT MATCHED THEN INSERT (COUNTER_NAME, COUNTER_VALUE)
        VALUES (S.COUNTER_NAME, 0);

--#SET TERMINATOR @

-- ---------------------------------------------------------------------
-- SEED_BANK_SEQUENCES: restart both sequences at BANK_COUNTER + 1.
-- Native SQL procedure (Db2 for z/OS 12 / Db2 LUW). Run with the
-- statement terminator set to '@' (DSNTEP2/SPUFI: --#SET TERMINATOR @,
-- Db2 CLP: db2 -td@ -f V003__sequences.sql).
-- ---------------------------------------------------------------------
CREATE PROCEDURE SEED_BANK_SEQUENCES (IN P_SORTCODE CHAR(6))
    LANGUAGE SQL
    MODIFIES SQL DATA
BEGIN
    DECLARE V_LAST_CUSTOMER BIGINT DEFAULT 0;
    DECLARE V_LAST_ACCOUNT  BIGINT DEFAULT 0;
    DECLARE V_STMT          VARCHAR(200);

    SELECT COALESCE(MAX(COUNTER_VALUE), 0) INTO V_LAST_CUSTOMER
      FROM BANK_COUNTER
     WHERE COUNTER_NAME = 'BANKZCUST' || P_SORTCODE || '  ';

    SELECT COALESCE(MAX(COUNTER_VALUE), 0) INTO V_LAST_ACCOUNT
      FROM BANK_COUNTER
     WHERE COUNTER_NAME = P_SORTCODE || '-ACCOUNT-LAST';

    SET V_STMT = 'ALTER SEQUENCE CUSTOMER_NUMBER_SEQ RESTART WITH '
                 || VARCHAR(V_LAST_CUSTOMER + 1);
    EXECUTE IMMEDIATE V_STMT;

    SET V_STMT = 'ALTER SEQUENCE ACCOUNT_NUMBER_SEQ RESTART WITH '
                 || VARCHAR(V_LAST_ACCOUNT + 1);
    EXECUTE IMMEDIATE V_STMT;
END
@

--#SET TERMINATOR ;

CALL SEED_BANK_SEQUENCES('987654');

-- ---------------------------------------------------------------------
-- Usage templates for the Java counter service (not executed here):
--
-- GETNEW 'G' (same unit of work as the INSERT CUSTOMER):
--   VALUES NEXT VALUE FOR CUSTOMER_NUMBER_SEQ INTO :newNumber;
--   UPDATE BANK_COUNTER SET COUNTER_VALUE = :newNumber
--    WHERE COUNTER_NAME = 'BANKZCUST987654  ' AND COUNTER_VALUE < :newNumber;
--   -- keeps BANK_COUNTER as the readable high-water mark for 'C' and for
--   -- the INQCUST "last customer" lookup.
--
-- CURRENT 'C':
--   SELECT COUNTER_VALUE FROM BANK_COUNTER
--    WHERE COUNTER_NAME = '987654-ACCOUNT-LAST';
--
-- ROLLBACK 'R': no SQL. Roll back the unit of work; the drawn number is
-- burned (gap), same as a CICS named counter.
-- ---------------------------------------------------------------------
