---
layout: default
title: Java Migration Plan
---

# Java Migration Plan

This plan moves the Bank of Z business logic from COBOL and PL/I to Java in five phases (0 to 4). Each step migrates one program. While that happens, three things stay fixed:

- **The REST contract** in `src/api/src/main/api/openapi.yaml` (17 operations on 14 paths).
- **The frontend** in `src/frontend`. It keeps routing customers whose IDs start with `C` to the CICS paths and those starting with `I` to the `/ims/...` paths.
- **The DBB and Wazi Deploy pipeline.** It is extended to handle Java artifacts, not replaced.

Each phase below has the same four parts: **scope**, **deliverables**, **verification gates**, and **rollback**. If a phase misses its gates, it does not move to production.

All counts and names in this document come from the repository at the time of writing. Phase 0 re-checks them before work starts.

---

## Current state

### Source inventory

| Area | Path | Contents |
|------|------|----------|
| CICS COBOL | `src/base/cics/cobol` | 32 programs (see the classification below) |
| CICS copybooks | `src/base/cics/copy` | 42 copybooks: COMMAREA layouts (for example `INQCUST`, `PAYDBCR`, `XFRFUN`), Db2 host-variable layouts (`ACCDB2`, `CUSTDB2`, `PROCDB2`, `CONTDB2`, pulled in by `EXEC SQL INCLUDE`), and z/OS Connect variants (`*Z.cpy`) |
| CICS BMS | `src/base/cics/bms` | 10 mapsets for the 3270 screens (`BNK1MAI`, `BNK1TFM`, `BNK1B2M`, ...) |
| IMS COBOL | `src/base/ims/cobol` | 6 online programs (`IBACSUM`, `IBGCUDAT`, `IBSCUDAT`, `IBTRAN`, `IBLOGIN1`, `IBLOGOUT`) and 5 DL/I loaders (`LOADACCT`, `LOADCUSA`, `LOADCUST`, `LOADHIST`, `LOADTSTA`) |
| IMS PL/I | `src/base/ims/pli` | `IBLOGIN.pli` |
| IMS Java | `src/base/ims/java` | Gradle project `nazare.jmp` with 8 classes and 738 lines (assessed in [Existing IMS Java code](#existing-ims-java-code)) |
| IMS DBD / PSB | `src/base/ims/DBD`, `src/base/ims/PSB` | 9 DBDs, all `ACCESS=(HDAM,OSAM)`; 8 PSBs |
| Batch | `src/base/batch/pli`, `src/base/batch/jcl` | `BNKSTMT.pli` monthly statement report (911 lines, Db2 cursors over `ACCOUNT` and `PROCTRAN`) and `BNKSTMT.jcl` (`IKJEFT01` / `DSN RUN`, DD names `DATECARD`, `SORTCODE`, `SYSPRINT`) |
| API | `src/api` | z/OS Connect OpenAPI 3 project: `openapi.yaml`, `operations/`, `zosAssets/` (10 `cicsCommarea-1.0` and 4 `imsTransaction-1.0` assets), Liberty `cics.xml` (IPIC connection `bankzCicsConnection`) and `ims.xml` (`imsConn`) |
| Frontend | `src/frontend` | Static HTML and JavaScript. `js/api.js` and `js/utils.js` (`parseCustomerId`) choose CICS or IMS. **Not changed by this plan.** |
| Tests | `tests/` | `run-all.sh`, `test_frontend.sh`, `test_get_customer_cics.sh`, `test_get_customer_ims.sh`. Run by `.setup/tasks/task-install-verification.sh`. |
| Build | `dbb-app.yaml`, `.setup/build/` | DBB tasks `Cobol`, `PLI`, `Assembler`, `ImsJavaBuilder`, `zOSConnect`, `VanillaFrontend`, `ServerXmlPackager`. Groovy tasks are in `.setup/build/groovy/`. |
| Deploy | `.setup/deploy/` | Wazi Deploy `deployment-method.yml` (activities include `DEPLOY_MODULES`, `ROLLBACK_MODULES`, `CICS_ACTIVATION`, `IMS_ACB_MANAGEMENT`, `Deploy IMS JAR`, `DEPLOY_ZOS_CONNECT`) and `types_pattern_mapping.yml` (types `LOAD`, `CICSLOAD`, `IMSLOAD`, `PSBLOAD`, `DBDLOAD`, `JAR`, `WAR`, ...) |

### CICS program classification

| Class | Programs | How they are called |
|-------|----------|---------------------|
| API, read-only | `INQCUST`, `INQACC`, `INQACCS`, `INQACCCU`, `INQTRAND`, `INQTRANL` | z/OS Connect `cicsCommarea` assets |
| API, update | `CRECUST`, `UPDCUST`, `DELCUS`, `DBCRFUN` | z/OS Connect `cicsCommarea` assets |
| Update, no API operation | `XFRFUN`, `UPDACC`, `CREACC`, `DELACC` | Only from BMS programs. `DELACC` is also LINKed by `DELCUS`. |
| Asynchronous children | `CRDTAGY1`..`CRDTAGY5` (transactions `OCR1`..`OCR5`) | Started by `CRECUST` with `EXEC CICS RUN TRANSID` on a channel, no COMMAREA |
| 3270 presentation | `BNKMENU`, `BNK1CAC`, `BNK1CCA`, `BNK1CCS`, `BNK1CRA`, `BNK1DAC`, `BNK1DCS`, `BNK1TFN`, `BNK1UAC` | Terminal transactions `OMEN`, `OCAC`, `OCCA`, `OCCS`, `OCRA`, `ODAC`, `ODCS`, `OTFN`, `OUAC` |
| Utilities | `ABNDPROC`, `GETCOMPY`, `GETSCODE`, `BANKDATA` | `BANKDATA` is the batch Db2 loader run from `.setup/jcl/cics/Db2-insert.j2`. The other three are small helpers. |

### API operations and their backends

| Operation | Backend asset | Subsystem |
|-----------|---------------|-----------|
| `GET /customers/{customerId}` | `INQCUST` | CICS |
| `POST /customers` | `CRECUST` | CICS |
| `PUT /customers/{customerId}` | `UPDCUST` | CICS |
| `DELETE /customers/{customerId}` | `DELCUS` | CICS |
| `GET /customers/{customerId}/accounts` | `INQACCCU` | CICS |
| `GET /accounts` | `INQACCS` | CICS |
| `GET /accounts/{accountId}`, `GET /accounts/{accountId}/balances` | `INQACC` | CICS |
| `POST /accounts/{accountId}/deposit` | `DBCRFUN` | CICS |
| `GET /accounts/{accountId}/transactions` | `INQTRANL` (transid `ITRL`) | CICS |
| `GET /accounts/{accountId}/transactions/{transactionId}` | `INQTRAND` (transid `ITRD`) | CICS |
| `GET /ims/customers/{customerId}` | `IBGCUDAT` | IMS |
| `PUT /ims/customers/{customerId}` | `IBSCUDAT` | IMS |
| `GET /ims/customers/{customerId}/accounts`, `GET /ims/accounts/{accountId}`, `GET /ims/accounts/{accountId}/balances` | `IBACSUM` | IMS |
| `POST /ims/accounts/{customerId}/{accountId}/deposit` | `IBTRAN` | IMS |

### Current test coverage

`tests/run-all.sh` (run by `task-install-verification.sh`) checks 4 of the 17 API operations, and all 4 are reads. This is the gap that Phase 0, deliverable 4, closes before any code moves.

| Area | Covered by | Depth of checks |
|------|------------|-----------------|
| Frontend | `test_frontend.sh`: `GET /admin.html` | HTTP status only |
| `GET /customers/{customerId}` (CICS) | `test_get_customer_cics.sh` | `lastName` only |
| `GET /ims/customers/{customerId}`, `GET /ims/customers/{customerId}/accounts`, `GET /ims/accounts/{accountId}/balances` | `test_get_customer_ims.sh` | Fields are non-empty; only `customerId` is checked for an exact value |
| The other 13 operations, including every write (`POST`/`PUT`/`DELETE` customers, both deposits) | None | None |
| BMS/3270-only programs (`XFRFUN`, `UPDACC`, `CREACC`, `DELACC`, `BNK1*`) | None | None |
| Batch `BNKSTMT` | None | None |
| `src/base/ims/java` (no `src/test`, no test dependencies) and `IBGHIST` | None | None |

The scripts parse only a few JSON fields, so they cannot catch differences in other fields, in numeric format or padding, or in the underlying COMMAREA or IMS message bytes. Three notes for anyone extending the suite:
- The IMS `accountId` path parameter is mapped to `IN-CUSTID` by `IBACSUM`, so these paths take a customer ID. This matches how `src/frontend/js/api.js` calls them.
- `BANKDATA` generates CICS account numbers and does not load `PROCTRAN`, so CICS account tests should take their account IDs from the customer's account list, and transaction-detail tests should skip when the list is empty.
- Batch output and the 3270-only programs are covered by the byte comparisons in Phases 2 and 3, not by `tests/`.

### Existing IMS Java code

The code under `src/base/ims/java` is **real, deployed transaction logic, not a sample.** Its scope is narrow, though: it only handles transaction history held in Db2 (`IMSBANK.HISTORY`). All DL/I database access and all business rules are still in COBOL and PL/I.

| Class | Role | Evidence |
|-------|------|----------|
| `controller.QueryTransaction` | JMP program for IMS transaction `IBGHIST`. Reads the input message with `com.ibm.ims.dli.tm`, queries Db2, and returns up to 50 history rows. | `IBGHIST=nazare/jmp/controller/QueryTransaction` in `.setup/jcl/ims/templates/jmp/CREDFSJVMAP.j2`; `CREATE PGM NAME(IBGHIST) ... LANG(JAVA)` in `.setup/deploy/ims_spoc.jcl.j2` |
| `controller.InsertHist` | Static method that COBOL `IBTRAN` calls through JNI (`NewDirectByteBuffer` over `HISTORY-SEG`). Decodes packed decimal fields with JZOS `PackedBigDecimalField` and text with `IBM-1047`, then inserts a row into `IMSBANK.HISTORY`. | `Class DB2InsertHist is "nazare.jmp.controller.InsertHist"` in `IBTRAN.cbl`; compile options `JAVAIOP(JAVA64)` and `LP(32)` in `dbb-app.yaml` |
| `history.TransactionService`, `history.TransactionDetail` | Db2 access over JDBC type 2 (`jdbc:db2:` plus system property `db2.location`, default `DBD1LOC`) and the row object | — |
| `message.*` (3 classes) | `IMSFieldMessage` layouts for the `IBGHIST` input and output messages | — |
| `controller.IMSBankHistory` | Type 4 JDBC variant with user and password. Nothing references it (no `DFSJVMAP` entry, no caller), so it looks like dead code. | — |

These are the findings that matter for migration:

- **The IMS history is written twice.** `IBTRAN` calls `InsertHist` (Db2) **before** the DL/I `ISRT` into the IMS `HISTORY` segment. `InsertHist` catches every exception and only calls `printStackTrace`, so a failed Db2 write is invisible to `IBTRAN` and the two copies can drift apart without anyone noticing. Phase 0 must reconcile them. Phase 4 can reuse the existing Db2 copy.
- **`IBGHIST` has no caller in the API or the UI.** It is deployed, but no z/OS Connect asset exists for it.
- **The insert has no explicit commit.** `TransactionService` turns auto-commit off and never calls `commit()` on the insert path, so it relies on the surrounding IMS sync point. Phase 0 must confirm this under load.
- **Two EBCDIC code pages are in use.** The z/OS Connect CICS assets use CCSID 037, while the Java code decodes `IBM-1047`. The two differ for a few characters (for example `[`, `]`, `^`, `¬`). The record mapping in Phase 1 must set the code page per interface.
- **Several pieces already work and are reused.** The Gradle build through `ImsJavaBuilder` (`gradle clean jar`, Java 21 toolchain), the Wazi Deploy `.JAR` type (deployed to `{{ sandbox_path }}/jars`), the JMP region (`DFSJMP`, `DFSJVMEV`, `DFSJVMAP`), and COBOL-to-Java JNI calls are all proven, and later phases build on them.

---

## Cross-phase mechanisms

### Strangler routing through z/OS Connect

The frontend only calls z/OS Connect operations. Each operation points to an asset in `src/api/src/main/zosAssets/`, and the asset names a **CICS program** or an **IMS transaction code**. So the cut-over happens at the asset or program level, and the frontend never notices. There are three routing mechanisms, from least to most control:

1. **Program redefinition (CICS, all-or-nothing).** Keep the program name (for example `INQCUST`) and change its CICS `PROGRAM` definition in `.setup/zconfig/bank-of-z-definitions.yaml` to point at the Java implementation in a JVM server. The asset, the IPIC connection, and the BMS callers stay the same.
2. **COBOL router (CICS, per request). This is the recommended option for Phases 2 and 3.** Rename the legacy program to an alias of up to 8 characters, recorded in the inventory (for example `INQCUST` becomes `INQCUSTL`; names that are already 8 characters, such as `INQACCCU`, get an assigned alias). A thin router then takes over the original name. Because `LINK` passes the COMMAREA by reference, the router passes it through untouched. It reads a routing table (Db2 `BANKZ.ROUTING`, cached in the region) that sets one mode per program:
   - `LEGACY`
   - `JAVA`
   - `SHADOW`: run the legacy program for the real response, and asynchronously run Java against the shadow schema and compare the results.
   - `CANARY(n%)`
   
   This also covers BMS callers and program-to-program `LINK`s, such as `DELCUS` calling `DELACC`.
3. **Asset re-pointing (IMS, and CICS if needed).** Add a new asset (for example `IBTRANJ` → a Java JMP transaction code) and switch the operation in `src/api/src/main/operations/...` to it. The switch ships through the existing `DEPLOY_ZOS_CONNECT` and `Refresh z/OS Connect` activities. To roll back, redeploy the previous API WAR, which is kept in the Wazi Deploy backup at `{{ zos_connect_root }}/backup/apps`.

### Byte-level payload comparison

Integration tests that check JSON fields are not enough on their own. Every migrated program must also produce **byte-identical outputs** to the legacy program for the same inputs, after masking the fields listed below.

| Interface | What is compared | Encoding |
|-----------|------------------|----------|
| CICS COMMAREA | Output COMMAREA bytes (full length, including filler) | CCSID 037. Zoned decimal `S9(10)V99 DISPLAY` amounts must keep the same sign nibble. |
| IMS message | Output message `LL ZZ` plus segment bytes | As defined by the asset's `OUTPUT-AREA` copybook |
| Batch | `SYSPRINT` report bytes, line by line | EBCDIC |
| Data side effects | After-images of rows or segments touched (`ACCOUNT`, `CUSTOMER`, `PROCTRAN`, `CONTROL`, IMS segments, `IMSBANK.HISTORY`) | Compared by column |

- **Capture corpus (Phase 0).** For every operation, record request and response pairs: COMMAREA bytes at the router, IMS messages, and z/OS Connect JSON. Store them as hex fixtures under `tests/golden/<program>/`.
- **Masks.** Each program has a list of fields that are allowed to differ:
  - timestamps and dates (`PROCTRAN` date and time, `LASTLOGIN`)
  - generated keys (the customer and account numbers from the `CONTROL` counters)
  - simulated credit scores (`CRDTAGYn` use `RANDOM`)
  
  Each list lives next to its fixtures and is reviewed like code. A mask is never added just to make a diff go away.
- **Comparison tool.** A Java CLI in the Phase 1 records module decodes both byte streams with the generated record classes and reports differences by field name and offset.

### Gate applied in every phase

A migrated program is promoted only if all of the following hold:

1. Java unit tests pass in the Gradle build. Gradle `test` is not part of the DBB `jar` path, so the tests run in CI before DBB.
2. `tests/run-all.sh` is green against the target environment (through `task-install-verification.sh`). It must pass with both `IMS_DISABLED=false` and `IMS_DISABLED=true` where relevant.
3. The byte comparison finds zero unmasked differences across the full capture corpus for that program.
4. In Phase 3, the shadow run finds zero unmasked differences for the agreed soak period.
5. The rollback in that phase's section has been rehearsed once in a non-production region.

---

## Phase 0: Assessment and baseline

### Scope
Freeze what the system does today before changing any of it: build the inventory, capture every contract, extend the regression harness, and decide the target runtime.

### Deliverables

1. **Program inventory** (`docs/docs/migration/inventory.md`). One row per program, transaction, PSB, and DBD, with these columns:
   - caller graph (`LINK`, `RUN TRANSID`, JNI, z/OS Connect asset, BMS transid)
   - Db2 tables and DL/I segments touched, with read or update
   - copybooks used (`COPY` versus `EXEC SQL INCLUDE`)
   - lines of code
   - assigned wave
   
   It also records copybooks that nothing includes, and CSD entries without source (for example program `BNK1B2B` is defined in `bank-of-z-definitions.yaml` but has no `.cbl`).
2. **Contract capture.** Freeze everything an outside caller can depend on:
   - COMMAREA and message layouts, generated from the copybooks and from the z/OS Connect `providerFiles/gen/*.cpy` and `*Schema.json`
   - the OpenAPI document
   - Db2 DDL (`.setup/jcl/cics/Db2-create.j2`, `.setup/jcl/ims/Db2-create.j2`)
   - DBD segment layouts
   
   Known differences between copybooks and DDL are listed explicitly. For example, `ACCDB2.cpy` declares `DECIMAL(4,2)` and `DECIMAL(10,2)`, while the DDL creates `DECIMAL(6,2)` and `DECIMAL(12,2)`. Also, CICS balances are `DECIMAL(12,2)` while IMS uses `DECIMAL(15,2)` and `S9(13)V99`.
3. **Behavioural baseline.** Record current behaviour, including quirks, as expected results. Migration must not "fix" any of these without a separate, approved change. Examples:
   - `IBTRAN` withdrawals never check the balance.
   - `XFRFUN` retries SQLCODE `-911`.
   - `INQCUST` treats `9999999999` as "last customer".
   - `BNKSTMT` defaults its sort code to `123456` (in both the JCL and the code), while `BANKDATA` loads data with sort code `987654` from `SORTCODE.cpy`. As shipped, the statement run probably selects no accounts.
4. **Extended regression harness.** Add one `tests/test_<operation>.sh` per API operation (all 17). Follow the existing pattern: source `test-setup.sh`, count passes and failures, use an `_ims` suffix so `IMS_DISABLED` skips IMS tests, and test both HTTP and HTTPS. Add the capture corpus and the comparison tool skeleton under `tests/golden/`.
5. **Target runtime decision record** (`docs/docs/migration/adr-001-runtime.md`):

| Option | Fits | Keeps contracts unchanged | Transactions | Notes |
|--------|------|---------------------------|--------------|-------|
| **Java in CICS** (JCICS in a Liberty or OSGi JVM server) | CICS programs | Yes: same program name, COMMAREA, IPIC connection, and CICS security | CICS unit of work with Db2 type 2 JDBC | Allows `LINK` between COBOL and Java, which the router and mixed waves need |
| **Java in IMS** (JMP for messages, JBP for batch) | IMS TM programs | Yes: same transaction code and message format | IMS sync point (already in use by `IBGHIST`) | Region, `DFSJVMAP`, and the build already exist |
| **Spring Boot** (z/OS UNIX, zCX, or off-platform) | Possible end state behind z/OS Connect or as a replacement | No: needs a new asset type or an API-layer change | Distributed (XA or sagas) | Best treated as a later step once the logic is in Java |

   **Recommendation:** use JCICS for CICS, JMP for IMS TM, and JZOS batch for `BNKSTMT`. Keep the domain and repository code free of any framework so it can later move to Spring Boot without being rewritten.

### Verification gates
- The inventory has been reviewed against `dbb-app.yaml` file lists, CSD definitions, `ims_spoc.jcl.j2`, and the z/OS Connect assets, with no unexplained entries.
- The extended `tests/run-all.sh` passes against the **unchanged** system on two consecutive runs.
- Replaying the capture corpus against the unchanged system matches byte for byte, which proves the masks are complete and the outputs are deterministic.
- The runtime decision record has been approved.

### Rollback
No production change is made in this phase. If the harness or corpus turns out to be wrong, revert the commits under `tests/` and `docs/docs/migration/`.

---

## Phase 1: Foundation

### Scope
Build the shared Java base and pipeline support. No production traffic moves to Java in this phase.

### Deliverables

1. **Java build modules.** Use multi-project Gradle, Java 21 (matching `src/base/ims/java`):
   - `src/java/bankz-records`: record classes generated from copybooks, plus codecs for zoned decimal, packed decimal, `COMP`/`COMP-5`, and EBCDIC with the CCSID set per interface. Also the comparison CLI.
   - `src/java/bankz-core`: domain services, plus repository interfaces such as `CustomerRepository`, `AccountRepository`, and `TransactionHistoryRepository`.
   - `src/java/bankz-db2`: JDBC repository implementations (type 2 on z/OS, H2 in Db2 mode for unit tests, with DDL loaded from `Db2-create.j2`).
   - `src/java/bankz-cics`: JCICS entry points, one per migrated program.
   - `src/java/bankz-batch`: JZOS batch mains.
   - `src/base/ims/java`: stays the JMP project. It gains a dependency on `bankz-core`.
2. **Copybook-to-Java record mapping.** A generator turns each `.cpy` into an immutable record class with `fromBytes(byte[])` and `toBytes()`. The class keeps field offsets, lengths, `PIC` scale, sign handling, `REDEFINES`, and `OCCURS` exactly. The generated classes are checked in, and the build fails if they no longer match their copybook. Round-trip tests run over every Phase 0 golden fixture.
3. **DBB extensions:**
   - Generalise `.setup/build/groovy/ImsJavaBuilder.groovy` into a `JavaBuilder` task that takes a list of Gradle projects (`configSources`) and a deploy type for each. Register it in `dbb-app.yaml` next to the existing `ImsJavaBuilder` entry.
   - Add impact rules so that a change to a `.cpy` file triggers record regeneration and a Java rebuild. Today `**.java` is excluded from `ScannerInit` and `ImpactAnalysis`, and Java changes are only detected by matching path substrings.
   - Run `gradle test` in the pipeline before `jar`.
4. **Wazi Deploy extensions:**
   - Add a type in `.setup/deploy/types_pattern_mapping.yml` for CICS Java artifacts (for example `.*\.CICSJAR$` going to the JVM server's application directory, with a `backup_dest`).
   - Add an activity in `.setup/deploy/deployment-method.yml`, modelled on `Deploy IMS JAR`, that installs the bundle and refreshes the JVM server.
   - Extend `ROLLBACK_MODULES` to restore JARs from the backup.
5. **Runtime infrastructure:**
   - CICS JVM server definition and CSD group in `.setup/zconfig/`.
   - Router program skeleton, `BANKZ.ROUTING` DDL, and a seed that sets every program to `LEGACY`.
   - Shadow Db2 schema `BANKZSH`, using the same DDL as `BANKZ` and refreshed from it.
6. **Strangler wiring:** put the router under one read-only program (`INQCUST`), still in `LEGACY` mode, to prove that the path through z/OS Connect, the router, and the legacy program adds no behaviour change.

### Verification gates
- The generated records round-trip every golden fixture byte for byte.
- The DBB pipeline builds and deploys the new modules end to end, and a pipeline change to a copybook triggers the Java rebuild.
- `tests/run-all.sh` is green with the router in place in `LEGACY` mode, and the byte comparison of `INQCUST` responses before and after the router finds zero differences.
- The JAR rollback through `ROLLBACK_MODULES` has been rehearsed.

### Rollback
- Remove the router by restoring the original `INQCUST` program definition and running `NEWCOPY`.
- The new Gradle projects and DBB or Wazi Deploy entries are additive. Revert the commits and redeploy the previous package.

---

## Phase 2: Edge services

### Scope
Migrate the lowest-risk work first.

1. **Batch PL/I `BNKSTMT`** becomes `bankz-batch` `StatementJob` (JZOS). The JCL keeps the same DD names: `DATECARD`, `SORTCODE`, and `SYSPRINT`.
2. **Read-only CICS transactions,** in dependency order:
   1. `GETSCODE` and `GETCOMPY`
   2. `INQCUST`
   3. `INQACC` and `INQACCS`
   4. `INQACCCU` (it LINKs to `INQCUST`)
   5. `INQTRAND` and `INQTRANL`

### Deliverables
- `StatementJob`, plus `BNKSTMTJ.jcl` using the JZOS batch launcher, deployed through the `.JCL` type. It uses Db2 type 2 JDBC under the same plan and authorisation ID, or a JDBC package bound for it.
- JCICS implementations for each read-only program, using the Phase 1 records and repositories. The COMMAREA formats stay as they are, including the not-found and abend paths (for example, `INQACC` sets `INQACC-SUCCESS='Y'` even when the account is not found).
- Routing table entries that move each program from `SHADOW` to `CANARY(10%)` to `JAVA`.

### Verification gates
- **Batch:** run both versions with the same `DATECARD` and `SORTCODE` against a frozen Db2 snapshot. `SYSPRINT` must match byte for byte, with only the run timestamp masked, for at least three reporting months, including an empty month and the sort-code mismatch case from Phase 0.
- **Read-only programs:**
  - zero unmasked COMMAREA differences across the corpus and through `SHADOW` mode
  - `tests/run-all.sh` green while in `CANARY` mode and while in `JAVA` mode
  - p95 latency no worse than the legacy program by more than an agreed budget
- The COBOL source stays in place, and is still built and deployed, until Phase 4.

### Rollback
- **Batch:** schedule `BNKSTMT.jcl` again. The legacy load module still ships in `{{ hlq }}.LOAD`.
- **CICS reads:** set the program's routing row to `LEGACY`. This takes effect immediately and needs no deployment. If the router itself is at fault, restore the original program definition.

---

## Phase 3: Core transactions

### Scope
1. **CICS update programs.** These keep their COMMAREA contracts exactly. The order is:
   1. `UPDCUST` and `UPDACC`
   2. `DBCRFUN`
   3. `CREACC`
   4. `DELACC`, then `DELCUS`
   5. `CRECUST`, together with `CRDTAGY1`..`5`. Their channel and container contract (`RUN TRANSID` `OCR1`..`OCR5`) stays the same.
   6. `XFRFUN`, last, because it is the largest program and has the `-911` retry logic.
2. **IMS TM** moves to Java in JMP regions. DL/I access goes behind a repository abstraction. The order is:
   1. `IBGCUDAT` and `IBACSUM` (read-only)
   2. `IBSCUDAT`
   3. `IBTRAN`. Its Java version calls the history repository directly, which removes the JNI hop.
   4. `IBLOGIN` (PL/I), `IBLOGIN1`, and `IBLOGOUT`
3. **Optional:** move the BMS presentation programs onto the Java backends. They keep calling the same program names through the router, so they need no change.

### Deliverables
- JCICS update programs that run inside the CICS unit of work, with Db2 updates committed at the CICS sync point. They keep today's isolation and locking behaviour, including the `XFRFUN` retry count and delay, and the `CONTROL` counter `ENQ` in `CRECUST`.
- `bankz-core` repository interfaces with two implementations each:
  - `Db2...Repository` for CICS
  - `DliAccountRepository` and related classes for IMS, using the IMS Universal JDBC or DL/I drivers (`com.ibm.ims:udb`, already a dependency) with the existing PSBs
  
  The service code does not know which store it is using. That is what makes Phase 4 possible.
- New Java JMP programs registered in `DFSJVMAP` (`CREDFSJVMAP.j2`) and `ims_spoc.jcl.j2` (`CREATE PGM ... LANG(JAVA)`, plus transaction codes such as `IBTRANJ`). Each gets its own z/OS Connect asset, used for re-pointing.
- **Dual run with shadow traffic:**
  - **CICS:** router mode `SHADOW` runs the legacy program as the system of record. It then starts the Java program asynchronously with a copy of the input COMMAREA against the `BANKZSH` schema, and compares the output COMMAREA and the row after-images.
  - **IMS:** the shadow transaction code runs the Java program against a shadow IMS datastore, or in compare-only mode with `PROCOPT=G`, and compares the output message and the intended DL/I updates.
  - Shadow results go to a `BANKZ.SHADOW_DIFF` table and are reported every day.
- A reconciliation job comparing `IMSBANK.HISTORY` with the IMS `HISTORY` segment, because of the dual write found in Phase 0.

### Verification gates
- Zero unmasked differences in shadow mode over a soak period covering at least one month-end. The comparison includes the COMMAREA or message bytes and the data after-images.
- `tests/run-all.sh` green in each routing state.
- Concurrency tests show the same outcomes under contention as the legacy programs, including `-911` handling and `CONTROL` counter uniqueness.
- Financial reconciliation: the total of balances and the count of `PROCTRAN` and `HISTORY` rows match between the legacy and shadow stores at the end of the soak.

### Rollback
- **CICS:** set the program's routing mode to `LEGACY`. The Java and COBOL versions share `BANKZ` and use the same row formats, so this needs no data repair.
- **IMS:** re-point the operation to the legacy asset (`IBTRAN`) by redeploying the previous API WAR from the backup, then stop the Java transaction code. The data stays in the same IMS DBDs, so no restore is needed.
- If a fault in a Java version corrupted data, restore the affected rows using the `SHADOW_DIFF` and `PROCTRAN` audit trail before routing traffic again.

---

## Phase 4: Data migration and decommission

### Scope
1. **Optional: move IMS DB to Db2.** Move the 9 HDAM/OSAM databases (`ACCOUNT`, `ACCTYPE`, `CUSTACCS`, `CUSTOMER`, `CUSTTYPE`, `HISTORY`, `TSTAT`, `TSTATTYP`, `TTYPE`) to Db2 tables under `IMSBANK`. The `HISTORY` data is already in Db2.
2. Remove the migrated COBOL and PL/I programs from the build and the deployment.
3. Update the RACF definitions and the documentation.

### Deliverables
- **IMS DB to Db2 (if chosen):**
  - DDL for each segment in `.setup/jcl/ims/Db2-create.j2`.
  - `Db2...Repository` implementations for the IMS-side interfaces.
  - The cut-over runs in three steps:
    1. Dual write: DL/I stays the system of record.
    2. Switch reads to Db2 behind a flag, with reconciliation running.
    3. Make Db2 the system of record.
  - The `LOAD*` programs, the `IBLOAD` PSB, `src/base/ims/LoadData`, and `populate-ims-tables.sh` are replaced by Db2 loaders.
  - Precision stays at `DECIMAL(15,2)` and is not merged with the CICS `DECIMAL(12,2)` without a business decision.
- **Build and deploy clean-up**, for each retired program:
  - Delete its source under `src/base/**`. The `Cobol` and `PLI` tasks select by glob.
  - Delete its `forFiles` entries in `dbb-app.yaml` (for example the `IBTRAN` JNI compile and link options, and the DL/I `linkEditStream` list).
  - Delete its CSD entries in `.setup/zconfig/bank-of-z-definitions.yaml`.
  - Delete its `PGM` and `TRAN` entries in `ims_spoc.jcl.j2`.
  - Delete its PSBs and DBDs from the `Assembler` task. This applies only after the IMS DB move.
  - Delete its DBRM and package binds (`Db2-bind.j2`).
  - Delete deploy types that no longer carry anything (`IMSLOAD`, `PSBLOAD`, `DBDLOAD`, `MAPLOAD` if BMS is retired).
  - Retire the router once every program is in `JAVA` mode. Then point the CICS `PROGRAM` definitions directly at the Java versions (mechanism 1) and drop `BANKZ.ROUTING` and `BANKZSH`.
- **RACF and security:**
  - Update the definitions created by `.setup/setup/setup-cics-region.sh`, `grant-perm-user.sh`, `.setup/jcl/ims/Db2-racf.j2`, and `addcert.sh`.
  - Update any site-managed transaction profiles. The CICS region runs with `SEC=YES` and IMS with `transaction_security_option: Y`.
  - In detail: delete profiles for retired transaction codes and terminal transids, add profiles for the new Java IMS transaction codes, give the JVM server user read access to the application directories on z/OS UNIX, and revoke Db2 `DSNR` and plan or package grants that are no longer used.
- **Documentation:** update `docs/docs/architecture/application-components.md`, `application-flow.md`, `build-and-deployment.md`, `docs/docs/reference/repository-structure.md`, and the glossary. Mark this plan as complete.

### Verification gates
- A full DBB build and Wazi Deploy succeed from a clean sandbox with the retired sources removed, and no remaining program refers to a removed module.
- `tests/run-all.sh` is green, and the full byte-comparison corpus passes against the Java-only system.
- **IMS DB move:**
  - row and segment counts match
  - balance totals match per account
  - a key-by-key compare finds zero differences before the system of record changes
  - a dual-write soak period has passed
- A RACF review shows no orphaned profiles and no missing access. To check, log on as the API and region user IDs and run the full harness.

### Rollback
- **Decommission:** the last package that still contains the COBOL and PL/I load modules is kept, along with the Wazi Deploy backup libraries (`{{ hlq }}.BACK.*`). Redeploy that package and restore the program definitions.
- **IMS DB move:**
  - Before Db2 becomes the system of record, turn the read flag back to DL/I.
  - After it does, run the reverse sync (Db2 to DL/I unload and reload) built and rehearsed as part of this phase. Do not decommission the IMS databases until the agreed retention window has passed.
- **RACF:** the changes are scripted, so re-run the previous version of the setup scripts. Back up the profiles (`RLIST ... ALL` output) before every change.

---

## Summary

| Phase | Production change | Main gate | Rollback lever |
|-------|-------------------|-----------|----------------|
| 0 Assessment | None | Harness and corpus byte-stable on the legacy system | Revert docs and tests |
| 1 Foundation | Router in `LEGACY` mode, new pipeline steps | Fixture round-trip, no-op router diff | Restore program definition, revert pipeline |
| 2 Edge services | `BNKSTMT`, read-only CICS | `SYSPRINT` and COMMAREA byte equality, harness green | Routing row back to `LEGACY`, legacy JCL |
| 3 Core transactions | CICS updates, IMS TM | Shadow soak with zero differences, reconciliation | Routing row, previous API WAR |
| 4 Data and decommission | IMS DB to Db2 (optional), source removal | Clean rebuild, data reconciliation | Last COBOL package, read flag, reverse sync |
