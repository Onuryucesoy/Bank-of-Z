# Gates for Phases 4 and 5 (checklists only; neither phase is started)

Each item needs evidence from z/OS (tapes, snapshot diffs, `ParityGate` return codes); local tests do not count.

## Before Phase 4 (BNK1* chain and BMS maps)
- [ ] Phase 1: `BNKSTMT.jcl` parallel step RC 0 for a full statement cycle; Java loaders' segment files match the COBOL ones.
- [ ] Phase 2: every IMS transaction replayed against its `...J` PSB copy with RC 0; IBTRAN Db2 + IMS commit behaviour confirmed (ESAF/RRS).
- [ ] Phase 3: every program in `cics-programs/PHASE3.md` replayed with RC 0 on both the COMMAREA diff and the ACCOUNT/CUSTOMER/PROCTRAN/CONTROL snapshot diff. Any RC 8 (COMP-3/numeric) is a blocker.
- [ ] Callers switched per program to the Java definitions and run in production for an agreed period with no new abend codes.
- [ ] Decision recorded: port BNK1* to JCICS, or route its functions through `src/api` z/OS Connect + `src/frontend`.

## Phase 4 exit (BMS maps may be removed)
- [ ] Every BNK1* function has a replacement whose captured outputs (COMMAREA or API response) match the legacy ones.
- [ ] No transaction in the CSD still references a `src/base/cics/bms` map set.

## Before Phase 5 (retire copybooks, COBOL/PL/I, DBB config)
- [ ] No legacy program is LINKed, scheduled or started anywhere (CSD, IMS gen, JCL, z/OS Connect services).
- [ ] z/OS Connect assets in `src/api/src/main/zosAssets` regenerated from the Java-side structures, and their request/response bytes diffed against the copybook-based ones.
- [ ] The kept legacy quirks (listed in each PHASE*.md) are each either fixed with sign-off or accepted.
- [ ] Only then: remove the copybooks, `dbb-app.yaml`/`zapp.yaml` COBOL entries, and the COBOL/PL/I sources.
