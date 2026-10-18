# Cross-program invariants (Phase 0 freeze)

Behaviour that is not visible in any single copybook or API mapping but that
the Java port must preserve. Each item cites the COBOL it was read from;
line numbers refer to this commit.

## 1. `BANKZCUST` customer-number allocation

**Source:** `src/base/cics/cobol/CRECUST.cbl` (`ENQ-NAMED-COUNTER`,
`DEQ-NAMED-COUNTER`, `UPD-NCS` / CONTROL access around lines 540-575 and
1490-1540); seed row written by `BANKDATA.cbl`; `CREACC.cbl` does the same
with `BANKZACCT`.

1. `EXEC CICS ENQ RESOURCE('BANKZCUST' || sortcode || '  ') LENGTH(16)` —
   serialises allocation *within the CICS region(s) sharing the ENQ scope*.
   ENQ failure → `COMM-SUCCESS 'N'`, `COMM-FAIL-CODE '3'`.
2. `SELECT CONTROL_VALUE_NUM FROM CONTROL WHERE CONTROL_NAME =
   'BANKZCUST987654'` (CHAR(32), blank padded). SQLCODE ≠ 0 → fail code `'4'`.
3. `ADD 1`, `UPDATE CONTROL SET CONTROL_VALUE_NUM = :new`. Failure → `'4'`.
4. The new value is the customer number (`PIC 9(10)`).
5. `DEQ` (failure → `'5'`). The header comment talks about restoring the
   counter on failure, but **no code decrements it**: numbers burnt by a
   later failure are gaps, and a CICS abend rolls back the Db2 update with
   the rest of the unit of work.

**Java contract** (`com.ibm.bankofz.core.id`):

- `IdAllocator.allocate(CounterName.customer(sortCode))` returns the next
  number; callers never see the mechanism.
- `ControlTableIdAllocator` uses the *same CONTROL row* with an atomic
  `UPDATE … SET CONTROL_VALUE_NUM = CONTROL_VALUE_NUM + 1` then reads it
  back in one Db2 unit of work; the row lock replaces the ENQ.
- **Coexistence rule:** CRECUST reads CONTROL without a lock (CS) before
  updating, so a COBOL and a Java allocator running at the same time can
  hand out the same number. Until CRECUST is retired, exactly one side
  allocates for a counter (CRECUST stays the writer through the read-only
  Phase 2; Java takes over only when create-customer is cut over).
- `SequenceIdAllocator` (Db2 `SEQUENCE`, DDL in
  `src/java-service/src/main/resources/db2/id-sequences.sql`) is the
  post-cutover mechanism, started at `CONTROL_VALUE_NUM + 1`.
- Numbers are unique and increasing, not dense. Exceeding `PIC 9(10)`
  (`9(8)` for accounts) is an allocation failure.

## 2. `CRECUST` asynchronous credit-check fan-out

**Source:** `CRECUST.cbl` lines ~615-1120; `CRDTAGY1.cbl`..`CRDTAGY5.cbl`.

1. Channel `CIPCREDCHANN`. For n = 1..5: `PUT CONTAINER('CIPA'..'CIPE')
   FROM(DFHCOMMAREA)` (the 399-byte `CRECUST` commarea) then
   `RUN TRANSID('OCR'||n) CHANNEL(...) CHILD(token)`. PUT failure → `'A'`,
   RUN failure → `'B'`.
2. Each agency `CRDTAGYn`: `DELAY` a random 1-2 s
   (`((3-1)*RANDOM(EIBTASKN))+1`), `GET CONTAINER` into `WS-CONT-IN`
   (`COPY CUSTOMER`, **397 bytes** — the 399-byte commarea overlays it; the
   first 397 bytes line up field for field, the success/fail bytes are
   dropped), sets `CUSTOMER-CREDIT-SCORE = ((999-1)*RANDOM)+1` (1..998),
   and `PUT`s the container back.
3. CRECUST `DELAY FOR SECONDS(3)`, then loops `FETCH ANY ... NOSUSPEND`
   collecting whatever children have finished, `GET CONTAINER` from each
   (errors `'C'`..`'H'`).
4. Score = integer average of the scores received
   (`WS-TOTAL-CS-SCR / WS-RETRIEVED-CNT`). Review date = today +
   `((21-1)*RANDOM)+1` days. Agencies that have not finished after the 3 s
   window are ignored; zero answers → credit-check error, customer not
   created.

**Java contract:** `CreditCheckService` / `CreditAgency` — five independent
agencies, run concurrently, a single 3-second deadline, average of the
answers that arrived, failure when none did. The score and review date are
non-deterministic and are excluded from golden-master value comparison
(`nonDeterministic` in `tests/golden/fixtures/CRECUST.json`, `CRDTAGY*.json`).

## 3. `DELCUS` cascading delete

**Source:** `src/base/cics/cobol/DELCUS.cbl` (LINKs at ~303, ~378, ~409).

