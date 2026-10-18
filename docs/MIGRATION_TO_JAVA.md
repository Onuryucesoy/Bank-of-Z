# Bank of Z: COBOL/PL/I to Java Migration Plan

This is a phased plan for moving the Bank of Z z/OS application (CICS COBOL, IMS COBOL/PL/I, batch PL/I) to Java without breaking the z/OS Connect API contract or the JavaScript frontend. Each phase lists its goal, scope, and exit criteria. The [Verified findings](#verified-findings-that-shape-the-plan) section lists things in the source that differ from the usual assumptions about this app; read it before scheduling Phase 3.

## Current state

### Inventory by layer

| Layer | Path | Contents | Approx. lines |
|---|---|---|---|
| CICS COBOL programs | `src/base/cics/cobol/` | 32 programs (`.cbl`) | 28,400 |
| BMS maps | `src/base/cics/bms/` | 10 map sources; 9 mapsets used by `BNK*` programs | 1,060 |
| CICS copybooks | `src/base/cics/copy/` | 42 copybooks (`.cpy`) | 1,930 |
| IMS COBOL | `src/base/ims/cobol/` | 11 programs: 6 online (`IBACSUM`, `IBGCUDAT`, `IBSCUDAT`, `IBTRAN`, `IBLOGIN1`, `IBLOGOUT`) and 5 loaders (`LOAD*`) | 2,860 |
| IMS PL/I | `src/base/ims/pli/` | 1 program (`IBLOGIN.pli`) | 350 |
| IMS Java (JMP) | `src/base/ims/java/` | Gradle project, 8 classes in `nazare.jmp.{controller,history,message}` | 740 |
| IMS control blocks | `src/base/ims/DBD/`, `src/base/ims/PSB/` | 9 DBDs, 8 PSBs | n/a |
| IMS copybooks / seed data | `src/base/ims/copy/`, `src/base/ims/LoadData/` | 4 copybooks, 12 data/CSV files | n/a |
| Batch | `src/base/batch/pli/`, `src/base/batch/jcl/` | 1 PL/I program (`BNKSTMT.pli`) + 1 JCL (`BNKSTMT.jcl`) | 940 |
| API contract | `src/api/src/main/api/openapi.yaml` | 17 operations on 14 paths; 14 z/OS Connect assets (10 CICS, 4 IMS) under `src/api/src/main/zosAssets/`; built by `src/api/build.gradle` with `com.ibm.zosconnect.gradle` 1.6.2 | n/a |
| Frontend | `src/frontend/` | Vanilla JS/HTML (Carbon web components), `js/api.js` client; routes `C…` customer IDs to CICS and `I…` to IMS | 4,200 |
| Build | `dbb-app.yaml` | DBB tasks: `Cobol`, `PLI`, `Assembler`, `ImsJavaBuilder`, `zOSConnect`, `VanillaFrontend`, `ServerXmlPackager` | n/a |

### API operations and the programs behind them

| Operation | Method and path | z/OS Connect asset / program |
|---|---|---|
| `createCustomer` | `POST /customers` | `CRECUST` |
| `getCustomer` / `updateCustomer` / `deleteCustomer` | `GET`/`PUT`/`DELETE /customers/{customerId}` | `INQCUST` / `UPDCUST` / `DELCUS` |
| `getCustomerAccounts` | `GET /customers/{customerId}/accounts` | `INQACCCU` |
| `getAccounts` | `GET /accounts` | `INQACCS` |
| `getAccount`, `getAccountBalances` | `GET /accounts/{accountId}[/balances]` | `INQACC` |
| `depositToAccount` | `POST /accounts/{accountId}/deposit` | `DBCRFUN` |
| `getAccountTransactions` | `GET /accounts/{accountId}/transactions` | `INQTRANL` |
| `getTransaction` | `GET /accounts/{accountId}/transactions/{transactionId}` | `INQTRAND` |
| `getIMSCustomer` / `updateIMSCustomer` | `GET`/`PUT /ims/customers/{customerId}` | `IBGCUDAT` / `IBSCUDAT` |
| `getIMSCustomerAccounts`, `getIMSAccount`, `getIMSAccountBalances` | `GET /ims/...` | `IBACSUM` |
| `depositToIMSAccount` | `POST /ims/accounts/{customerId}/{accountId}/deposit` | `IBTRAN` (calls Java `nazare.jmp.controller.InsertHist` over JNI) |

Mapping source: `src/api/src/main/operations/*/*/operation.yaml` (`zasset:`).

### Data stores

- **Db2 (schema `BANKZ`)**: tables `CUSTOMER`, `ACCOUNT`, `PROCTRAN`, `CONTROL`. All CICS business data lives here (DDL in `.setup/jcl/cics/Db2-create.j2`).
- **Db2 (`IMSBANK.HISTORY`)**: written by `InsertHist` and read by `TransactionService` in the IMS Java project.
- **IMS DL/I**: 9 databases (`ACCOUNT`, `ACCTYPE`, `CUSTACCS`, `CUSTOMER`, `CUSTTYPE`, `HISTORY`, `TSTAT`, `TSTATTYP`, `TTYPE`), HDAM/OSAM.
- **VSAM**: only `ABNDFILE`, written by `ABNDPROC` (abend log). No business program uses VSAM.

## Verified findings that shape the plan

These come from the current source on `main`. Each one changes scope or adds a decision.

1. **No static CALLs between application programs.** Programs reach each other through `EXEC CICS LINK PROGRAM(...)` and, in one case, `EXEC CICS RUN TRANSID` (`CRECUST` → `CRDTAGY1`–`5`). The only `CALL`s are to LE services (`CEEDATM`, `CEEGMT` in `BANKDATA`) and to IMS (`CBLTDLI`, `PLITDLI`). Also, the static-call pattern in `dbb-app.yaml` `impactQueryPatterns` is `${APP_DIR_NAME}/src/**/.cbl`. It has no `*`, so it matches nothing. DBB impact analysis therefore can't be used to find call dependencies. Phase 0 has to trace `LINK`/`RUN TRANSID` targets directly (table below).
2. **`CREACC`, `UPDACC`, `DELACC`, and `XFRFUN` have no API operation.** Only the 3270 screens call them: `BNK1CAC`, `BNK1UAC`, `BNK1DAC`, and `BNK1TFN`. In `src/frontend/js/api.js`, `createAccount`, `updateAccount`, and `deleteAccount` are stubs that throw "not supported", and the frontend has no transfer call. **The JS frontend does not yet replace these BMS flows.** If BMS were retired as-is, these four programs would have no caller. Phase 3 has to extend the contract before BMS goes away.
3. **`GETSCODE` and `GETCOMPY` have no callers in the repo and no data access.** They return constants (sort code from `SORTCODE.cpy`, company name). They are defined in the CICS CSD (`.setup/zconfig/bank-of-z-definitions.yaml`) and nothing else.
4. **Ten copybooks are orphans** (referenced by no program and no z/OS Connect asset): `BANKMAP`, `BNK1DDM`, `CUSTMAP`, `CONTROLI`, `NEWACCNO`, `NEWCUSNO`, `STCUSTNO`, `PROCISRT`, `RESPSTR`, `WAZI`. They should not get Java equivalents.
5. **Some IMS programs may not be live.** `IBLOGIN` (PL/I) and `IBLOGOUT` are defined as IMS transactions in `.setup/jcl/ims/Ims-spoc.jcl.j2` but have no API operation. `IBLOGIN1.cbl` (COBOL) is not defined there at all. `IMSBankHistory.java` is referenced nowhere. `QueryTransaction` (`IBGHIST`) is configured as a JMP program but has no z/OS Connect asset.
6. **Configuration drift.** `BNKSTMT.jcl` passes `SORTCODE 123456`, but `BANKDATA` and every CICS program use `987654` from `SORTCODE.cpy`. `BNK1B2M.bms` reuses mapset name `BNK1TFM`. The CSD defines `BNK1B2B`, which has no source.
7. **History is written twice on the IMS side.** `IBTRAN` writes the Db2 copy (`IMSBANK.HISTORY`, via `InsertHist`) before the DL/I `HISTORY` segment. `InsertHist` swallows exceptions, so the two copies can diverge without any error.

---

## Phase 0: Assessment and inventory

**Goal:** Produce a complete, checked map of programs, data, and dependencies, so that the scope of every later phase is known.

### Program catalog by domain (CICS, 32 programs)

| Domain | Programs | Data access | Callers / entry point |
|---|---|---|---|
| Customer (5) | `CRECUST`, `INQCUST`, `UPDCUST`, `DELCUS`, `INQACCCU` | Db2 `CUSTOMER`, `CONTROL`, `PROCTRAN`; `INQACCCU` reads `ACCOUNT` | API; `BNK1CCS`, `BNK1DCS`, `BNK1CCA` |
| Account (6) | `CREACC`, `INQACC`, `UPDACC`, `DELACC`, `INQACCS`, `GETSCODE` | Db2 `ACCOUNT`, `CONTROL`, `PROCTRAN`; `GETSCODE` none | API (`INQACC`, `INQACCS`); BMS only for `CREACC`/`UPDACC`/`DELACC` |
| Transactions (4) | `INQTRANL`, `INQTRAND`, `XFRFUN`, `DBCRFUN` | Db2 `PROCTRAN`; `XFRFUN`/`DBCRFUN` also update `ACCOUNT` | API (`INQTRANL`, `INQTRAND`, `DBCRFUN`); `XFRFUN` only from `BNK1TFN` |
| Credit agency (5) | `CRDTAGY1`–`CRDTAGY5` | None (score simulated with `RANDOM` + `DELAY`) | `CRECUST` via `RUN TRANSID` on channel `CIPCREDCHANN` |
| UI / menu (9) | `BNKMENU`, `BNK1CAC`, `BNK1CCA`, `BNK1CCS`, `BNK1CRA`, `BNK1DAC`, `BNK1DCS`, `BNK1TFN`, `BNK1UAC`, plus BMS maps `BNK1ACC`, `BNK1CAM`, `BNK1CCM`, `BNK1CDM`, `BNK1DAM`, `BNK1DCM`, `BNK1MAI`, `BNK1TFM`, `BNK1UAM`, `BNK1B2M` | None directly | 3270 terminal |
| Infrastructure (3) | `BANKDATA`, `ABNDPROC`, `GETCOMPY` | `BANKDATA`: Db2 `ACCOUNT`/`CUSTOMER`/`CONTROL` (seed load); `ABNDPROC`: VSAM `ABNDFILE`; `GETCOMPY`: none | Batch seed; every program's abend handler; CSD only |

The UI / menu row counts 9 programs; 5 + 6 + 4 + 5 + 9 + 3 = 32.

IMS and batch: online `IBACSUM`, `IBGCUDAT`, `IBSCUDAT`, `IBTRAN` (API-backed); `IBLOGIN` (PL/I), `IBLOGIN1`, and `IBLOGOUT` (not API-backed); loaders `LOADACCT`, `LOADCUSA`, `LOADCUST`, `LOADHIST`, `LOADTSTA`; batch `BNKSTMT` (reads `CUSTOMER`, `ACCOUNT`, `PROCTRAN`).

### Runtime call graph (`EXEC CICS LINK` / `RUN TRANSID`)

| Caller | Targets |
|---|---|
| `CREACC` | `INQCUST`, `INQACCCU` |
| `DELCUS` | `INQCUST`, `INQACCCU`, `DELACC` |
| `INQACCCU` | `INQCUST` |
| `CRECUST` | `CRDTAGY1`–`CRDTAGY5` (async, `RUN TRANSID` + `FETCH`) |
| `BNK1CAC` / `BNK1CCS` / `BNK1CRA` / `BNK1TFN` | `CREACC` / `CRECUST` / `DBCRFUN` / `XFRFUN` |
| `BNK1CCA` / `BNK1DAC` / `BNK1UAC` / `BNK1DCS` | `INQACCCU` / `INQACC`+`DELACC` / `INQACC`+`UPDACC` / `INQCUST`+`UPDCUST`+`DELCUS` |
| All business and UI programs | `ABNDPROC` (abend handler) |

Because of the ordering here, `INQCUST` and `INQACCCU` have to be available in Java (Phase 2) before `CREACC` and `DELCUS` move (Phase 3).

### Copybook to Java mapping (`src/base/cics/copy/`)

| Category | Copybooks | Java target |
|---|---|---|
| Db2 host-variable declarations (`EXEC SQL INCLUDE`) | `ACCDB2`, `CUSTDB2`, `PROCDB2`, `CONTDB2` | JPA entities / JDBC row records `Account`, `Customer`, `ProcTran`, `Control`. Types come from the DDL, not the copybook (e.g. `ACCDB2` says `DECIMAL(10,2)`, DDL says `DECIMAL(12,2)`) |
| Domain record layouts | `ACCOUNT`, `CUSTOMER`, `PROCTRAN` | Domain records (`record Account(...)`, etc.) |
| COMMAREA interfaces | `CRECUST`, `CREACC`, `INQACC`, `INQACCCU`, `INQACCS`, `INQCUSTZ`, `INQTRAND`, `INQTRANL`, `UPDACC`, `UPDCUST`, `DELACC`, `DELCUS`, `PAYDBCR`, `XFRFUN`, `GETSCODE`, `GETCOMPY` | Request/response DTO records per service |
| z/OS Connect provider layouts | `INQACCZ`, `INQACCCZ`, `INQACCSZ`, `PAYDBCRZ`, `DELACCZ` (plus `INQCUSTZ`, `CRECUST`, `DELCUS`, `UPDCUST`, `INQTRAND`, `INQTRANL` used directly by assets) | Reference only, for checking JSON field parity against `openapi.yaml` |
| Control records | `ACCTCTRL`, `CUSTCTRL` | ID-generation service backed by Db2 `CONTROL` (or a Db2 sequence) |
| Constants / errors | `SORTCODE`, `ABNDINFO` | `application.yaml` property; structured error/audit event |
| Orphans (no references) | `BANKMAP`, `BNK1DDM`, `CUSTMAP`, `CONTROLI`, `NEWACCNO`, `NEWCUSNO`, `STCUSTNO`, `PROCISRT`, `RESPSTR`, `WAZI` | None. Delete in Phase 5 |

IMS copybooks `IBGHIST`, `IBSHIST`, `IBTRAN`, and `JNI` (in `src/base/ims/copy/`) map to the existing `nazare.jmp.message` classes and IMS JDBC DTOs.

### Data-store usage per program

Taken from the catalog above. In short: every CICS business program is Db2-only, `ABNDPROC` is the only VSAM user, and IMS online programs use DL/I through PSBs named after the program (`IBACSUM`, `IBGCUDAT`, `IBSCUDAT`, `IBTRAN`, `IBLOGIN`, `IBLOGOUT`; `IB` and `IBLOAD` cover all 9 DBDs for loaders).

### Exit criteria

- Catalog and call graph checked against the source and the CSD (`.setup/zconfig/bank-of-z-definitions.yaml`). Every program has an owner domain and a target phase.
- The `impactQueryPatterns` static-call glob is fixed to `**/*.cbl`, or the team records that DBB impact analysis can't be used for call tracing.
- Copybook mapping signed off, including the DDL-vs-copybook type differences.
- Each item in [Verified findings](#verified-findings-that-shape-the-plan) has an owner and a decision (keep, migrate, or retire).
- Golden request/response fixtures are captured from the live z/OS Connect API for every operation in `openapi.yaml`.

---

## Phase 1: Interoperability foundation

**Goal:** Run a Java service that implements the existing `openapi.yaml` contract next to z/OS Connect, so that traffic can move one operation at a time with no frontend change.

### Scope

- **Consolidate `src/base/ims/java/` first.** It already contains Java (JMP programs `InsertHist` and `QueryTransaction`, plus Db2 access to `IMSBANK.HISTORY`). Pull the reusable pieces (`TransactionService`, `TransactionDetail`, message classes) into a shared library. Remove or document `IMSBankHistory` (unreferenced). Make `InsertHist` report failures instead of swallowing them. Keep the JMP entry points thin so that `ImsJavaBuilder` in `dbb-app.yaml` still builds them.
- **New Spring Boot module** (for example `src/java-service/`) with:
  - Controllers generated from `src/api/src/main/api/openapi.yaml` (OpenAPI Generator, `interfaceOnly`), so the contract stays the single source of truth.
  - A backend switch per operation: *delegate* (call the existing COBOL program) or *native* (Java implementation).
  - Db2 access through JDBC (JCC type 2 on z/OS, type 4 off-platform).
- **Deployment on z/OS alongside z/OS Connect.** Choose between:
  - Liberty in a CICS JVM server: COBOL is called with JCICS `Program.link()` using the same COMMAREA layouts, and Db2 is reached through the CICS Db2 attachment.
  - Standalone Liberty, or a Spring Boot jar started under JZOS: COBOL is reached through CICS TG/IPIC or the existing z/OS Connect endpoints, and Db2 through JCC type 2.
  - (`ABNDFILE`, the only VSAM file, is reachable from JZOS `ZFile` if needed.)
- **Gateway routing.** Put a routing layer (z/OS Connect API requester, Liberty proxy, or an external API gateway) in front of both backends with a per-path route table. The frontend's `baseUrl` in `src/frontend/config.js` stays the same.
- **Build.** Add a Gradle/Maven build for the new module to CI, outside the DBB `Cobol`/`PLI` tasks. Don't touch `zOSConnect` or `VanillaFrontend` yet.

### Exit criteria

- The Java service serves all 17 operations in *delegate* mode, and its responses match the Phase 0 golden fixtures byte-for-byte (after JSON normalisation).
- The gateway can send any single path to Java or z/OS Connect with a config change, and can roll back without a redeploy.
- The IMS Java project builds and tests from one Gradle build, and `InsertHist` failures are visible.
- The frontend test suite under `tests/` passes against the gateway with no frontend change.

---

## Phase 2: Read-only queries

**Goal:** Replace inquiry programs with native Java endpoints over the existing Db2 data. Read-only operations go first because they are low-risk.

### Scope

| COBOL program | Java endpoint | Notes |
|---|---|---|
| `INQCUST` | `GET /customers/{customerId}` | `9999999999` means "last customer"; keep that behaviour or mark it deprecated in the contract |
| `INQACC` | `GET /accounts/{accountId}`, `.../balances` | `99999999` means "last account"; not-found currently returns success `'Y'` |
| `INQACCS` | `GET /accounts` | |
| `INQACCCU` | `GET /customers/{customerId}/accounts` | Internal dependency of `CREACC` and `DELCUS`; expose it as a Java service as well as an endpoint |
| `INQTRANL` | `GET /accounts/{accountId}/transactions` | Paging/ordering parity |
| `INQTRAND` | `GET /accounts/{accountId}/transactions/{transactionId}` | Transaction ID format `SC-ACCNO-YYYYMMDD-HHMMSS-REF` |
| `GETSCODE` | Config property / internal service | No caller, no data; no new endpoint unless the contract needs one |
| `GETCOMPY` | Config property / internal service | Same as `GETSCODE` |

- Convert the Phase 0 copybooks to Java `record`s: Db2 row types from `ACCDB2`/`CUSTDB2`/`PROCDB2` (with DDL types), and response DTOs from the matching COMMAREA copybooks. Money fields use `BigDecimal` with the DDL scale. Dates stored as `INTEGER YYYYMMDD` get explicit converters.
- **Parity check:** for a time-boxed window, the gateway sends every request to both the Java and COBOL endpoints, compares the responses, and logs differences. Only the COBOL response is returned to the client.
- IMS read paths (`IBGCUDAT`, `IBACSUM`) can move in this phase through the IMS Universal JDBC driver, read-only, if Phase 4/5 decides to keep IMS. Otherwise they stay delegated.

### Exit criteria

- Every endpoint above routes 100% to Java in production, with zero unexplained differences over the agreed parity window.
- p95 latency is no worse than the COBOL path.
- The COBOL inquiry programs get no API traffic. They stay deployed only for internal `LINK` callers (`CREACC`, `DELCUS`, and the `BNK*` screens) until Phase 3.

---

## Phase 3: Mutating operations

**Goal:** Move all writes to Java transactional services, replace the 3270 flows, and retire their programs.

### Scope

| COBOL program | Java service | Notes |
|---|---|---|
| `CRECUST` | `CustomerService.create` | Replace the `RUN TRANSID` credit-agency fan-out with parallel outbound calls with timeouts; keep the `CONTROL` counter (or a Db2 sequence) under a row lock |
| `UPDCUST` | `CustomerService.update` | Does not touch created date or credit score; keep that |
| `DELCUS` | `CustomerService.delete` | Deletes accounts, then the customer, then writes `PROCTRAN`, all in one transaction |
| `CREACC` | `AccountService.create` | **Needs a new operation** (e.g. `POST /accounts`) in `openapi.yaml` |
| `UPDACC` | `AccountService.update` | **Needs a new operation** (`PUT /accounts/{accountId}`); updates type, rate, and overdraft only |
| `DELACC` | `AccountService.delete` | **Needs a new operation** (`DELETE /accounts/{accountId}`) |
| `DBCRFUN` | `PaymentService.debitCredit` | Backs `POST /accounts/{accountId}/deposit`; keep the existing insufficient-funds and account-type rules |
| `XFRFUN` | `TransferService.transfer` | **Needs a new operation** (e.g. `POST /transfers`); replace `-911` retry/`DELAY` with optimistic locking or `SELECT ... FOR UPDATE` |
| `CRDTAGY1`–`5` | `CreditAgencyClient` (×5) | Outbound HTTP clients behind one interface; the simulator becomes a test stub |

- **Transactions:** Spring `@Transactional` over a single Db2 datasource covers every program above, because they are all Db2-only. JTA/XA is needed only if a flow is later made to span Db2 and IMS or VSAM. The one case today is the IMS deposit (`IBTRAN`: DL/I plus `IMSBANK.HISTORY`).
- **Frontend:** wire `createAccount`, `updateAccount`, and `deleteAccount` in `src/frontend/js/api.js` to the new operations, and add a transfer page. Until this ships, the JS frontend does not replace `BNK1CAC`, `BNK1UAC`, `BNK1DAC`, or `BNK1TFN`.
- **Retire BMS:** once the replacements are live, remove `BNKMENU`, the `BNK1*` programs, and the BMS mapsets, plus their CSD definitions (including the source-less `BNK1B2B`).
- **Decommission `BANKDATA` and `ABNDPROC`** once nothing references them. Replace `BANKDATA` with a Java/Flyway seed-data loader. Replace `ABNDPROC`/`ABNDFILE` with structured logging, which also ends the last VSAM dependency. `ABNDPROC` is only unreferenced after every COBOL caller is gone.

### Exit criteria

- All mutating API operations, old and new, are served by Java, with parity tests on the resulting Db2 state (rows in `CUSTOMER`, `ACCOUNT`, `PROCTRAN`, `CONTROL`).
- Concurrency tests show no lost updates on balance changes (`DBCRFUN`, `XFRFUN`).
- No 3270 transaction is in use, and the `BNK*` programs and BMS maps are removed from the CSD.
- `BANKDATA` and `ABNDPROC` have no remaining callers.

---

## Phase 4: Batch migration

**Goal:** Move batch and the remaining IMS PL/I work to Java.

### Scope

- **`src/base/batch/pli/BNKSTMT.pli` → Spring Batch job** with this structure: reader over `CUSTOMER`/`ACCOUNT`/`PROCTRAN`, statement processor, and file/report writer, with restart through the Spring Batch job repository. Fix the `SORTCODE` mismatch (JCL `123456` vs `987654`) by reading the same config property as the online services.
- **`src/base/batch/jcl/BNKSTMT.jcl` → JZOS/BPXBATCH** step that launches the job jar, or hand the job to the enterprise scheduler (e.g. TWS/Control-M) if Java runs off-platform. Pass `DATECARD`/`SORTCODE` as job parameters.
- **IMS PL/I `IBLOGIN.pli`** (and `IBLOGOUT`, `IBLOGIN1` if Phase 0 shows they are live): rewrite to Java using IMS Universal Drivers (JDBC or DL/I), or move the segments they use to Db2 and implement them as plain Spring services. Fix the `LASTLOGIN` timestamp format mismatch while doing this.
- **IMS loaders** (`LOADACCT`, `LOADCUSA`, `LOADCUST`, `LOADHIST`, `LOADTSTA`): replace with the Java seed loader, or with Db2 `LOAD` if segments move to Db2.

### Exit criteria

- The Spring Batch statement output matches `BNKSTMT` output for the same `DATECARD`/`SORTCODE` input across agreed sample periods.
- The job is scheduled, restartable, and alerting in the target scheduler, and the PL/I job is turned off.
- No IMS PL/I or COBOL program in production (beyond what Phase 5 retires) lacks a Java replacement.

---

## Phase 5: Data and decommission

**Goal:** Finish data re-platforming where it was chosen, and remove the legacy code and build tasks.

### Scope

- **Data:** move IMS databases (`CUSTOMER`, `ACCOUNT`, `CUSTACCS`, `HISTORY`, `TSTAT` and the lookup DBDs `ACCTYPE`, `CUSTTYPE`, `TSTATTYP`, `TTYPE`) to relational tables if the Open Decisions call for it. Reconcile the two IMS history copies (DL/I `HISTORY` vs `IMSBANK.HISTORY`) into one source of truth. Reconcile money precision (`DECIMAL(12,2)` CICS vs `DECIMAL(15,2)`/`S9(13)V99` IMS) if the two banks are merged. `ABNDFILE` (VSAM) is retired in Phase 3.
- **Source removal:** `src/base/cics/cobol/`, `src/base/cics/bms/`, `src/base/cics/copy/`, `src/base/ims/cobol/`, `src/base/ims/pli/`, `src/base/ims/copy/`, `src/base/batch/`, and `src/base/ims/DBD/`/`PSB/`/`LoadData/` if IMS is retired.
- **Build:** in `dbb-app.yaml`, remove the `Cobol` and `PLI` tasks, and `Assembler`/`ImsJavaBuilder` if IMS is retired. Remove `zOSConnect` and the `src/api/src/main/zosAssets/` and `operations/` trees once the gateway points only at Java. Keep `openapi.yaml` as the contract for the Java service.
- **Runtime:** remove CICS/IMS resource definitions from `.setup/zconfig/` and `.setup/jcl/`, and update `tests/` to target the Java service.

### Exit criteria

- 100% of API traffic and all batch work run on Java for an agreed stabilisation period.
- No COBOL, PL/I, BMS, copybook, DBD, or PSB sources remain (or only the ones Open Decisions chose to keep), and `dbb-app.yaml` has no COBOL/PLI tasks.
- Data reconciliation reports are signed off, and the legacy regions/datasets are scheduled for shutdown.

---

## Open decisions

| Decision | Options | Inputs needed |
|---|---|---|
| **Where Java runs** | **On z/OS:** Liberty/CICS JVM server or JZOS, with JCC type 2 to Db2. Low latency, local `LINK` to COBOL during co-existence, zIIP offload, same security (RACF). **Off-platform:** containers/cloud with JCC type 4. Frees the platform eventually, but adds network hops, distributed transactions, and a remote path to COBOL during Phases 1–3 | Latency SLOs, MIPS/zIIP cost, ops skills, long-term platform strategy |
| **Data platform** | **Keep Db2 for z/OS** (lowest risk; Java uses the same tables) vs **full re-platforming** (e.g. Db2 LUW/PostgreSQL), which needs CDC/replication during cut-over and re-validation of `DECIMAL` and `INTEGER YYYYMMDD` semantics | Where Java runs, data gravity, other consumers of `BANKZ`/`IMSBANK` tables |
| **IMS scope** | Which IMS programs are actually used in production: API-backed `IBACSUM`, `IBGCUDAT`, `IBSCUDAT`, `IBTRAN`; not API-backed `IBLOGIN` (PL/I), `IBLOGOUT`; not defined to IMS `IBLOGIN1`; unreferenced Java `IMSBankHistory`; JMP `QueryTransaction`/`IBGHIST` (no API asset); loaders `LOAD*` | IMS transaction statistics (SMF/IMS log), z/OS Connect access logs |
| **Contract changes** | How to expose `CREACC`/`UPDACC`/`DELACC`/`XFRFUN` (new paths and versioning), and whether to keep the "last record" sentinels (`9999999999`, `99999999`) | API consumers, frontend roadmap |
