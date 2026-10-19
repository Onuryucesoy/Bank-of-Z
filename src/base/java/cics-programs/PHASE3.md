# Phase 3: Db2-backed CICS programs as JCICS Java programs

`CUSTDB2`, `ACCDB2` and `PAYDBCR` are copybooks, not programs, so this phase ports the programs that use them.
The COBOL stays in place and keeps working; each Java program is a separate `PROGRAM ... JVMCLASS(...)`
definition that can be switched in per program once its parallel run is clean.

## Ported programs (package `com.ibm.bankofz.cics`)
| Legacy | JCICS entry (`main(CommAreaHolder)`) | Logic | Interface |
|---|---|---|---|
| `CRDTAGY1`–`CRDTAGY5` | `Crdtagy1`–`Crdtagy5` | `CreditAgency` | channel `CIPCREDCHANN`, containers `CIPA`–`CIPE` (CUSTOMER layout) |
| `INQCUST` | `Inqcust` | `InquireCustomer` | INQCUSTZ COMMAREA |
| `CRECUST` | `Crecust` | `CreateCustomer` + `JcicsCreditCheck` | CRECUST, 399 bytes |
| `UPDCUST` | `Updcust` | `UpdateCustomer` | UPDCUST COMMAREA |
| `DELCUS` | `Delcus` | `DeleteCustomer` | DELCUS, 399 bytes |
| `INQACC` | `Inqacc` | `InquireAccount` | INQACC COMMAREA |
| `INQACCCU` | `Inqacccu` | `InquireAccountsForCustomer` | INQACCCU, ODO up to 20 accounts |
| `CREACC` | `Creacc` | `CreateAccount` | CREACC, 100 bytes |
| `UPDACC` | `Updacc` | `UpdateAccount` | UPDACC COMMAREA |
| `DELACC` | `Delacc` | `DeleteAccount` | DELACC COMMAREA |
| `DBCRFUN` | `Dbcrfun` | `DebitCredit` | PAYDBCR, 132 bytes |
| `XFRFUN` | `Xfrfun` | `TransferFunds` | XFRFUN, 90 bytes |

Every COMMAREA is read and written through the generated `copybook-bridge` classes (CP037, zoned decimal with
trailing sign, binary COMP), so offsets, lengths and sign nibbles are the COBOL ones. Programs that LINK
(`CREACC`, `DELCUS`, `INQACCCU`) LINK by the legacy program name, so they reach either the COBOL or the Java
version, whichever is installed.

## Unit of work
None of the ports commit. Db2 work goes over `jdbc:default:connection` and is part of the CICS task's unit of
work, as with the COBOL's EXEC SQL. `SYNCPOINT ROLLBACK` is issued through `CicsTask.rollback()` exactly where
the COBOL issues it (DBCRFUN PROCTRAN failure, XFRFUN missing account, INQACCCU SQL failure), and abends go
through `CicsTask.abend()` with the COBOL abend codes, which backs the task out. No CICS program here touches
IMS, so there is no Db2 + IMS two-phase commit path in this phase (IBTRAN is handled in Phase 2).

## Legacy behaviour kept on purpose (fix only after parity, with sign-off)
- **INQCUST** customer 0 (random) and 9999999999 (last) look up sort code 000000, so they fail with code 9.
- **UPDCUST** validates only the first 9 bytes of the title and returns a corrupted credit-score review date.
- **CRECUST** compares all 10 title bytes (so `Professorx` is rejected, unlike UPDCUST).
- **DELACC** returns a negative interest rate without its sign.
- **INQACC** reports success `Y` for a missing account.
- **CREACC** checks only the prefix of the account type (`ISAXXXXX` is accepted); next statement is today + 30 days.
- **DELCUS** ignores DELACC's result, and reports success when the customer row vanished after INQCUST.
- **DBCRFUN** lets counter (non-496) withdrawals overdraw without limit; returns the new balances even when the
  UPDATE fails; a PROCTRAN failure sets fail code `0` (`MOVE '02'` into a one-byte field).
- **XFRFUN** lets the FROM account go negative; leaves the fail code unchanged on success.
- New balances are truncated to `S9(10)V99` like a COMPUTE into the COMP-3 host variable (no ON SIZE ERROR).

## Deliberate deviations
- **INQCUST** throws instead of looping forever when the last customer disappears mid-inquiry.
- **XFRFUN** abends `RUF2`/`RUF3` on a Db2 deadlock instead of retrying up to five times with SYNCPOINT + DELAY.
- **DBCRFUN/XFRFUN** do not reproduce `HANDLE ABEND` for AFCR/AFCS/AFCT (file-control abends a JDBC path cannot
  raise).
- **DELCUS** passes a blank APPLID to DELACC (DELACC only displays it).
- **CRDTAGY/CRECUST** delay with `Thread.sleep`; the random credit score is in range but not COBOL's `RANDOM`
  sequence, so replay compares those fields by range, not bytes.

## Parallel run on z/OS (not done yet)
1. Install the `cics-programs` jar in a JVM server and define each Java program under a new name
   (e.g. `XFRFUNJ`), leaving the COBOL definitions alone.
2. Record traffic with `cics-capture` (`CaptureProxy` under the legacy name, legacy program re-defined under the
   alias in `capture-targets.properties`).
3. Snapshot `ACCOUNT`, `CUSTOMER`, `PROCTRAN` and `CONTROL`, replay the tape against the `...J` programs on a
   copy of the tables, snapshot again, and run `ParityGate` on both the COMMAREA diff and the Db2 diff.
   RC 8 (COMP-3/numeric divergence) blocks Phase 4.
4. Switch callers per program only after a clean gate; the COBOL stays installed until Phase 5.

None of this code has run in a CICS region yet; the 58 tests here use H2 in DB2 mode.
