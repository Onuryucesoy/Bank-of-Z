# Bank of Z: phased migration of z/OS components to Java

Status: proposal. This plan covers the code in this repository only. It does not change any code.

## 1. Purpose and approach

This plan moves every z/OS component of Bank of Z to Java with a strangler approach. Each COBOL or PL/I program is replaced behind the interface it already has: the CICS COMMAREA, the IMS message and PCB, the batch DD and report, or the z/OS Connect operation. Old and new run side by side until they produce the same results. Then traffic switches over. The COBOL/PL/I build and deploy path stays intact until Phase 6, so every step before that can be undone by redeploying the existing load module.

Guiding rules:

1. **Contract first.** Copybooks, COMMAREAs, IMS segment layouts and `openapi.yaml` are byte-level and schema-level contracts. They are frozen before any program is rewritten. Changing them is a separate, versioned change.
2. **Characterize before porting.** Every program gets golden input/output records, captured from the running COBOL/PL/I, before work on its Java version starts. The Java port must reproduce current behavior, defects included (section 3.4). Fixing a defect is a separate change made after cutover.
3. **One program family per step, with its own switch.** Each cutover is a CICS `PROGRAM` definition change, an IMS `PGM`/`TRAN` change, a z/OS Connect asset change, or a JCL `EXEC` change. Each can be reversed in minutes.
4. **Stay on z/OS until Phase 6 (recommended).** Running Java inside CICS (JCICS in a JVM server or Liberty), inside IMS (JMP/JBP) and under JZOS batch keeps RACF, CICS/RRS syncpoints, the DBB/Wazi Deploy pipeline and the `safkeyring://` TLS setup unchanged. Moving off-platform is a possible later program of work and is out of scope here (section 9).

## 2. Current-state inventory

| Area | Location | Contents | Notes that affect migration |
|---|---|---|---|
| CICS COBOL | `src/base/cics/cobol/` | 32 programs: business programs (`INQACC`, `INQACCCU`, `INQACCS`, `INQCUST`, `INQTRAND`, `INQTRANL`, `CREACC`, `CRECUST`, `UPDACC`, `UPDCUST`, `DELACC`, `DELCUS`, `DBCRFUN`, `XFRFUN`, `CRDTAGY1`-`5`, `GETCOMPY`, `GETSCODE`), 3270 front-ends (`BNKMENU`, `BNK1*`), `ABNDPROC`, `BANKDATA` (batch data generator) | Programs call each other only through `EXEC CICS LINK` and `RUN TRANSID`, never through static `CALL`. Db2 copybooks come in via `EXEC SQL INCLUDE`. |
| Copybooks | `src/base/cics/copy/` | 42 copybooks: COMMAREAs (`INQACC`, `UPDACC`, `INQCUSTZ`, `PAYDBCR`, `XFRFUN`, ...), Db2 host-variable layouts (`ACCDB2`, `CUSTDB2`, `PROCDB2`, `CONTDB2`), `ABNDINFO` | Several are not used by any program (for example `BANKMAP`, `CUSTMAP`, `NEWACCNO`, `NEWCUSNO`, `STCUSTNO`, `WAZI`). `*Z` copybooks are mostly z/OS Connect provider layouts. |
| BMS | `src/base/cics/bms/` | 10 mapsets (`BNK1ACC` ... `BNK1UAM`) | `XFRFUN`, `UPDACC`, `CREACC`, `DELACC` are called **only** from 3270 programs (no z/OS Connect operation), which matters for Phase 6. |
| IMS COBOL | `src/base/ims/cobol/` | MPPs `IBACSUM`, `IBGCUDAT`, `IBSCUDAT`, `IBLOGOUT`, `IBLOGIN1`, `IBTRAN`; DL/I batch loaders `LOADACCT`, `LOADCUSA`, `LOADCUST`, `LOADHIST`, `LOADTSTA` | `IBTRAN` is compiled with `LP(32),JAVAIOP(JAVA64),DLL,RENT,PGMNAME(LONGMIXED)` and link-edited with `igzxjni2.x` and `libjvm31.x` (`dbb-app.yaml`). It calls `nazare.jmp.controller.InsertHist` through JNI using a `NewDirectByteBuffer` over its history segment. |
| IMS PL/I | `src/base/ims/pli/IBLOGIN.pli` | Login MPP (`PLITDLI`) | This is the deployed `IBLOGIN`. `IBLOGIN1.cbl` is not defined in `Ims-spoc.jcl.j2`. |
| IMS Java | `src/base/ims/java/` | Gradle, Java 21, jar `nazare-ims-jmp`; `QueryTransaction` (JMP `IBGHIST`), `InsertHist` (JNI target), `IMSBankHistory` | Dependencies: `com.ibm.db2:jcc:12.1.5.0`, `com.ibm.ims:udb:15.166`, `com.ibm.jzos:ibmjzos:2.4.11.3`, `javax.xml.bind:jaxb-api:2.4.0-b180830.0359`. Built on z/OS by the DBB `ImsJavaBuilder` task. The IMS JVM classpath is one jar (`.setup/jcl/ims/templates/jmp/dfsjvmpr.props.j2`). |
| IMS control blocks | `src/base/ims/PSB/`, `src/base/ims/DBD/` | 8 PSBs, 9 DBDs (HDAM/OSAM, single root, Cp1047) | DBB `Assembler` task, deploy types `PSBLOAD` / `DBDLOAD`. |
| Batch | `src/base/batch/` | `pli/BNKSTMT.pli` (Db2 statement report), `jcl/BNKSTMT.jcl` | Runs as `IKJEFT01` then `DSN RUN PROGRAM(BNKSTMT) PLAN(BANKZPLN)`; inputs come from `DATECARD` and `SORTCODE` DDs. |
| API | `src/api/` | Gradle with plugin `com.ibm.zosconnect.gradle` `1.6.2`; `src/main/api/openapi.yaml` (OpenAPI 3.0.3, 17 operations); 14 assets in `src/main/zosAssets/` (10 CICS, 4 IMS) | The asset-to-copybook mapping comes from `fileName=` in `zosAssets/<asset>/providerFiles/*.dai`. |
| Frontend | `src/frontend/` | Vanilla JS (`js/api.js`, `js/utils.js`) | Chooses the backend from the customer-ID prefix: `C` goes to CICS and `I` goes to IMS (`parseCustomerId`). |
| Build | `dbb-app.yaml`, `zapp.yaml` | DBB tasks `Cobol`, `PLI`, `Assembler`, `ImsJavaBuilder`, `zOSConnect`, `VanillaFrontend`, `ServerXmlPackager` | The static-call impact pattern `src/**/.cbl` (missing `*`) matches nothing. That is harmless today because there are no static calls. |
| Security | `.setup/setup/addcert.sh`, `.setup/setup/gencert-eku.sh`, `setup-zosconnect-server.sh`, `setup-frontend-server.sh` | Liberty `JCERACFKS` keystores at `safkeyring://${ZOS_ADMIN_USER}/${ZOS_KEYRING}` | The CICS region (`.setup/zconfig/cics-region.yaml`) currently defines a JVM server only for CMCI (`EYUSMSSJ.jvmprofile`). An application JVM server must be added. |

