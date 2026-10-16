# DBCRFUN → Java

Java 17 port of the CICS debit/credit program `src/base/cics/cobol/DBCRFUN.cbl`, the program behind the
`POST /accounts/{accountId}/deposit` API (FACILTYPE 496) and the teller cash screens.

```bash
cd src/modern/dbcrfun-java && mvn -B test
```

`DebitCreditService.apply(DebitCreditRequest)` reproduces DBCRFUN on the same Db2 `ACCOUNT` and `PROCTRAN`
tables, in one JDBC transaction (commit on success, rollback otherwise; the CICS `SYNCPOINT`).
`DebitCreditServiceTest` runs it against H2 in Db2 mode, using the DDL from `.setup/jcl/cics/Db2-create.j2`.
Each test names the COBOL lines it pins.

## Rules carried over

| Rule | COBOL |
|---|---|
| Sort code always `987654` from the `SORTCODE` copybook; the caller's value is ignored | 211-212 |
| Unknown account → fail code `1`; other SQL error → `2` | 279-286 |
| Payment (FACILTYPE 496) debit from MORTGAGE/LOAN → `4` | 330-338 |
| Payment debit that would take the available balance below zero → `3` | 340-350 |
| Both balances += amount; PIC S9(10)V99 truncation | 384-387 |
| PROCTRAN: `DEB`/`CRE` with `COUNTER WTHDRW`/`COUNTER RECVED` for teller; `PDR`/`PCR` with the description (or `COMM-ORIGIN(1:14)`) for payments | 491-525 |

## Legacy quirks kept on purpose (decide before fixing)

1. **Teller debits are never balance-checked** and can overdraw without limit.
2. **`ACCOUNT_OVERDRAFT_LIMIT` is read but unused.** Payments are checked against zero, not the arranged overdraft.
3. **Payment credits into MORTGAGE/LOAN accounts are refused** (the second check at 368-376 has no sign test), so
   loan repayments through the API fail with code `4`.
4. **PROCTRAN insert failure is reported as fail code `0`**: `MOVE '02'` into the 1-byte `COMM-FAIL-CODE` (645).
   The returned balances show the rolled-back values.
5. **Lost-update window**: plain `SELECT` then `UPDATE` of absolute balances under `ISOLATION(CS)`
   (`.setup/build/languages/Db2Binds.yaml`). The port keeps this shape. `SELECT ... FOR UPDATE` would close it.

## Not covered

- No recorded golden master: DBCRFUN needs a CICS region and Db2, so these tests are derived from the source.
  On a Bank of Z environment, replay the same cases through `POST /accounts/{id}/deposit` and compare.
- Abend and storm-drain handling (`ABNDPROC`, `AD2Z`/`AFCR`…) maps to exceptions/rollback here, not to CICS abends.
