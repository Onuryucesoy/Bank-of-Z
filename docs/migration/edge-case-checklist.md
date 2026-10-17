# Per-program migration checklist

Copy this file to `docs/migration/programs/<PROGRAM>.md` for each program being migrated, fill it in, and link it from the PR. A row is done when it has evidence: a golden record, a test name, or a reviewer's sign-off on why it doesn't apply.

**Program:** `________`  **Source:** `src/base/.../________`  **Java owner:** `________`  **Golden records captured on:** `________`

## A. Interface

- [ ] Every caller is listed: z/OS Connect asset, `EXEC CICS LINK`, `RUN TRANSID`, IMS transaction, JNI. See [inventory.md](inventory.md).
- [ ] The COMMAREA / message layout is modelled as typed fields per operation, with no byte-buffer REDEFINES emulation. A codec test covers every offset (example: `ProcIsrtCodecTest`).
- [ ] Every REDEFINES on the interface is listed, with the discriminator that selects it (function code, 88-level, length).
- [ ] Bytes not defined by the active REDEFINES member are ignored on read and filled with a documented value on write.
- [ ] `POINTER` / `PIC X(4)` PCB fields are carried as opaque bytes and never interpreted.
- [ ] Every output field set on each path is listed, including fields that are *not* reset (INITIALIZE vs residue).
- [ ] The response length (`LL`) matches, including OCCURS DEPENDING ON tables (IBACSUM: `28 + TOTAL-ACCS * 17`, then recomputed as `LENGTH OF OUTPUT-AREA`).

## B. Magic values and sentinels

- [ ] All-9s keys: INQACC `99999999` = last account; INQCUST `9999999999` = last customer. Both behave the same in Java, **including the empty-table case**: INQACC's "last" path abends HNCS on SQLCODE +100.
- [ ] SPACES / LOW-VALUES sentinels: INQACC returns not-found as ACCOUNT-TYPE spaces **with INQACC-SUCCESS = 'Y'**. Java must give the same answer to the same caller, or the change must be agreed and called out.
- [ ] Fixed message strings are copied byte for byte, padding included (`CUSTOMER DOES NOT EXIST`, `ACCOUNT DOES NOT EXIST`, `PASSWORD INVALID`, `CUSTOMER ALREADY LOGGED IN`, `LOGIN SUCCESSFUL`, `BAD STATUS CODE WAS RECEIVED: `, `INVALID ACCOUNT ACTION. MUST BE 'w' OR 'd'.`).
- [ ] "Success" values that aren't obvious: IBACSUM MSG-OUT = 32 × `'0'`; INQACC success flag 'Y' on not-found.
- [ ] Hard-coded business constants are listed: sort code 987654, COMM-FACILTYPE 496 (= payment), account types `MORTGAGE` / `LOAN    ` (8 chars, space padded), customer type 'c'/'t', status '0'/'1'.
- [ ] Commented-out or debugging-only code (`D` lines, `RULES-CHECK`, "fictional bug" TODOs) is recorded as **not** in scope.

## C. Numerics

- [ ] Every numeric field is mapped to a `PicSpec` and usage (DISPLAY / COMP-3 / COMP / COMP-5). See [data-contracts.md](data-contracts.md).
- [ ] Every COMPUTE/ADD/SUBTRACT/MULTIPLY/DIVIDE uses `CobolRounding.TRUNCATE` unless the COBOL says `ROUNDED`.
- [ ] Each store into a smaller field is either `truncateHighOrder` (COBOL default) or an explicit, agreed `requireFits` with a defined error path.
- [ ] Cross-stack precision: CICS S9(10)V99 vs IMS S9(13)V9(2). No value passes between them without a size check.
- [ ] Negative amounts are tested (debits, transfers use `* -1`), as well as zero and the maximum value.
- [ ] Missing business checks are reproduced or explicitly fixed with sign-off: XFRFUN has no overdraft check; DBCRFUN only checks funds for payments (FACILTYPE 496); IBTRAN withdrawals have no balance check.

## D. Error, abend and transaction branches

- [ ] Every `EXEC CICS ABEND ABCODE(...)` is listed with its trigger (e.g. INQACC HRAC, HNCS, HROL) and the Java equivalent (exception type, HTTP status, rollback).
- [ ] Every SQLCODE branch is covered: 0, +100, -911/-913 (deadlock/timeout: XFRFUN retries up to 5 times with a 1-second DELAY), and any other negative code.
- [ ] `SYNCPOINT` / `SYNCPOINT ROLLBACK` placement maps to a Java transaction boundary, and the unit of work is the same size.
- [ ] Storm-drain / ABNDPROC side effects (abend record written) are kept or replaced by equivalent logging.
- [ ] `HANDLE ABEND` labels are mapped: what the caller sees after an abend.