## 3. Cross-cutting risks and mandated controls

Each risk below has controls that apply in **every** phase. A phase cannot exit while any control relevant to its scope is unmet.

### 3.1 Packed-decimal and binary fidelity

Facts in the code:
- `INQACC.cbl` host variables: `HV-ACCOUNT-INT-RATE PIC S9(4)V99 COMP-3`, `HV-ACCOUNT-AVAIL-BAL` / `HV-ACCOUNT-ACTUAL-BAL PIC S9(10)V99 COMP-3`, `HV-ACCOUNT-OVERDRAFT-LIM PIC S9(9) COMP`, `WS-CICS-RESP PIC S9(8) COMP`.
- CICS COMMAREAs hold money as **zoned** `PIC S9(10)V99` DISPLAY (for example `COMM-AVAIL-BAL` in `UPDACC.cpy`), not COMP-3. Conversions between COMP-3 and zoned happen inside the programs and must be ported exactly.
- IMS uses `PIC S9(13)V9(2) COMP-3` (`IBTRAN.cbl` `BALANCE-ACC`, `AMOUNT-HIST`, `BALANCE-HIST`). Db2 DDL uses `DECIMAL(12,2)` (CICS) and `DECIMAL(15,2)` (IMS). The `ACCDB2.cpy` comments say `DECIMAL(10,2)`. Trust the DDL in `.setup/jcl/cics/Db2-create.j2`.
- No program in `src/base` uses `ROUNDED` or `ON SIZE ERROR`. COBOL arithmetic therefore **truncates** toward zero, and with `TRUNC(STD)` high-order digits are lost on overflow without any error.

Controls:
- All monetary and rate values are `java.math.BigDecimal` with an explicit scale equal to the PIC scale. `float`, `double`, `Float` and `Double` are forbidden for money. Enforce this with a build-time check (ArchUnit rule or Checkstyle `IllegalType`) that fails the build.
- Default rounding is `RoundingMode.DOWN` (truncation, matching COBOL without `ROUNDED`). Any use of `HALF_UP` must point to a COBOL `ROUNDED` clause, and none exist today.
- Overflow is emulated explicitly: the result is reduced modulo 10^n digits, as `TRUNC(STD)` does, and logged. It must not raise an exception unless the COBOL source raises one. A shared `CobolDecimal` / `PicSpec` utility owns this logic. Programs never hand-roll it.
- COMP-3 sign nibbles: write `C` / `D`, and accept `C`, `D` and `F` on read, as the compiler does. COMP fields are big-endian two's complement with `TRUNC(STD)` digit limits, not full binary range.
- Tests: property-based round-trip (pack, then unpack, then repack) over the full digit range of every PIC used, plus golden byte fixtures captured on z/OS for boundary values (0, -0, max, min, max+1).

