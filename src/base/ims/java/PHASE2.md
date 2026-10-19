# Phase 2: IMS transactions as Java JMP programs

## Decision: IMS data stays on DL/I
Moving the IMS databases to Db2 is a separate project. In this phase the DBDs, the PSBs and the segment layouts stay as they are. The Java programs make the same DL/I calls (GU/GHU/GHN/ISRT/REPL with the same SSAs) through `com.ibm.ims:udb`, using raw `ByteBuffer` I/O areas, so the segment bytes are the same ones the COBOL writes.

## Ported programs (package `com.ibm.bankofz.ims.jmp`)
| Legacy | Java | PSB (unchanged) | In / out message bytes |
|---|---|---|---|
| `IBACSUM.cbl` | `Ibacsum` | IBACSUM | 21 / 37 + 17n |
| `IBGCUDAT.cbl` | `Ibgcudat` | IBGCUDAT | 21 / 279 |
| `IBSCUDAT.cbl` | `Ibscudat` | IBSCUDAT | 250 / 274 |
| `IBLOGOUT.cbl` | `Iblogout` | IBLOGOUT | 21 / 36 |
| `IBLOGIN.pli` (the deployed PGM IBLOGIN) | `Iblogin` | IBLOGIN | 37 / 36 |
| `IBTRAN.cbl` | `Ibtran` | IBTRAN | 56 / 48 + 17n |

All six programs run on the `Dli` interface: `UdbDli` on z/OS, `FakeDli` in the tests. `Mpp` contains the shared I/O PCB loop: GU until QC, one ISRT per message, then a syncpoint.

## Legacy behaviour kept on purpose (fix only after parity, with sign-off)
- **IBLOGOUT:** if the REPL fails, it still answers `LOGOFF SUCCESSFUL`, because the BAD-STATUS text gets overwritten. `IBLOGIN1.cbl` has the same bug. `IBLOGIN.pli` does not, and it is the program that's deployed.
- **IBTRAN, failed history write:** if the HISTORY ISRT fails, IBTRAN still REPLs ACCOUNT, sends its reply, and ends the program. It never issues ROLB.
- **IBTRAN balance text:** the balance in `MSG-OUT` is `PIC Z(13).99`, which is unsigned, so a negative balance prints without its sign. Any BAD-STATUS from the ACCOUNT REPL is overwritten by the balance.
- **Deliberate deviation, more than 6 accounts:** for a customer with more than 6 accounts, `Ibacsum` and `Ibtran` throw an exception. The COBOL would write past the end of the `OCCURS 1 TO 6` table.

## Db2 + IMS commit/rollback (IBTRAN)
The order is unchanged:
1. Db2 history, written by `nazare.jmp.controller.InsertHist`, which is called unchanged through `HistorySink.insertHist()` with a direct `ByteBuffer`.
2. IMS HISTORY ISRT.
3. ACCOUNT REPL.

`TransactionService` opens `jdbc:db2:` with `autoCommit=false` and never commits. That means the Db2 row becomes permanent only at the IMS syncpoint, and only if Db2 is attached to the region through ESAF/RRS. **This has not been verified on z/OS.**

`InsertHist` also swallows every exception, so IBTRAN cannot tell when the Db2 write fails.

Setting `-Dbankz.ibtran.strict=true` (default off, which keeps parity with the COBOL) changes what happens when the HISTORY ISRT fails: the program issues ROLB and skips the REPL instead.

## Parallel run (z/OS steps, not done yet)
1. For each program, add a new PSB that is a copy of the legacy one with only the name changed: `IBACSUMJ`, `IBGCUDAJ`, `IBSCUDAJ`, `IBLOGOUJ`, `IBLOGINJ`, `IBTRANJ`. Use the same PCBs and `LANG=JAVA`. The existing PSBs stay untouched.
2. Run PSBGEN/ACBGEN and populate the IMS catalog for the new PSBs.
3. Point `-Dbankz.ims.metadataURL.<PSB>` at the generated metadata class. That class is not in the repo.
4. Create each program with `CREATE PGM ... LANG(JAVA)` and a transaction of the same name in `CLASS(6)`, following `IBGHIST` in `.setup/deploy/ims_spoc.jcl.j2`.
5. Map each program in `ims-region.yaml`, for example `ibacsumj: com/ibm/bankofz/ims/jmp/Ibacsum`. The Java program reads the PSB name from its `main`, so either pass it in or adjust it per program before deploying.
6. Replay the captured IMS tapes from Phase 0b against both transaction codes. Run `ParityGate` on the responses, and run an IMS unload diff on ACCOUNT, CUSTACCS, CUSTOMER and HISTORY plus a Db2 `PROCTRAN`/history diff after each replay. RC 8 (COMP-3 or numeric difference) blocks the next step.

## Open questions before switching any transaction
- **IBLOGIN input offsets:** `IBLOGIN.pli` declares the input as `TRAN_CODE CHAR(9)`, `IN_CUSTID CHAR(10)` with halfword LL/ZZ. The Java uses the queue layout that IBLOGIN1.cbl and z/OS Connect use (customer ID at offset 12, 9 bytes; password at offset 21, 16 bytes). Confirm against a captured IBLOGIN message.
- **Customer ID conversion:** PL/I `CUSTID_NUM = IN_CUSTID` is modelled with COBOL NUMVAL rules. Confirm that non-numeric input behaves the same.