1. `LINK INQCUST` — not found / error → `COMM-DEL-SUCCESS 'N'`, fail code
   copied from INQCUST.
2. `LINK INQACCCU` — up to 20 accounts of the customer.
3. For each account: `LINK DELACC` (which deletes ACCOUNT and writes
   PROCTRAN `ODA`). DELACC's result is **not checked**; a failure there does
   not stop the customer delete.
4. Select then `DELETE FROM CUSTOMER`; SQLCODE 100 on the select means a
   concurrent delete already happened and is treated as done.
5. Write PROCTRAN type `ODC` (description `PROC-DESC-DELCUS-*`: sort code,
   customer number, 14-char name, DOB `YYYY-MM-DD`).
6. Respond `COMM-DEL-SUCCESS 'Y'` with the deleted customer echoed.

There is no explicit SYNCPOINT: everything is one CICS unit of work, and
any abend (e.g. PROCTRAN insert failure) rolls back all account and customer
deletes. The Java port must keep accounts → customer → audit in one
transaction with the same tolerance for already-deleted rows.

## 4. `PROCTRAN` audit logging

**Source:** `PROCTRAN.cpy`, `PROCDB2.cpy`, the `WRITE-PROCTRAN*` sections of
`CRECUST`, `CREACC`, `DELCUS`, `DELACC`, `DBCRFUN`, `XFRFUN`.

- Every state-changing transaction inserts exactly one `BANKZ.PROCTRAN`
  row per affected account (XFRFUN: one `TFR` row) in the same unit of
  work as the change. Insert failure abends the task (e.g. `HWPT`), rolling
  back the business change: **no change without its audit row**.
- Columns: eyecatcher `PRTR`; sort code; account number (`00000000` for
  customer-level events); date `DD.MM.YYYY` host variable into a Db2 DATE;
  time `HHMMSS` from `ASKTIME`/`FORMATTIME`; reference = `EIBTASKN`
  (12 digits); type; 40-byte description whose layout depends on the type
  (`REDEFINES` in `PROCTRAN.cpy`); amount `S9(10)V99`.
- Types (88-levels): `OCC`/`ODC` branch create/delete customer, `OCA`/`ODA`
  branch create/delete account, `ICC`/`IDC`/`ICA`/`IDA` web variants,
  `CRE`/`DEB` teller credit/debit, `PCR`/`PDR` payment credit/debit (DBCRFUN
  when `COMM-FACILTYPE = 496`, which the z/OS Connect `depositToAccount`
  mapping always sets), `TFR` transfer, cheque `CHA`/`CHF`/`CHI`/`CHO`,
  `OCS` standing order.
- Java: `ProcTranRepository.insert` in the caller's transaction,
  `ProcTranType` for codes. Golden-master comparison treats the reference,
  date and time as non-deterministic.

## 5. CICS ↔ IMS customer-ID routing

**Source:** `src/frontend/js/utils.js` (`parseCustomerId`),
`src/frontend/js/api.js`.

- A UI customer ID is a one-letter system prefix plus the backend number:
  `C…` → CICS/Db2 (`/customers/{n}`, `/accounts/...`), `I…` → IMS
  (`/ims/customers/{n}`, `/ims/accounts/...`). The prefix never reaches the
  API or the mainframe.
- The two populations are independent: a CICS customer 1 and an IMS
  customer 1 are different people. Nothing reconciles them.
- Java: `CustomerRouting.parse` reproduces the rule. Phase 2 routing must
  keep the path split so the frontend is unchanged.

## 6. Other behaviour to preserve

| Program | Behaviour |
|---|---|
| `INQCUST` | Customer number `0000000000` returns a random existing customer; `9999999999` returns the highest-numbered one. Not found → `INQCUST-INQ-SUCCESS 'N'`, fail `'1'`. |
| `INQACC` | Account `99999999` returns the highest-numbered account. Not found still returns `INQACC-SUCCESS 'Y'` with an empty record — callers detect it from the data. |
| `INQACCCU` | Max 20 accounts (`OCCURS 1 TO 20 DEPENDING ON`); fail `'4'` when the customer is missing. |
| `UPDCUST` | Validates title against a fixed list (fail `'T'`) and non-blank first name (`'4'`); never updates the created date or key. |
| `XFRFUN` | Amount ≤ 0 → fail `'4'` with no SQL. Retries Db2 deadlock/timeout `-911` with 1-second delays; explicit `SYNCPOINT ROLLBACK` on partial failure so both legs commit or neither does. |
| `DBCRFUN` | Fail `'1'` account not found, `'2'` SQL error, `'3'` insufficient available funds on a payment debit, `'4'` debit/credit refused for mortgage/loan account types. |
| All CICS API calls | CCSID 037; numeric COMMAREA fields are zoned DISPLAY, so the API layer formats numbers (e.g. `formatNumber` to 10 digits for `DELCUS` customer numbers). |