### 3.2 Fixed-width records and REDEFINES

- Copybooks and COMMAREAs are byte-layout contracts. For example, `UPDACC.cpy` redefines `COMM-OPENED PIC 9(8)` as DAY/MONTH/YEAR (`DDMMYYYY`). Db2 `INTEGER` date columns, by contrast, hold `YYYYMMDD`.
- Every copybook in scope gets a Java record class. It is either **generated** (IBM Record Generator for Java / JZOS `com.ibm.jzos.fields`, or an equivalent copybook-to-Java tool) or hand-written and verified against the compiler listing (`LIST,MAP` offsets).
- **Golden-layout tests** assert, for every field: offset, length, type (`X`, `9`, `S9`, `COMP`, `COMP-3`), scale and total record length, against values taken from the compiler MAP output (checked in under `src/test/resources/layouts/`). A copybook change without a matching layout-fixture change fails the build.
- Text encoding is explicit: CICS assets use CCSID 037 and IMS DBDs use Cp1047. Code must never rely on `Charset.defaultCharset()`. Tests cover characters that differ between the two code pages (`[`, `]`, `^`, `!`, `|`, `¢`, `¬`).
- REDEFINES become typed views over a single `byte[]`. They are never two independent fields that can drift apart.

### 3.3 Transaction model and unit-of-work boundaries

| COBOL pattern | Java mapping (in CICS JVM server) |
|---|---|
| `EXEC CICS LINK PROGRAM(x) COMMAREA(c)` | `com.ibm.cics.server.Program.link(byte[])`. During coexistence the target may be COBOL or Java, and the caller must not need to know which. |
| `EXEC CICS RUN TRANSID ... CHILD` + `FETCH ANY` (`CRECUST` → `CRDTAGY1`-`5`) | JCICS asynchronous API (`AsyncService.runTransactionId`, `getAnyCompletedChild`) on channel `CIPCREDCHANN`, containers `CIPA`-`CIPE` |
| `EXEC CICS HANDLE ABEND LABEL(ABEND-HANDLING)` | One `try/catch (AbendException)` per program entry point that runs the same handler: `SYNCPOINT ROLLBACK`, `LINK ABNDPROC` with an `ABNDINFO` record, then re-abend |
| `EXEC CICS ABEND ABCODE('HRAC')` | `Task.getTask().abend("HRAC")`. Abend codes are part of the contract (`HBNK`, `HROL`, `HNCS`, `HRAC`, `HWPT`, `PLOP`, ...). The full list is in the Phase 0 inventory. |
| `EXEC CICS SYNCPOINT [ROLLBACK]` | `Task.getTask().commit()` / `rollback()`, or JTA `UserTransaction` under Liberty in CICS. The CICS UOW is the transaction boundary, with Db2 through the CICS-attached JDBC type 2 driver, so Db2 work stays in the CICS UOW. |
| Spring `@Transactional` | Allowed only on top of the JTA transaction manager bound to the CICS UOW. A separate local JDBC transaction is never allowed. |

For each program, Phase 0 records a **UOW table**: which Db2 tables it reads and writes, where it syncpoints or rolls back, which programs it LINKs to (and whether they share its UOW), and which abend codes it issues. Examples: `INQACC` (read only; rollback in `ABEND-HANDLING`), `UPDACC` (single `UPDATE`, no HANDLE ABEND, returns `COMM-SUCCESS='N'` on SQL error), `XFRFUN` (two account updates plus two `PROCTRAN` rows in one UOW, retries `-911` up to 5 times), `DELCUS` (deletes accounts, then the customer).

### 3.4 Counters (`HBNKACCT` and CONTROL rows)

Facts in the code: `INQACC.cbl` declares `NCS-ACC-NO-NAME` with value `HBNKACCT`, but its `READ-ACCOUNT-LAST` section calls `GET-LAST-ACCOUNT-DB2`. No program in `src/base` issues `EXEC CICS GET [D]COUNTER`. `CREACC` / `CRECUST` (`ENQ-NAMED-COUNTER`) serialize on a CICS `ENQ` (`BANKZACCT<sortcode>` / `BANKZCUST<sortcode>`, 16 bytes) and then read and update the Db2 `CONTROL` rows `<sortcode>-ACCOUNT-LAST` / `-COUNT`. The current "named counter" is therefore an ENQ-protected Db2 row, not a Named Counter Server.

