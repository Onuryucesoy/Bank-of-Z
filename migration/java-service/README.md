# Bank-of-Z Java service (Phase 0/1 scaffold)

Standalone Gradle project (Java 21 toolchain, Jersey 3 / JAX-RS on the JDK HTTP server). It is **not** wired to
Db2 or IMS: every endpoint in `src/api/src/main/api/openapi.yaml` returns a canned, OpenAPI-shaped body from
`src/main/resources/stubs/openapi-stub-responses.json` and reports the z/OS Connect asset it stands in for in the
`X-BankOfZ-Backend` header (`X-BankOfZ-Stub: true`).

```bash
cd migration/java-service
gradle build           # compile + unit tests (test) + contract/golden-master suite (goldenMaster)
gradle run             # serve on http://localhost:8080/api  (PORT env var overrides)
curl localhost:8080/api/customers/0000000001
```

| Package | Contents |
|---|---|
| `api` | `CustomerResource`, `AccountResource`, `ImsResource` (17 operations), `ZosAsset` (14 assets), `StubResponses` |
| `codec` | `CopybookCodec` (IBM037 text, zoned, packed COMP-3, binary COMP/COMP-5, raw 8-digit dates — all numerics as `BigDecimal`), `FieldSpec`, `RecordLayout` |
| `routing` | `CustomerIdRouter` — Java twin of `validateCustomerId`/`parseCustomerId` in `src/frontend/js/utils.js` |

Test suites:

- `src/test` — codec byte-level tests (CP037 code points, sign nibbles, overflow/rounding rejection, no
  `double`/`float` in the public API), routing invariant, stub loading.
- `src/goldenMaster` — loads the generated `zosAssets/*/providerFiles/gen/*Schema.json` and `openapi.yaml`;
  validates schema skeletons, the wave-1 fixtures and every stub response; replays captured traffic from
  `migration/golden-master/recordings` (skipped while empty). See [../golden-master/README.md](../golden-master/README.md).

Gradle reads the repo root via the `bankofz.repoRoot` system property, so tests always use the live contract files.