## E. DL/I (IMS programs only)

- [ ] PSB and PROCOPT are recorded. The Java side uses a PSB with no more access than needed (read paths: IBGCUDAT / IBACSUM, PROCOPT=G).
- [ ] Each call (GU / GHU / GN / GHN / ISRT / REPL / DLET) is mapped, including the SSA field, operator (`EQ` vs `= `) and key type (binary fullword vs doubleword).
- [ ] Status codes: blank, GE, GB, II (duplicate on ISRT), QC/CF on the IOPCB, and "anything else" each have a test.
- [ ] GE vs GB are treated the same where the program does (IBLOGIN, IBACSUM).
- [ ] Loop termination matches: IBACSUM stops at the first missing ACCOUNT, and only reports `NO ACCOUNT` when none was found before.
- [ ] Result order is DL/I physical order (HDAM synonym chain), not key order. No `ORDER BY` added unless agreed.
- [ ] Hold calls (GHU/GHN) followed by REPL keep the same locking expectation in Java (optimistic or pessimistic, documented).
- [ ] Timestamp formats written to the segment match the DBD `DFSMARSH` pattern, or the mismatch is documented (IBLOGIN writes `yyyy_MM_dd HH:mm:ss:hh0`).
- [ ] Table limits: IBACSUM OCCURS 1 TO 6, QueryTransaction 50 rows / 4200 bytes. What happens past the limit is decided, not left undefined.

## F. Encoding and data

- [ ] All character conversion uses Cp1047 (`Ebcdic.CP1047`).
- [ ] Trailing-space semantics: comparisons ignore trailing spaces (password CHAR(16), account type X(8)).
- [ ] Any sorted or range query on character keys is checked for EBCDIC vs ASCII collation differences.
- [ ] Date fields are kept as raw `CobolDate` until validated. Invalid dates (00000000, 20260230) round-trip unchanged.
- [ ] ID parsing matches: `FUNCTION NUMVAL` on X(9) vs PL/I `PIC'(9)9'` on CHAR(10), with leading/trailing spaces and non-digits each tested.

## G. Security

- [ ] Passwords and other secrets are never logged (IBLOGIN logs `[REDACTED]`; Java `CustomerSegment.toString()` redacts).
- [ ] Plaintext password storage is recorded as a finding for a later phase, not silently "fixed" in a like-for-like port.
- [ ] No trust-all TLS managers are copied from `IMSBankHistory`.

## H. Rollout

- [ ] Golden records replayed against COBOL and Java, compared byte for byte.
- [ ] The Java path is behind a flag / routing rule that defaults to COBOL.
- [ ] Rollback is a configuration change, not a redeploy.
- [ ] Reviewer sign-off on every deliberate divergence (listed below).

### Deliberate divergences

| # | COBOL behaviour | Java behaviour | Reason | Approved by |
|---|---|---|---|---|
| | | | | |

## Known divergences in the Phase 1 primitives

These are already present in the code in `src/base/ims/java` and need sign-off when the first program uses them:

| Area | COBOL | Java | Why |
|---|---|---|---|
| DTO construction | MOVE truncates oversized text/numbers silently | `ProcIsrtCommarea` constructors throw | Fail loudly at the boundary; truncation stays available through `Money.truncateHighOrder` |
| Invalid numeric bytes | Data exception (S0C7) only when the field is used | `ZonedDecimal` / `PackedDecimal` throw on decode | Same failure, earlier |
| IBACSUM, more than 6 accounts | Storage overlay (undefined) | `MORE_THAN_SIX_ACCOUNTS` outcome | Undefined behaviour can't be reproduced; needs a product decision |
| IBACSUM bad status | `BAD STATUS CODE WAS RECEIVED: xx` with the DL/I code | Same text with the first 2 characters of the SQLSTATE | IMS JDBC reports SQLSTATE, not DL/I status; map properly before parallel run |
| IBLOGIN | REPL STATUS='1' and LASTLOGIN | `LoginCheck` stops at `WOULD_LOG_IN`, never writes | Phase 1 is read-only |