Controls:
- Phase 4 replaces ENQ plus CONTROL with a Db2 `SEQUENCE` (`CREATE SEQUENCE ... NO CYCLE CACHE n ORDER`), or an `IDENTITY` column where the key is generated on insert. The starting value is seeded from `MAX(ACCOUNT_NUMBER)` / `MAX(CUSTOMER_NUMBER)` plus 1 at cutover.
- Invariants to test: no duplicates under concurrent creation (N parallel tasks in a test region), values fit in `PIC 9(8)` (accounts) / `PIC 9(10)` (customers), and the "last" lookups (`INQACC` 99999999, `INQCUST` 9999999999) still return the highest number in use.
- Gaps are acceptable (a sequence with `CACHE` can skip values) only if the business accepts them. Record that decision in the Phase 4 entry criteria. If it is not accepted, use `NO CACHE ORDER`.
- While COBOL and Java run side by side, **both** must allocate numbers from the same source. Either Java takes the same ENQ and CONTROL row, or COBOL is switched to the sequence first. Two separate allocators are never allowed.

### 3.5 Storm-drain logic

Facts in the code: `CHECK-FOR-STORM-DRAIN-DB2` is performed in 5 CICS programs (`DBCRFUN`, `INQACC`, `INQACCCU`, `INQACCS`, `XFRFUN`). It treats `SQLCODE 923` as "DB2 Connection lost" and only logs. In those programs, `ABEND-HANDLING` treats abends `AFCR`, `AFCS`, `AFCT` as storm-drain conditions: it runs `SYNCPOINT ROLLBACK` and then skips both the `ABNDPROC` logging and the re-abend. Seven other programs (`CREACC`, `CRECUST`, `DELACC`, `DELCUS`, `INQCUST`, `UPDACC`, `UPDCUST`) declare `STORM-DRAIN-CONDITION` but never perform the check.

Controls:
- Port this once as a **resilience component** (`StormDrainPolicy`) with a classification table mapping SQLCODE or abend code to a category (transient, connection-lost, or fatal) and an action (log only, retry with backoff, fail fast). Do not port the COBOL paragraph line by line into each program.
- The default policy reproduces current behavior exactly: log and continue for 923, abend for everything else. It also reproduces `XFRFUN`'s `-911` retry (5 times, 1 second `DELAY`). Any change of behavior, such as a real retry, a circuit breaker, or CICS workload storm-drain integration, is a separate, flagged change after parity.
- Unit-test the classification table. Use fault injection (a JDBC wrapper returning SQLCODE 923, -911, -904) in the shadow environment.

### 3.6 IMS / DL/I fidelity

- Every IMS COBOL program has the same shape: `DLITCBL` entry, a `GU` loop on the I/O PCB, segment calls (`GU`/`GHU`/`GHN`/`REPL`/`ISRT`) on the DB PCB, and an `ISRT` reply. Java ports use `com.ibm.ims.dli` (`Application`, `MessageQueue`, `PSB`, `PCB`, `SSAList`, `Path`) from the existing `udb` dependency, inside the **existing** `src/base/ims/java` project. Do not create a second IMS Java codebase.
- PSB/DBD assembler sources stay authoritative. Segment layouts are verified with the golden-layout tests from section 3.2 (for example, the customer segment is 279 bytes). Java ports run under new PSBs with the same content and new program names during parallel run (for MPPs the PSB name equals the PGM name).
- `IBTRAN` contract: it calls `InsertHist` (Db2 `IMSBANK.HISTORY`) through JNI **before** the DL/I `ISRT` into IMS `HISTORY`. `InsertHist` swallows all exceptions and there is no `ROLB`, so the two history stores can diverge without any error. The port must keep this order and error behavior until parity is signed off. Making both writes atomic (one RRS UOW, `ROLB` on failure) is a tracked change after parity.
- Because the IMS JVM classpath is a single jar, all new Java code is bundled into `nazare-ims-jmp`, or the classpath template is extended deliberately.

### 3.7 Integration surface (z/OS Connect and frontend)

- `openapi.yaml`, the generated JSON schemas, and each asset's `.dai` mapping (field order, types, lengths, CCSID 037) are frozen in Phase 0 as contract fixtures.
- Java programs receive the **same COMMAREA bytes** (CICS) or **same IMS message** (IMS) that z/OS Connect sends today. z/OS Connect assets are not changed during Phases 2-5, so the API and frontend do not see the switch.
- Known differences between the spec and the implementation (for example, `.dai` record length for `CRECUST` 403 versus COMMAREA 399, and `INQACCS` 1967 versus 1970; IMS account responses returning arrays and `accountType: CHECKING`, which is not in the enum) are recorded as-is in the contract fixtures. They are fixed only through an explicit API version change.
- Contract tests: for every operation, replay recorded HTTP requests against the API with a COBOL backend and with a Java backend, and compare the JSON responses after normalization (field order, `BigDecimal` scale preserved, so `0.30` must not become `0.3`).

### 3.8 Security plumbing

- **Staying on z/OS (recommended):** keep Liberty `JCERACFKS` at `safkeyring://...`, `addcert.sh` and `gencert-eku.sh` unchanged. Java programs run under the same RACF-authenticated CICS / IMS task user, so resource-level security (transaction, program, Db2 plan / package, IMS PSB) still applies. The new CICS JVM server profile and IMS JVM options must not add a JKS/PKCS12 keystore containing private keys.
- **Moving off z/OS (not recommended in this plan):** requires a replacement PKI (enterprise CA, secret manager, cert rotation), a new identity mapping from RACF user IDs to the new platform, and new Db2 / IMS connectivity (DRDA / IMS Connect with TLS). Treat this as its own project with its own threat model.

## 4. Testing strategy (applies to every phase)

1. **Golden-record replay.** Each program has a directory of cases in this form: request bytes (COMMAREA or IMS message), initial database state (Db2 rows, IMS segments), response bytes, final database state diff, abend code (if any), and `ABNDINFO` record (if written). The harness runs the Java program against the same initial state and requires byte-identical output and state diff. Known non-determinism is masked by an explicit, reviewed mask: timestamps, `INQCUST 0000000000` (random customer), `CRDTAGYn` simulated credit scores, and `PROCTRAN` transaction-ID time fields.
2. **Shadow traffic.** In a test region, a capture shim (a COBOL wrapper `LINK`ed in place of the target, or a CICS global user exit) copies live COMMAREAs / messages to the Java version running on a **separate database copy**, or read-only for inquiry programs, and records any differences. Shadowing a write path never writes to the system-of-record database.
3. **Layout and numeric tests** (sections 3.1 and 3.2) run on every build off-platform. The Java build must run on Linux/macOS with no z/OS dependency: H2 in `MODE=DB2`, plus a fake DL/I interface.
4. **API contract tests** (section 3.7) and the existing `tests/*.sh` smoke tests run after every cutover.
5. **Coverage baseline.** Before Phase 0 there are no zUnit test cases and no Java/frontend unit tests, and `tests/` covers only 4 of 17 operations (all GETs). Phase 0 must bring golden-case coverage to every operation and every abend code a program can issue.

Exit gate for every program cutover: 100% of golden cases pass, at least N days (recommended 5 business days) of shadow traffic with zero unexplained differences, all relevant section 3 controls checked, and a rehearsed rollback.

## 5. Rollback model

- The COBOL/PL/I sources, the DBB `Cobol`/`PLI` tasks and the load modules stay in `dbb-app.yaml` and in the Wazi Deploy manifests until Phase 6.
- Cutover is a definition switch, never a source deletion:
  - CICS: `PROGRAM(x)` changes from the COBOL load module to `JVMSERVER(BANKZJVM) JVMCLASS(...)` (or the Liberty `@CICSProgram` entry point). Rollback means reinstalling the previous definition and running `NEWCOPY`.
  - IMS: the TRAN points to a new PGM/PSB (Java). Rollback means pointing the TRAN back to the COBOL/PL/I PGM (`UPD TRAN ... SET(PGM(...))`).
  - Batch: the JCL `EXEC` changes from `IKJEFT01`/`DSN RUN` to the JZOS launcher (`JVMLDM*`). Rollback means restoring the previous JCL member.
  - z/OS Connect: no change (the same asset invokes whichever program is installed).
- Database changes are additive and backward compatible: a new `SEQUENCE` while `CONTROL` rows are still updated, and no column drops. They stay that way until Phase 6.
- Each phase's runbook lists the exact rollback commands and the time it takes, rehearsed in the test region.

## 6. Phases

### Phase 0: Characterization and contract freeze

**Scope:** every program in `src/base/cics/cobol/`, `src/base/ims/cobol/`, `src/base/ims/pli/`, `src/base/batch/pli/`; every copybook in `src/base/cics/copy/` and `src/base/ims/copy/`; `src/base/ims/PSB/`, `src/base/ims/DBD/`; `src/api/src/main/api/openapi.yaml` and `src/api/src/main/zosAssets/**`.

**Work:**
- Inventory: for each program, its entry interface (COMMAREA copybook and length, IMS message layout, DDs), callers and callees (`LINK`, `RUN TRANSID`, JNI), Db2 tables, IMS PCBs, abend codes, UOW table (section 3.3), and its z/OS Connect operation if any.
- Layout fixtures: compiler `MAP` offsets for every copybook and IMS segment, checked in as golden-layout files.
- Capture: a capture shim (COBOL wrapper program or CICS exit for CICS; message-queue capture for IMS; DD snapshots for batch) records request/response pairs and before/after database state in a test region seeded by `BANKDATA` and the IMS `LoadData`.
- A replay harness that runs off-platform against Java (H2 `MODE=DB2` + fake DL/I) and on z/OS against the real region.
- Freeze `openapi.yaml`, generated schemas and `.dai` files: a checksum file that CI checks, with code-owner review required for changes.
- Record known defects as **characterized behavior**, not to be fixed during migration. For example: `XFRFUN` never checks the available balance; `DBCRFUN` allows overdrafts for teller debits; `INQACC` returns `SUCCESS='Y'` when the account is not found; `IBTRAN` withdrawals have no balance check; `BNKSTMT` counts only `CR`/`CRD` as credits; `IBLOGOUT`/`IBLOGIN1` report success when `REPL` fails.

**Entry:** test region provisioned with this repo's `.setup` scripts; plan approved.
**Exit:** every program has at least one golden case per success path and per abend/error code; layout tests are green for every copybook; contract checksums are in CI; the replay harness passes when it replays COBOL captures against COBOL (self-consistency).
**Test strategy:** self-replay of captures (to detect non-determinism and build the mask list); layout fixtures compared with compiler listings.
**Rollback:** none needed. Nothing in production changes. The capture shim is installed only in test regions.

### Phase 1: Target runtime and Java scaffolding

**Decision (recommended):** stay on z/OS.
- CICS: an application JVM server (`BANKZJVM`, new JVM profile next to `EYUSMSSJ.jvmprofile` in `.setup/zconfig/cics-region.yaml`), running OSGi JCICS or Liberty-in-CICS. Liberty is preferred if JTA/CDI are wanted. Db2 access uses the CICS-attached JDBC type 2 driver so it shares the CICS UOW.
- IMS: JMP/JBP regions using the existing `dfsjvmpr.props.j2` and the `nazare-ims-jmp` jar.
- Batch: JZOS batch launcher.

**Scope:** new Gradle projects under `src/base/` (for example `src/base/java/` with `copybook-bridge`, `parity-harness`, `cics-programs`, `batch`), next to the existing `src/base/ims/java/`. A new DBB task (same pattern as `ImsJavaBuilder`) builds the CICS/batch jars with deploy types for the CICS bundle directory and the batch USS path. Wazi Deploy manifests are updated to ship the jars. `zapp.yaml` is unchanged except for an optional Java property group.

**Work:** shared libraries (`CobolDecimal`/`PicSpec`, `PackedDecimal`, `ZonedDecimal`, CCSID 037/1047 codecs, `StormDrainPolicy`, `AbendHandler` that links `ABNDPROC` with an `ABNDINFO` record); ArchUnit rule banning floating-point money; off-platform build with JDK 21 (matching `src/base/ims/java`), Gradle, H2, JUnit 5; CI on Linux.

**Entry:** Phase 0 exit.
**Exit:** an empty "hello" Java program is deployed through DBB and Wazi Deploy into the test CICS region and IMS region, and a JZOS batch job runs from JCL; the shared libraries pass the numeric and layout tests from Phase 0; COBOL build output is unchanged (DBB build report diff shows no change to COBOL/PL/I outputs).
**Test strategy:** unit and property tests for the shared libraries; a deploy smoke test; a check that `dbb build` still produces identical COBOL load modules.
**Rollback:** remove the new DBB task and the JVM server definition. COBOL is unaffected.

### Phase 2: IMS first

Why IMS goes first: `src/base/ims/java` and a working COBOL-to-Java bridge already exist, the transactions are small (one GU loop each), and the IMS API surface is 4 z/OS Connect assets.

**Scope (in order):** `IBACSUM`, `IBGCUDAT` (read only), then `IBSCUDAT`, `IBLOGOUT`, `IBLOGIN.pli` (and the undeployed `IBLOGIN1.cbl`: decide whether to retire it or port it), then `IBTRAN` last. DL/I batch loaders `LOADACCT`, `LOADCUSA`, `LOADCUST`, `LOADHIST`, `LOADTSTA` become JBP or JZOS jobs using PSB `IBLOAD` (PROCOPT=L). PSB/DBD sources stay as they are, plus new PSBs for parallel-run program names.

**Work:** port each program into `src/base/ims/java` over a thin `Dli` interface (a real implementation on `udb`, and a fake for off-platform tests). Keep the `IBTRAN` → `InsertHist` contract (direct ByteBuffer layout, order of Db2 then IMS writes, exceptions swallowed) until parity. After `IBTRAN` is ported, the JNI bridge is no longer needed for that path, because Java calls `InsertHist` directly. Keep `IBTRAN.cbl` and its `LP(32),JAVAIOP(JAVA64)` build until Phase 6.

**Entry:** Phase 1 exit; IMS golden cases complete.
**Exit (per program):** golden replay is 100%; shadow run on the parallel TRAN shows zero unexplained differences for the agreed window; IMS `HISTORY` and Db2 `IMSBANK.HISTORY` reconciliation shows no new divergence (for `IBTRAN`); API contract tests for `/ims/**` pass.
**Test strategy:** golden replay against the fake DL/I and the real IMS test region; shadow traffic through duplicated input messages to a parallel TRAN with its own databases; reconciliation queries.
**Rollback:** `UPD TRAN` back to the COBOL/PL/I PGM. Database layouts are unchanged.

### Phase 3: CICS read paths

**Scope:** `INQACC`, `INQCUST`, `INQACCCU`, `INQACCS`, `INQTRAND`, `INQTRANL`, `GETSCODE`, `GETCOMPY` (these two have no callers today: port them or retire them), using copybooks `INQACC`, `INQCUSTZ`, `INQACCCU`, `INQACCS`, `INQTRAND`, `INQTRANL`, `ACCDB2`, `CUSTDB2`, `PROCDB2`, `ABNDINFO`.

**Work:** JCICS programs with the identical COMMAREA (byte-for-byte, same length). Keep the special keys (`99999999` / `9999999999` mean "last", `0000000000` means random customer), the not-found semantics, `HANDLE ABEND` → `ABNDPROC` → re-abend with the same codes (`HRAC`, `HNCS`, ...), and storm-drain classification through `StormDrainPolicy`. `LINK` callers (`CREACC`, `DELCUS` → `INQCUST`/`INQACCCU`) keep calling by program name, so they work against either implementation.

**Entry:** Phase 2 complete, or at least `IBACSUM`/`IBGCUDAT` cut over (this proves the pipeline); CICS JVM server is in production.
**Exit (per program):** golden replay is 100%; read-only shadow (the capture shim `LINK`s to both versions and compares COMMAREAs) shows zero unexplained differences over the agreed window; p99 latency within the agreed budget of COBOL; API contract tests for the matching z/OS Connect operations pass.
**Test strategy:** golden replay; read-only dual-run in production-like regions is safe because these programs do not write; fault injection for SQLCODE 923 / -904.
**Rollback:** reinstall the COBOL `PROGRAM` definition and run `NEWCOPY`.

### Phase 4: CICS write paths

**Scope (in order of increasing risk):** `UPDCUST`, `UPDACC`, `DELACC`, `DELCUS`, `CREACC`, `CRECUST` with `CRDTAGY1`-`5`, `DBCRFUN`, `XFRFUN`, `ABNDPROC` (VSAM `ABNDFILE`). Copybooks: `UPDACC`, `UPDCUST`, `DELACC`, `DELCUS`, `CREACC`, `CRECUST`, `PAYDBCR`, `XFRFUN`, `CONTDB2`, `PROCTRAN`, `ACCTCTRL`, `CUSTCTRL`.

**Work:**
- Counters: replace ENQ plus `CONTROL` with a Db2 `SEQUENCE`/`IDENTITY` as described in section 3.4, including the rule that both implementations must share one allocator.
- Abend handling: `AbendHandler` reproduces `SYNCPOINT ROLLBACK`, `ABNDPROC` logging and the original abend codes.
- Storm drain and retry: `StormDrainPolicy`, including `XFRFUN`'s `-911` retry (5 times, 1 second delay) and its update ordering (lower account number first, to avoid deadlocks).
- `CRECUST` asynchronous credit check: JCICS async API on the same channel and containers. Note that `CRECUST` PUTs a 399-byte record while `CRDTAGYn` reads it as a 397-byte `CUSTOMER` layout. Keep both lengths.
- UOW parity: each program's UOW table from Phase 0 becomes an integration test that asserts the commit and rollback points, for example rolling back `XFRFUN` after the first update.

**Entry:** Phase 3 complete; the business has decided whether gaps in account and customer numbers are acceptable; a separate shadow database copy exists for write shadowing.
**Exit (per program):** golden replay is 100% including final database state; write shadow (the capture shim replays into Java against a shadow database, then the before/after states are diffed) shows zero unexplained differences; concurrency test (N parallel `CREACC`/`XFRFUN`) shows no duplicate keys, no lost updates beyond current behavior, and no deadlocks beyond current behavior; rollback rehearsed.
**Test strategy:** golden replay with state diff; write shadowing to an isolated database; concurrency and fault-injection tests; reconciliation of `PROCTRAN` row counts and types (`OCC/ODC`, `OCA/ODA`, `CRE/DEB/PCR/PDR`, `TFR`) between COBOL and Java runs.
**Rollback:** reinstall the COBOL `PROGRAM` definition. If the sequence is already in use, COBOL keeps working because `CONTROL` rows are still updated, or because COBOL was switched to the sequence first (section 3.4).

### Phase 5: Batch

**Scope:** `src/base/batch/pli/BNKSTMT.pli`, `src/base/batch/jcl/BNKSTMT.jcl`; also `BANKDATA.cbl` (the data generator) if it remains in use.

**Work:** a Java implementation run by the JZOS batch launcher. The JCL wrapper stays (`DATECARD`, `SORTCODE`, `SYSPRINT` DDs are read through `ZFile`/`DDs` with the same record formats). Db2 is accessed through JDBC type 2 with an RRSAF attach, or type 4 if preferred. The plan/package binding `BANKZPLN` is replaced by a JDBC package collection. Keep report bytes identical, including the characterized defects: credits are only `CR`/`CRD`, February always has 28 days, `DEC(10,2)` host variables, and null-indicator columns keep the previous row's value.

**Entry:** Phase 1 exit. This phase can run in parallel with Phases 3-4, since it is independent of CICS.
**Exit:** report output is byte-identical (after the timestamp mask) for at least 3 monthly cycles of captured data plus edge-case datasets (empty sort code, accounts with no transactions, leap-year February); job elapsed time and CPU within budget.
**Test strategy:** golden report comparison; a parallel run where both jobs run against the same Db2 snapshot and the reports are diffed.
**Rollback:** restore the previous JCL member (`IKJEFT01` / `DSN RUN PROGRAM(BNKSTMT)`). The PL/I load module is still deployed.

### Phase 6: Decommission

**Entry:** every program has run in production on Java through at least one full business cycle (month-end, including `BNKSTMT`) with no rollback; the business has approved the change.

**Work:**
- BMS/3270: retire `src/base/cics/bms/*`, `BNKMENU`, `BNK1*` only if usage logs show no 3270 traffic. **First**, either expose `XFRFUN`, `UPDACC`, `CREACC`, `DELACC` through z/OS Connect and implement the stubs in `src/frontend/js/api.js`, or formally retire them. Today the 3270 screens are their only callers.
- `dbb-app.yaml`: remove the `Cobol` and `PLI` tasks (including the `IBTRAN` `LP(32),JAVAIOP(JAVA64)` compile and link-edit stanzas), the `IMSLOAD`/`CICSLOAD` deploy types, and the `impactQueryPatterns` for `cbl`/`lnk`. Keep `Assembler` for PSB/DBD, `ImsJavaBuilder`, the new Java tasks, `zOSConnect`, `VanillaFrontend` and `ServerXmlPackager`.
- `zapp.yaml`: remove the COBOL `remote-libraries` property group (`BANKZ.DBB.BMS.COPY`, `SDFHCOB`) and update the `taz-test` / `zcodescan` profiles.
- Database: drop the `CONTROL` counter rows and the ENQ names once no COBOL allocator remains.
- Remove the COBOL/PL/I sources and copybooks. Keep the golden-layout fixtures and golden cases as permanent regression tests. Update `README.md` and `docs/docs/architecture/*`.

**Exit:** a full DBB build and Wazi Deploy with no COBOL/PL/I; all golden, contract and smoke tests green; documentation updated.
**Test strategy:** full regression of golden replay and API contract tests, plus `tests/run-all.sh` on the new build.
**Rollback:** this is the first irreversible step. Before it, tag the last release that includes COBOL (load modules, DBB build report, Wazi Deploy package) and keep that package deployable for an agreed retention period.

## 7. Phase summary

| Phase | Programs | Switch mechanism | Rollback | Gate |
|---|---|---|---|---|
| 0 | none (capture only) | n/a | n/a | golden cases + layout fixtures + contract checksums |
| 1 | none (scaffolding) | new JVM server / DBB task | remove additions | hello-world deployed on CICS/IMS/JZOS; COBOL build unchanged |
| 2 | IMS MPPs, loaders | `UPD TRAN SET(PGM)` | point TRAN back | replay 100%, shadow clean, history reconciliation |
| 3 | CICS inquiries | `PROGRAM` → `JVMSERVER` | reinstall COBOL definition | replay 100%, read-only dual run clean |
| 4 | CICS writes, counters, abend, storm drain | `PROGRAM` → `JVMSERVER`; Db2 sequence | reinstall COBOL definition; `CONTROL` still maintained | replay + state diff, write shadow, concurrency |
| 5 | `BNKSTMT` | JCL `EXEC` | previous JCL | byte-identical report for 3 cycles |
| 6 | delete COBOL/PL/I, BMS | build config | redeploy tagged COBOL release | full regression |

## 8. Implementation note

If this plan is approved for implementation, start with **Phase 0 and Phase 1 only**: the characterization harness, layout fixtures, contract checksums, and the Java scaffolding under `src/base/`. Do not port any program in the same change. Later phases depend on golden data captured on a real z/OS test region, which cannot be produced off-platform.

## 9. Out of scope / open decisions

- Moving the workload off z/OS (containers, distributed Db2/IMS access): requires the re-architecture described in section 3.8 and a separate plan.
- Fixing characterized defects (section 6, Phase 0): each fix is a separate change after cutover, with its own golden-case update.
- Whether to keep 3270 access (Phase 6) and whether gaps in account and customer numbers are acceptable (Phase 4): these are business decisions.
