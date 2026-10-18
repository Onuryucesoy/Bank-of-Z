# Public API contract (Phase 0 freeze)

Frozen public surface of Bank of Z as exposed through IBM z/OS Connect.
The Java service must serve exactly these routes, shapes and status codes
when traffic is shifted to it (strangler fig), so this document is the
acceptance contract for every later phase.

Sources (all unchanged by this work):

- OpenAPI 3 document: `src/api/src/main/api/openapi.yaml`
- z/OS Connect assets: `src/api/src/main/zosAssets/<asset>/zosAsset.yaml` (backend type, program, transid, CCSID) and `providerFiles/gen/*.cpy` (the copybook views the asset was generated from)
- Operation mappings: `src/api/src/main/operations/<operationId>/{request,response_<status>}.yaml` (JSONata-style expressions binding JSON fields to copybook fields) and `response_mapping.yaml` (status selection)

All CICS assets are `cicsCommarea-1.0` with CCSID 037 and run under
mirror transaction `OMEN` (except `INQTRAND` → `ITRD`, `INQTRANL` →
`ITRL`; `UPDCUST` declares no transid). IMS assets are
`imsTransaction-1.0` and send the gen `INPUT-AREA` message (with
`LL`/`ZZ` `COMP-5` prefix and `TRAN-CODE`) to the MPP of the same name.

**Routing.** The frontend (`src/frontend/js/utils.js` `parseCustomerId`,
`src/frontend/js/api.js`) strips a `C`/`I` prefix from the customer ID and
calls `/customers/...` (CICS) or `/ims/customers/...` (IMS). The API
itself never sees the prefix. See [invariants.md](invariants.md#5-cics--ims-customer-id-routing).

The per-endpoint tables below were extracted mechanically from the mapping
files; expressions are quoted verbatim (long ones may wrap). Field names on
the right-hand side of request mappings and left-hand side of response
sources are the copybook items inventoried in
[data-contracts.md](data-contracts.md).


## Endpoint summary

| # | Method | Path | operationId | z/OS asset | Backend | Program / transaction | Commarea / message copybook |
|---|---|---|---|---|---|---|---|
| 1 | GET | `/accounts` | `getAccounts` | `INQACCS` | CICS (cicsCommarea-1.0) | `INQACCS` / `OMEN` | `INQACCSZ.cpy` |
| 2 | GET | `/accounts/{accountId}` | `getAccount` | `INQACC` | CICS (cicsCommarea-1.0) | `INQACC` / `OMEN` | `INQACCZ.cpy` |
| 3 | GET | `/accounts/{accountId}/balances` | `getAccountBalances` | `INQACC` | CICS (cicsCommarea-1.0) | `INQACC` / `OMEN` | `INQACCZ.cpy` |
| 4 | POST | `/accounts/{accountId}/deposit` | `depositToAccount` | `DBCRFUN` | CICS (cicsCommarea-1.0) | `DBCRFUN` / `OMEN` | `PAYDBCRZ.cpy` |
| 5 | GET | `/accounts/{accountId}/transactions` | `getAccountTransactions` | `INQTRANL` | CICS (cicsCommarea-1.0) | `INQTRANL` / `ITRL` | `INQTRANL.cpy` |
| 6 | GET | `/accounts/{accountId}/transactions/{transactionId}` | `getTransaction` | `INQTRAND` | CICS (cicsCommarea-1.0) | `INQTRAND` / `ITRD` | `INQTRAND.cpy` |
| 7 | POST | `/customers` | `createCustomer` | `CRECUST` | CICS (cicsCommarea-1.0) | `CRECUST` / `OMEN` | `CRECUST.cpy` |
| 8 | DELETE | `/customers/{customerId}` | `deleteCustomer` | `DELCUS` | CICS (cicsCommarea-1.0) | `DELCUS` / `OMEN` | `DELCUS.cpy` |
| 9 | GET | `/customers/{customerId}` | `getCustomer` | `INQCUST` | CICS (cicsCommarea-1.0) | `INQCUST` / `OMEN` | `INQCUSTZ.cpy` |
| 10 | PUT | `/customers/{customerId}` | `updateCustomer` | `UPDCUST` | CICS (cicsCommarea-1.0) | `UPDCUST` / `CICS default` | `UPDCUST.cpy` |
| 11 | GET | `/customers/{customerId}/accounts` | `getCustomerAccounts` | `INQACCCU` | CICS (cicsCommarea-1.0) | `INQACCCU` / `OMEN` | `INQACCCZ.cpy` |
| 12 | GET | `/ims/accounts/{accountId}` | `getIMSAccount` | `IBACSUM` | IMS (imsTransaction-1.0) | `IBACSUM` / `IMS tran IBACSUM` | `IBACSUM gen INPUT-AREA/OUTPUT-AREA` |
| 13 | GET | `/ims/accounts/{accountId}/balances` | `getIMSAccountBalances` | `IBACSUM` | IMS (imsTransaction-1.0) | `IBACSUM` / `IMS tran IBACSUM` | `IBACSUM gen INPUT-AREA/OUTPUT-AREA` |
| 14 | POST | `/ims/accounts/{customerId}/{accountId}/deposit` | `depositToIMSAccount` | `IBTRAN` | IMS (imsTransaction-1.0) | `IBTRAN` / `IMS tran IBTRAN` | `IBTRAN.cpy` |
| 15 | GET | `/ims/customers/{customerId}` | `getIMSCustomer` | `IBGCUDAT` | IMS (imsTransaction-1.0) | `IBGCUDAT` / `IMS tran IBGCUDAT` | `IBGCUDAT gen INPUT-AREA/OUTPUT-AREA` |
| 16 | PUT | `/ims/customers/{customerId}` | `updateIMSCustomer` | `IBSCUDAT` | IMS (imsTransaction-1.0) | `IBSCUDAT` / `IMS tran IBSCUDAT` | `IBSCUDAT gen INPUT-AREA/OUTPUT-AREA` |
| 17 | GET | `/ims/customers/{customerId}/accounts` | `getIMSCustomerAccounts` | `IBACSUM` | IMS (imsTransaction-1.0) | `IBACSUM` / `IMS tran IBACSUM` | `IBACSUM gen INPUT-AREA/OUTPUT-AREA` |

17 operations over 14 paths.


## Endpoint detail


### 1. GET `/accounts` — `getAccounts`

- z/OS asset: `src/api/src/main/zosAssets/INQACCS/zosAsset.yaml` → CICS `INQACCS` (transid `OMEN`)
- Mapping files: `src/api/src/main/operations/getAccounts/`
- Parameters: `accountType` (query), `status` (query)
- Request body schema: `—`
- Responses: 200 `AccountList`, 400 `—`, 401 `—`, 500 `—`


`response_200.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.accounts` | `None` |
| `body.totalCount` | `$count($zosAssetResponse.commarea.INQACCS."ACCOUNT-DETAILS")` |

HTTP status selection (`response_*.yaml` conditions):

- response_200.yaml: {'condition': '$exists($zosAssetResponse.commarea.INQACCS) or $not($exists($zosAssetResponse.abendCode))', 'httpStatusCode': 200}
- response_400.yaml: {'httpStatusCode': 400}
- response_401.yaml: {'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_404.yaml: {'httpStatusCode': 404}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 500 return constant `{code, message}` bodies (see mapping files).

### 2. GET `/accounts/{accountId}` — `getAccount`

- z/OS asset: `src/api/src/main/zosAssets/INQACC/zosAsset.yaml` → CICS `INQACC` (transid `OMEN`)
- Mapping files: `src/api/src/main/operations/getAccount/`
- Parameters: `accountId` (path)
- Request body schema: `—`
- Responses: 200 `Account`, 400 `—`, 401 `—`, 403 `—`, 404 `—`, 500 `—`


`request.yaml` (API → copybook)

| Target | Source / expression |
|---|---|
| `commarea.INQACC-COMMAREA.INQACC-ACCNO` | `$pathParameters.accountId` |

`response_200.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.accountId` | `{{$apiRequest.pathParameters.accountId}}` |
| `body.accountType` | `{{$zosAssetResponse.commarea."INQACC-COMMAREA"."INQACC-ACC-TYPE" = 'ISA' ? 'SAVINGS' : $zosAssetResponse.commarea."INQACC-COMMAREA"."INQACC-ACC-TYPE" = 'MORTGAGE' ? 'LOAN' : 'CURRENT'}}` |
| `body.currency` | `GBP` |
| `body.accountNumber` | `{{$zosAssetResponse.commarea."INQACC-COMMAREA"."INQACC-ACCNO"}}` |
| `body.sortCode` | `{{$zosAssetResponse.commarea."INQACC-COMMAREA"."INQACC-SCODE"}}` |
| `body.status` | `ACTIVE` |
| `body.openingDate` | `{{$zosAssetResponse.commarea."INQACC-COMMAREA"."INQACC-OPENED-GROUP"."INQACC-OPENED-YEAR"}}-{{$zosAssetResponse.commarea."INQACC-COMMAREA"."INQACC-OPENED-GROUP"."INQACC-OPENED-MONTH"}}-{{$zosAssetResponse.commarea."INQACC-COMMAREA"."INQACC-OPENED-GROUP"."INQACC-OPENED-DAY"}}` |
| `body.customerId` | `{{$zosAssetResponse.commarea."INQACC-COMMAREA"."INQACC-CUSTNO"}}` |

HTTP status selection (`response_*.yaml` conditions):

- response_200.yaml: {'condition': '$exists($zosAssetResponse.commarea."INQACC-COMMAREA") or $not($exists($zosAssetResponse.abendCode))', 'httpStatusCode': 200}
- response_400.yaml: {'httpStatusCode': 400}
- response_401.yaml: {'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_404.yaml: {'httpStatusCode': 404}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 403, 404, 500 return constant `{code, message}` bodies (see mapping files).

### 3. GET `/accounts/{accountId}/balances` — `getAccountBalances`

- z/OS asset: `src/api/src/main/zosAssets/INQACC/zosAsset.yaml` → CICS `INQACC` (transid `OMEN`)
- Mapping files: `src/api/src/main/operations/getAccountBalances/`
- Parameters: `accountId` (path)
- Request body schema: `—`
- Responses: 200 `Balance`, 400 `—`, 401 `—`, 403 `—`, 404 `—`, 500 `—`


`request.yaml` (API → copybook)

| Target | Source / expression |
|---|---|
| `commarea.INQACC-COMMAREA.INQACC-ACCNO` | `$pathParameters.accountId` |

`response_200.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.balanceType` | `AVAILABLE` |
| `body.amount` | `$zosAssetResponse.commarea."INQACC-COMMAREA"."INQACC-AVAIL-BAL"` |
| `body.currency` | `GBP` |
| `body.creditDebitIndicator` | `{{$zosAssetResponse.commarea."INQACC-COMMAREA"."INQACC-AVAIL-BAL" < 0 ? 'DEBIT' : 'CREDIT'}}` |
| `body.dateTime` | `{{$zosAssetResponse.commarea."INQACC-COMMAREA"."INQACC-LAST-STMT-GROUP"."INQACC-LAST-STMT-YEAR"}}-{{$zosAssetResponse.commarea."INQACC-COMMAREA"."INQACC-LAST-STMT-GROUP"."INQACC-LAST-STMT-MONTH"}}-{{$zosAssetResponse.commarea."INQACC-COMMAREA"."INQACC-LAST-STMT-GROUP"."INQACC-LAST-STMT-DAY"}}T00:00:00Z` |

HTTP status selection (`response_*.yaml` conditions):

- response_200.yaml: {'condition': '$exists($zosAssetResponse.commarea."INQACC-COMMAREA") or $not($exists($zosAssetResponse.abendCode))', 'httpStatusCode': 200}
- response_400.yaml: {'httpStatusCode': 400}
- response_401.yaml: {'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_404.yaml: {'httpStatusCode': 404}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 403, 404, 500 return constant `{code, message}` bodies (see mapping files).

### 4. POST `/accounts/{accountId}/deposit` — `depositToAccount`

- z/OS asset: `src/api/src/main/zosAssets/DBCRFUN/zosAsset.yaml` → CICS `DBCRFUN` (transid `OMEN`)
- Mapping files: `src/api/src/main/operations/depositToAccount/`
- Parameters: `accountId` (path)
- Request body schema: `DepositRequest`
- Responses: 201 `DepositResponse`, 400 `—`, 401 `—`, 403 `—`, 404 `—`, 500 `—`


`request.yaml` (API → copybook)

| Target | Source / expression |
|---|---|
| `commarea.PAYDBCRZ.COMM-ACCNO` | `$substring('00000000', 0, 8 - $length($string($number($pathParameters.accountId)))) & $string($number($pathParameters.accountId))` |
| `commarea.PAYDBCRZ.COMM-AMT` | `$body.amount` |
| `commarea.PAYDBCRZ.COMM-SORTC` | `$number($body.sortCode)` |
| `commarea.PAYDBCRZ.COMM-ORIGIN.COMM-APPLID` | `None` |
| `commarea.PAYDBCRZ.COMM-ORIGIN.COMM-USERID` | `None` |
| `commarea.PAYDBCRZ.COMM-ORIGIN.COMM-FACILITY-NAME` | `None` |
| `commarea.PAYDBCRZ.COMM-ORIGIN.COMM-NETWRK-ID` | `None` |
| `commarea.PAYDBCRZ.COMM-ORIGIN.COMM-FACILTYPE` | `496` |
| `commarea.PAYDBCRZ.COMM-ORIGIN.COMM-DESCRIPTION` | `$body.description ? $body.description : ''` |

`response_201.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.accountId` | `{{$zosAssetResponse.commarea.PAYDBCRZ."COMM-ACCNO"}}` |
| `body.availableBalance` | `$zosAssetResponse.commarea.PAYDBCRZ."COMM-AV-BAL"` |
| `body.actualBalance` | `$zosAssetResponse.commarea.PAYDBCRZ."COMM-ACT-BAL"` |

HTTP status selection (`response_*.yaml` conditions):

- response_404.yaml: {'condition': '$exists($zosAssetResponse.commarea.PAYDBCRZ) and $zosAssetResponse.commarea.PAYDBCRZ."COMM-SUCCESS" != "Y"', 'httpStatusCode': 404}
- response_201.yaml: {'condition': '$zosAssetResponse.commarea.PAYDBCRZ."COMM-SUCCESS" = "Y"', 'httpStatusCode': 201}
- response_401.yaml: {'condition': '$not($exists($zosAssetResponse.commarea.PAYDBCRZ))', 'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 403, 404, 500 return constant `{code, message}` bodies (see mapping files).

### 5. GET `/accounts/{accountId}/transactions` — `getAccountTransactions`

- z/OS asset: `src/api/src/main/zosAssets/INQTRANL/zosAsset.yaml` → CICS `INQTRANL` (transid `ITRL`)
- Mapping files: `src/api/src/main/operations/getAccountTransactions/`
- Parameters: `accountId` (path), `fromDate` (query), `toDate` (query), `limit` (query), `offset` (query)
- Request body schema: `—`
- Responses: 200 `TransactionList`, 400 `—`, 401 `—`, 403 `—`, 404 `—`, 500 `—`


`request.yaml` (API → copybook)

| Target | Source / expression |
|---|---|
| `commarea.INQTRANL-COMMAREA.INQTRANL-EYE` | `ITRL` |
| `commarea.INQTRANL-COMMAREA.INQTRANL-SORTCODE` | `$number($substringBefore($pathParameters.accountId, '-'))` |
| `commarea.INQTRANL-COMMAREA.INQTRANL-ACCNO` | `$number($substringAfter($pathParameters.accountId, '-'))` |
| `commarea.INQTRANL-COMMAREA.INQTRANL-FROM-DATE` | `$exists($queryParameters.fromDate) ? $number($replace($queryParameters.fromDate, '-', '')) : 20200101` |
| `commarea.INQTRANL-COMMAREA.INQTRANL-TO-DATE` | `$exists($queryParameters.toDate) ? $number($replace($queryParameters.toDate, '-', '')) : 20301231` |
| `commarea.INQTRANL-COMMAREA.INQTRANL-LIMIT` | `$exists($queryParameters.limit) ? $number($queryParameters.limit) : 50` |
| `commarea.INQTRANL-COMMAREA.INQTRANL-OFFSET` | `$exists($queryParameters.offset) ? $number($queryParameters.offset) : 0` |

`response_200.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.transactions` | `None` |
| `body.totalCount` | `$zosAssetResponse.commarea."INQTRANL-COMMAREA"."INQTRANL-TOTAL-COUNT"` |
| `body.limit` | `$zosAssetResponse.commarea."INQTRANL-COMMAREA"."INQTRANL-LIMIT"` |
| `body.offset` | `$zosAssetResponse.commarea."INQTRANL-COMMAREA"."INQTRANL-OFFSET"` |

HTTP status selection (`response_*.yaml` conditions):

- response_200.yaml: {'condition': '$exists($zosAssetResponse.commarea."INQTRANL-COMMAREA") and $zosAssetResponse.commarea."INQTRANL-COMMAREA"."INQTRANL-SUCCESS" = \'Y\' or $not($exists($zosAssetResponse.abendCode))', 'httpStatusCode': 200}
- response_400.yaml: {'httpStatusCode': 400}
- response_401.yaml: {'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_404.yaml: {'httpStatusCode': 404}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 403, 404, 500 return constant `{code, message}` bodies (see mapping files).

### 6. GET `/accounts/{accountId}/transactions/{transactionId}` — `getTransaction`

- z/OS asset: `src/api/src/main/zosAssets/INQTRAND/zosAsset.yaml` → CICS `INQTRAND` (transid `ITRD`)
- Mapping files: `src/api/src/main/operations/getTransaction/`
- Parameters: `accountId` (path), `transactionId` (path)
- Request body schema: `—`
- Responses: 200 `Transaction`, 400 `—`, 401 `—`, 403 `—`, 404 `—`, 500 `—`


`request.yaml` (API → copybook)

| Target | Source / expression |
|---|---|
| `commarea.INQTRAND-COMMAREA.INQTRAND-EYE` | `ITRD` |
| `commarea.INQTRAND-COMMAREA.INQTRAND-SORTCODE` | `$number($substringBefore($pathParameters.accountId, '-'))` |
| `commarea.INQTRAND-COMMAREA.INQTRAND-ACCNO` | `$number($substringAfter($pathParameters.accountId, '-'))` |
| `commarea.INQTRAND-COMMAREA.INQTRAND-DATE` | `$number($substringBefore($substringAfter($substringAfter($pathParameters.transactionId, '-'), '-'), '-'))` |
| `commarea.INQTRAND-COMMAREA.INQTRAND-TIME` | `$number($substringBefore($substringAfter($substringAfter($substringAfter($pathParameters.transactionId, '-'), '-'), '-'), '-'))` |
| `commarea.INQTRAND-COMMAREA.INQTRAND-REF` | `$number($substringAfter($substringAfter($substringAfter($substringAfter($pathParameters.transactionId, '-'), '-'), '-'), '-'))` |

`response_200.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.transactionId` | `{{$zosAssetResponse.commarea."INQTRAND-COMMAREA"."INQTRAND-TRAN-ID"}}` |
| `body.accountId` | `{{$zosAssetResponse.commarea."INQTRAND-COMMAREA"."INQTRAND-ACCNO"}}` |
| `body.amount` | `$abs($zosAssetResponse.commarea."INQTRAND-COMMAREA"."INQTRAND-TRAN-AMOUNT")` |
| `body.currency` | `GBP` |
| `body.bookingDateTime` | `{{$zosAssetResponse.commarea."INQTRAND-COMMAREA"."INQTRAND-TRAN-DATE-GRP"."INQTRAND-TRAN-DATE-YYYY"}}-{{$zosAssetResponse.commarea."INQTRAND-COMMAREA"."INQTRAND-TRAN-DATE-GRP"."INQTRAND-TRAN-DATE-MM"}}-{{$zosAssetResponse.commarea."INQTRAND-COMMAREA"."INQTRAND-TRAN-DATE-GRP"."INQTRAND-TRAN-DATE-DD"}}` |

HTTP status selection (`response_*.yaml` conditions):

- response_200.yaml: {'condition': '$exists($zosAssetResponse.commarea."INQTRAND-COMMAREA") and $zosAssetResponse.commarea."INQTRAND-COMMAREA"."INQTRAND-FOUND" = \'Y\'', 'httpStatusCode': 200}
- response_404.yaml: {'condition': '$exists($zosAssetResponse.commarea."INQTRAND-COMMAREA") and $zosAssetResponse.commarea."INQTRAND-COMMAREA"."INQTRAND-FOUND" = \'N\'', 'httpStatusCode': 404}
- response_400.yaml: {'httpStatusCode': 400}
- response_401.yaml: {'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 403, 404, 500 return constant `{code, message}` bodies (see mapping files).

### 7. POST `/customers` — `createCustomer`

- z/OS asset: `src/api/src/main/zosAssets/CRECUST/zosAsset.yaml` → CICS `CRECUST` (transid `OMEN`)
- Mapping files: `src/api/src/main/operations/createCustomer/`
- Parameters: —
- Request body schema: `CreateCustomerRequest`
- Responses: 201 `CreatedCustomer`, 400 `—`, 401 `—`, 403 `—`, 500 `—`


`request.yaml` (API → copybook)

| Target | Source / expression |
|---|---|
| `commarea.CRECUSTZ.COMM-NAME.COMM-TITLE` | `{{$body.title}}` |
| `commarea.CRECUSTZ.COMM-NAME.COMM-FIRST-NAME` | `{{$exists(body.firstName) ? $body.firstName : ""}}` |
| `commarea.CRECUSTZ.COMM-NAME.COMM-LAST-NAME` | `{{$exists(body.lastName) ? $body.lastName : ""}}` |
| `commarea.CRECUSTZ.COMM-DOB.COMM-DOB-DAY` | `$exists($body.dateOfBirth) and $match($body.dateOfBirth, /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/) ? $number($substring($body.dateOfBirth, 8, 2)) : 0` |
| `commarea.CRECUSTZ.COMM-DOB.COMM-DOB-MONTH` | `$exists($body.dateOfBirth) and $match($body.dateOfBirth, /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/) ? $number($substring($body.dateOfBirth, 5, 2)) : 0` |
| `commarea.CRECUSTZ.COMM-DOB.COMM-DOB-YEAR` | `$exists($body.dateOfBirth) and $match($body.dateOfBirth, /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/) ? $number($substring($body.dateOfBirth, 0, 4)) : 0` |
| `commarea.CRECUSTZ.COMM-PHONE` | `{{$body.phoneNumber}}` |
| `commarea.CRECUSTZ.COMM-ADDR.COMM-ADDR-LINE1` | `{{$body.address.addressLine1}}` |
| `commarea.CRECUSTZ.COMM-ADDR.COMM-ADDR-LINE2` | `{{$body.address.addressLine2}}` |
| `commarea.CRECUSTZ.COMM-ADDR.COMM-CITY` | `{{$body.address.city}}` |
| `commarea.CRECUSTZ.COMM-ADDR.COMM-POSTCODE` | `{{$body.address.postalCode}}` |
| `commarea.CRECUSTZ.COMM-ADDR.COMM-COUNTRY` | `{{$body.address.country}}` |
| `commarea.CRECUSTZ.COMM-STATUS` | `{{$body.customerStatus}}` |

`response_201.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.customerId` | `{{$zosAssetResponse.commarea.CRECUSTZ."COMM-KEY"."COMM-NUMBER"}}` |
| `body.sortCode` | `{{$zosAssetResponse.commarea.CRECUSTZ."COMM-KEY"."COMM-SORTCODE"}}` |

HTTP status selection (`response_*.yaml` conditions):

- response_201.yaml: {'condition': '$zosAssetResponse.commarea.CRECUSTZ."COMM-SUCCESS" = "Y"', 'httpStatusCode': 201}
- response_400.yaml: {'condition': '$zosAssetResponse.commarea.CRECUSTZ."COMM-SUCCESS" = "N"', 'httpStatusCode': 400}
- response_401.yaml: {'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 403, 500 return constant `{code, message}` bodies (see mapping files).

### 8. DELETE `/customers/{customerId}` — `deleteCustomer`

- z/OS asset: `src/api/src/main/zosAssets/DELCUS/zosAsset.yaml` → CICS `DELCUS` (transid `OMEN`)
- Mapping files: `src/api/src/main/operations/deleteCustomer/`
- Parameters: `customerId` (path)
- Request body schema: `—`
- Responses: 204 `—`, 400 `—`, 401 `—`, 403 `—`, 404 `—`, 500 `—`


`request.yaml` (API → copybook)

| Target | Source / expression |
|---|---|
| `commarea.DELCUSZ.COMM-CUSTNO` | `$formatNumber($number($pathParameters.customerId), '0000000000')` |
| `commarea.DELCUSZ.COMM-SCODE` | `987654` |

HTTP status selection (`response_*.yaml` conditions):

- response_204.yaml: {'condition': '$zosAssetResponse.commarea.DELCUSZ."COMM-DEL-SUCCESS" = \'Y\'', 'httpStatusCode': 204}
- response_400.yaml: {'httpStatusCode': 400}
- response_401.yaml: {'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_404.yaml: {'condition': '$exists($zosAssetResponse.commarea.DELCUSZ) and $zosAssetResponse.commarea.DELCUSZ."COMM-DEL-SUCCESS" != \'Y\'', 'httpStatusCode': 404}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 403, 404, 500 return constant `{code, message}` bodies (see mapping files).

### 9. GET `/customers/{customerId}` — `getCustomer`

- z/OS asset: `src/api/src/main/zosAssets/INQCUST/zosAsset.yaml` → CICS `INQCUST` (transid `OMEN`)
- Mapping files: `src/api/src/main/operations/getCustomer/`
- Parameters: `customerId` (path)
- Request body schema: `—`
- Responses: 200 `Customer`, 400 `—`, 401 `—`, 403 `—`, 404 `—`, 500 `—`


`request.yaml` (API → copybook)

| Target | Source / expression |
|---|---|
| `commarea.INQCUSTZ.INQCUST-CUSTNO` | `$pathParameters.customerId` |

`response_200.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.customerId` | `{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-CUSTNO"}}` |
| `body.title` | `{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-NAME"."INQCUST-TITLE"}}` |
| `body.firstName` | `{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-NAME"."INQCUST-FIRST-NAME"}}` |
| `body.lastName` | `{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-NAME"."INQCUST-LAST-NAME"}}` |
| `body.dateOfBirth` | `{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-DOB"."INQCUST-DOB-YYYY"}}-{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-DOB"."INQCUST-DOB-MM"}}-{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-DOB"."INQCUST-DOB-DD"}}` |
| `body.phoneNumber` | `{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-PHONE"}}` |
| `body.address.addressLine1` | `{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-ADDR"."INQCUST-ADDR-LINE1"}}` |
| `body.address.addressLine2` | `{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-ADDR"."INQCUST-ADDR-LINE2"}}` |
| `body.address.city` | `{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-ADDR"."INQCUST-CITY"}}` |
| `body.address.postalCode` | `{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-ADDR"."INQCUST-POSTCODE"}}` |
| `body.address.country` | `{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-ADDR"."INQCUST-COUNTRY"}}` |
| `body.customerStatus` | `{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-STATUS"}}` |
| `body.createdDate` | `{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-CREATED-DATE"."INQCUST-CREATED-YYYY"}}-{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-CREATED-DATE"."INQCUST-CREATED-MM"}}-{{$zosAssetResponse.commarea.INQCUSTZ."INQCUST-CREATED-DATE"."INQCUST-CREATED-DD"}}T00:00:00Z` |

`response_500.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.code` | `{{$zosAssetResponse.abendCode}}` |
| `body.message` | `ERROR` |

HTTP status selection (`response_*.yaml` conditions):

- response_200.yaml: {'condition': '$zosAssetResponse.commarea.INQCUSTZ."INQCUST-INQ-SUCCESS" = \'Y\'', 'httpStatusCode': 200}
- response_400.yaml: {'httpStatusCode': 400}
- response_401.yaml: {'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_404.yaml: {'condition': '$exists($zosAssetResponse.commarea.INQCUSTZ) and $zosAssetResponse.commarea.INQCUSTZ."INQCUST-INQ-SUCCESS" != \'Y\'', 'httpStatusCode': 404}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 403, 404, 500 return constant `{code, message}` bodies (see mapping files).

### 10. PUT `/customers/{customerId}` — `updateCustomer`

- z/OS asset: `src/api/src/main/zosAssets/UPDCUST/zosAsset.yaml` → CICS `UPDCUST` (transid `CICS default`)
- Mapping files: `src/api/src/main/operations/updateCustomer/`
- Parameters: `customerId` (path)
- Request body schema: `CustomerUpdate`
- Responses: 200 `Customer`, 400 `—`, 401 `—`, 403 `—`, 404 `—`, 500 `—`


`request.yaml` (API → copybook)

| Target | Source / expression |
|---|---|
| `commarea.UPDCUST.COMM-CUSTNO` | `{{$pathParameters.customerId}}` |
| `commarea.UPDCUST.COMM-NAME.COMM-TITLE` | `{{$body.title}}` |
| `commarea.UPDCUST.COMM-NAME.COMM-FIRST-NAME` | `{{$body.firstName}}` |
| `commarea.UPDCUST.COMM-NAME.COMM-LAST-NAME` | `{{$body.lastName}}` |
| `commarea.UPDCUST.COMM-DOB.COMM-DOB-DAY` | `$number($substring($requestBody.dateOfBirth, 8, 2))` |
| `commarea.UPDCUST.COMM-DOB.COMM-DOB-MONTH` | `$number($substring($requestBody.dateOfBirth, 5, 2))` |
| `commarea.UPDCUST.COMM-DOB.COMM-DOB-YEAR` | `$number($substring($requestBody.dateOfBirth, 0, 4))` |
| `commarea.UPDCUST.COMM-PHONE` | `{{$body.phoneNumber}}` |
| `commarea.UPDCUST.COMM-ADDR.COMM-ADDR-LINE1` | `{{$body.address.addressLine1}}` |
| `commarea.UPDCUST.COMM-ADDR.COMM-ADDR-LINE2` | `{{$body.address.addressLine2}}` |
| `commarea.UPDCUST.COMM-ADDR.COMM-CITY` | `{{$body.address.city}}` |
| `commarea.UPDCUST.COMM-ADDR.COMM-POSTCODE` | `{{$body.address.postalCode}}` |
| `commarea.UPDCUST.COMM-ADDR.COMM-COUNTRY` | `{{$body.address.country}}` |
| `commarea.UPDCUST.COMM-STATUS` | `{{$body.customerStatus}}` |

`response_200.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.customerId` | `{{$zosAssetResponse.commarea.UPDCUST."COMM-CUSTNO"}}` |
| `body.title` | `{{$zosAssetResponse.commarea.UPDCUST."COMM-NAME"."COMM-TITLE"}}` |
| `body.firstName` | `{{$zosAssetResponse.commarea.UPDCUST."COMM-NAME"."COMM-FIRST-NAME"}}` |
| `body.lastName` | `{{$zosAssetResponse.commarea.UPDCUST."COMM-NAME"."COMM-LAST-NAME"}}` |
| `body.dateOfBirth` | `{{$zosAssetResponse.commarea.UPDCUST."COMM-DOB"."COMM-DOB-YEAR"}}-{{$zosAssetResponse.commarea.UPDCUST."COMM-DOB"."COMM-DOB-MONTH"}}-{{$zosAssetResponse.commarea.UPDCUST."COMM-DOB"."COMM-DOB-DAY"}}` |
| `body.phoneNumber` | `{{$zosAssetResponse.commarea.UPDCUST."COMM-PHONE"}}` |
| `body.address.addressLine1` | `{{$zosAssetResponse.commarea.UPDCUST."COMM-ADDR"."COMM-ADDR-LINE1"}}` |
| `body.address.addressLine2` | `{{$zosAssetResponse.commarea.UPDCUST."COMM-ADDR"."COMM-ADDR-LINE2"}}` |
| `body.address.city` | `{{$zosAssetResponse.commarea.UPDCUST."COMM-ADDR"."COMM-CITY"}}` |
| `body.address.postalCode` | `{{$zosAssetResponse.commarea.UPDCUST."COMM-ADDR"."COMM-POSTCODE"}}` |
| `body.address.country` | `{{$zosAssetResponse.commarea.UPDCUST."COMM-ADDR"."COMM-COUNTRY"}}` |
| `body.customerStatus` | `{{$zosAssetResponse.commarea.UPDCUST."COMM-STATUS"}}` |
| `body.createdDate` | `{{$zosAssetResponse.commarea.UPDCUST."COMM-CREATED-DATE"."COMM-CREATED-YEAR"}}-{{$zosAssetResponse.commarea.UPDCUST."COMM-CREATED-DATE"."COMM-CREATED-MONTH"}}-{{$zosAssetResponse.commarea.UPDCUST."COMM-CREATED-DATE"."COMM-CREATED-DAY"}}T00:00:00Z` |

HTTP status selection (`response_*.yaml` conditions):

- response_200.yaml: {'condition': '$zosAssetResponse.commarea.UPDCUST."COMM-UPD-SUCCESS" = \'Y\'', 'httpStatusCode': 200}
- response_400.yaml: {'httpStatusCode': 400}
- response_401.yaml: {'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_404.yaml: {'httpStatusCode': 404}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 403, 404, 500 return constant `{code, message}` bodies (see mapping files).

### 11. GET `/customers/{customerId}/accounts` — `getCustomerAccounts`

- z/OS asset: `src/api/src/main/zosAssets/INQACCCU/zosAsset.yaml` → CICS `INQACCCU` (transid `OMEN`)
- Mapping files: `src/api/src/main/operations/getCustomerAccounts/`
- Parameters: `customerId` (path)
- Request body schema: `—`
- Responses: 200 `AccountList`, 400 `—`, 401 `—`, 403 `—`, 404 `—`, 500 `—`


`request.yaml` (API → copybook)

| Target | Source / expression |
|---|---|
| `commarea.INQACCCZ.CUSTOMER-NUMBER` | `$pathParameters.customerId` |

`response_200.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.accounts` | `None` |
| `body.totalCount` | `$count($zosAssetResponse.commarea.INQACCCZ."ACCOUNT-DETAILS")` |

HTTP status selection (`response_*.yaml` conditions):

- response_200.yaml: {'condition': '$exists($zosAssetResponse.commarea.INQACCCZ) or $not($exists($zosAssetResponse.abendCode))', 'httpStatusCode': 200}
- response_400.yaml: {'httpStatusCode': 400}
- response_401.yaml: {'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_404.yaml: {'httpStatusCode': 404}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 403, 404, 500 return constant `{code, message}` bodies (see mapping files).

### 12. GET `/ims/accounts/{accountId}` — `getIMSAccount`

- z/OS asset: `src/api/src/main/zosAssets/IBACSUM/zosAsset.yaml` → IMS `IBACSUM` (transid `IMS tran IBACSUM`)
- Mapping files: `src/api/src/main/operations/getIMSAccount/`
- Parameters: `accountId` (path)
- Request body schema: `—`
- Responses: 200 `Account`, 400 `—`, 401 `—`, 403 `—`, 404 `—`, 500 `—`


`request.yaml` (API → copybook)

| Target | Source / expression |
|---|---|
| `message.INPUT-AREA.TRAN-CODE` | `IBACSUM` |
| `message.INPUT-AREA.IN-CUSTID` | `{{$pathParameters.accountId}}` |

`response_200.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.accountId` | `{{$zosAssetResponse.message."OUTPUT-AREA"."ACCOUNT-SUMMARY"."ACCID-AS"}}` |
| `body.accountType` | `{{ $item."ACCTYPE-AS"  = 's' ? 'SAVINGS' : $item."ACCTYPE-AS" = 'c' ? 'CHECKING' : 'CURRENT'}}` |
| `body.currency` | `USD` |
| `body.status` | `ACTIVE` |

HTTP status selection (`response_*.yaml` conditions):

- response_200.yaml: {'condition': '$exists($zosAssetResponse.message."OUTPUT-AREA"."ACCOUNT-SUMMARY")', 'httpStatusCode': 200}
- response_400.yaml: {'httpStatusCode': 400}
- response_401.yaml: {'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_404.yaml: {'httpStatusCode': 404}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 403, 404, 500 return constant `{code, message}` bodies (see mapping files).

### 13. GET `/ims/accounts/{accountId}/balances` — `getIMSAccountBalances`

- z/OS asset: `src/api/src/main/zosAssets/IBACSUM/zosAsset.yaml` → IMS `IBACSUM` (transid `IMS tran IBACSUM`)
- Mapping files: `src/api/src/main/operations/getIMSAccountBalances/`
- Parameters: `accountId` (path)
- Request body schema: `—`
- Responses: 200 `Balance`, 400 `—`, 401 `—`, 403 `—`, 404 `—`, 500 `—`


`request.yaml` (API → copybook)

| Target | Source / expression |
|---|---|
| `message.INPUT-AREA.TRAN-CODE` | `IBACSUM` |
| `message.INPUT-AREA.IN-CUSTID` | `{{$pathParameters.accountId}}` |

`response_200.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.balanceType` | `AVAILABLE` |
| `body.amount` | `$zosAssetResponse.message."OUTPUT-AREA"."ACCOUNT-SUMMARY"."BALANCE-AS"` |
| `body.currency` | `USD` |
| `body.dateTime` | `{{$now()}}` |

HTTP status selection (`response_*.yaml` conditions):

- response_200.yaml: {'condition': '$exists($zosAssetResponse.message."OUTPUT-AREA"."ACCOUNT-SUMMARY")', 'httpStatusCode': 200}
- response_400.yaml: {'httpStatusCode': 400}
- response_401.yaml: {'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_404.yaml: {'httpStatusCode': 404}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 403, 404, 500 return constant `{code, message}` bodies (see mapping files).

### 14. POST `/ims/accounts/{customerId}/{accountId}/deposit` — `depositToIMSAccount`

- z/OS asset: `src/api/src/main/zosAssets/IBTRAN/zosAsset.yaml` → IMS `IBTRAN` (transid `IMS tran IBTRAN`)
- Mapping files: `src/api/src/main/operations/depositToIMSAccount/`
- Parameters: `customerId` (path), `accountId` (path)
- Request body schema: `DepositRequest`
- Responses: 201 `DepositResponse`, 400 `—`, 401 `—`, 403 `—`, 404 `—`, 500 `—`


`request.yaml` (API → copybook)

| Target | Source / expression |
|---|---|
| `message.INPUT-AREA.TRAN-CODE` | `IBTRAN` |
| `message.INPUT-AREA.IN-ACCID` | `{{$pathParameters.accountId}}` |
| `message.INPUT-AREA.IN-AMOUNT` | `{{$body.amount}}` |
| `message.INPUT-AREA.IN-TRXTYPE` | `D` |
| `message.INPUT-AREA.IN-CUSTID` | `{{$pathParameters.customerId}}` |

`response_201.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.accountId` | `{{$zosAssetResponse.message."OUTPUT-AREA"."ACCOUNT-SUMMARY"."ACCID-AS"}}` |
| `body.availableBalance` | `$zosAssetResponse.message."OUTPUT-AREA"."ACCOUNT-SUMMARY"."BALANCE-AS"` |
| `body.actualBalance` | `$zosAssetResponse.message."OUTPUT-AREA"."ACCOUNT-SUMMARY"."BALANCE-AS"` |

HTTP status selection (`response_*.yaml` conditions):

- response_201.yaml: {'condition': '$exists($zosAssetResponse.message."OUTPUT-AREA"."MSG-OUT")', 'httpStatusCode': 201}
- response_400.yaml: {'httpStatusCode': 400}
- response_401.yaml: {'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_404.yaml: {'httpStatusCode': 404}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 403, 404, 500 return constant `{code, message}` bodies (see mapping files).

### 15. GET `/ims/customers/{customerId}` — `getIMSCustomer`

- z/OS asset: `src/api/src/main/zosAssets/IBGCUDAT/zosAsset.yaml` → IMS `IBGCUDAT` (transid `IMS tran IBGCUDAT`)
- Mapping files: `src/api/src/main/operations/getIMSCustomer/`
- Parameters: `customerId` (path)
- Request body schema: `—`
- Responses: 200 `Customer`, 400 `—`, 401 `—`, 403 `—`, 404 `—`, 500 `—`


`request.yaml` (API → copybook)

| Target | Source / expression |
|---|---|
| `message.INPUT-AREA.TRAN-CODE` | `IBGCUDAT` |
| `message.INPUT-AREA.IN-CUSTID` | `{{$pathParameters.customerId}}` |

`response_200.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.customerId` | `{{$zosAssetResponse.message."OUTPUT-AREA"."CUSTID-OUT"}}` |
| `body.firstName` | `{{$zosAssetResponse.message."OUTPUT-AREA"."FIRSTNAME-OUT"}}` |
| `body.lastName` | `{{$zosAssetResponse.message."OUTPUT-AREA"."LASTNAME-OUT"}}` |
| `body.phoneNumber` | `{{$zosAssetResponse.message."OUTPUT-AREA"."PHONE-OUT"}}` |
| `body.address.addressLine1` | `{{$zosAssetResponse.message."OUTPUT-AREA"."ADDRESS-OUT"}}` |
| `body.address.city` | `{{$zosAssetResponse.message."OUTPUT-AREA"."CITY-OUT"}}` |
| `body.address.postalCode` | `{{$zosAssetResponse.message."OUTPUT-AREA"."ZIPCODE-OUT"}}` |
| `body.customerStatus` | `ACTIVE` |

HTTP status selection (`response_*.yaml` conditions):

- response_200.yaml: {'condition': '$exists($zosAssetResponse.message."OUTPUT-AREA"."CUSTID-OUT")', 'httpStatusCode': 200}
- response_400.yaml: {'httpStatusCode': 400}
- response_401.yaml: {'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_404.yaml: {'httpStatusCode': 404}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 403, 404, 500 return constant `{code, message}` bodies (see mapping files).

### 16. PUT `/ims/customers/{customerId}` — `updateIMSCustomer`

- z/OS asset: `src/api/src/main/zosAssets/IBSCUDAT/zosAsset.yaml` → IMS `IBSCUDAT` (transid `IMS tran IBSCUDAT`)
- Mapping files: `src/api/src/main/operations/updateIMSCustomer/`
- Parameters: `customerId` (path)
- Request body schema: `CustomerUpdate`
- Responses: 200 `Customer`, 400 `—`, 401 `—`, 403 `—`, 404 `—`, 500 `—`


`request.yaml` (API → copybook)

| Target | Source / expression |
|---|---|
| `message.INPUT-AREA.TRAN-CODE` | `IBSCUDAT` |
| `message.INPUT-AREA.CUSTOMER-IN.CUSTID-IN` | `$pathParameters.customerId` |
| `message.INPUT-AREA.CUSTOMER-IN.LASTNAME-IN` | `{{$body.lastName}}` |
| `message.INPUT-AREA.CUSTOMER-IN.FIRSTNAME-IN` | `{{$body.firstName}}` |
| `message.INPUT-AREA.CUSTOMER-IN.ADDRESS-IN` | `{{$body.address.addressLine1}}` |
| `message.INPUT-AREA.CUSTOMER-IN.CITY-IN` | `{{$body.address.city}}` |
| `message.INPUT-AREA.CUSTOMER-IN.STATE-IN` | `{{$body.address.addressLine2}}` |
| `message.INPUT-AREA.CUSTOMER-IN.ZIPCODE-IN` | `{{$body.address.postalCode}}` |
| `message.INPUT-AREA.CUSTOMER-IN.PHONE-IN` | `{{$body.phoneNumber}}` |

`response_200.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.customerId` | `{{$zosAssetResponse.message."OUTPUT-AREA"."CUSTOMER-OUT"."CUSTID-OUT"}}` |
| `body.firstName` | `{{$zosAssetResponse.message."OUTPUT-AREA"."CUSTOMER-OUT"."FIRSTNAME-OUT"}}` |
| `body.lastName` | `{{$zosAssetResponse.message."OUTPUT-AREA"."CUSTOMER-OUT"."LASTNAME-OUT"}}` |
| `body.phoneNumber` | `{{$zosAssetResponse.message."OUTPUT-AREA"."CUSTOMER-OUT"."PHONE-OUT"}}` |
| `body.address.addressLine1` | `{{$zosAssetResponse.message."OUTPUT-AREA"."CUSTOMER-OUT"."ADDRESS-OUT"}}` |
| `body.address.addressLine2` | `{{$zosAssetResponse.message."OUTPUT-AREA"."CUSTOMER-OUT"."STATE-OUT"}}` |
| `body.address.city` | `{{$zosAssetResponse.message."OUTPUT-AREA"."CUSTOMER-OUT"."CITY-OUT"}}` |
| `body.address.postalCode` | `{{$zosAssetResponse.message."OUTPUT-AREA"."CUSTOMER-OUT"."ZIPCODE-OUT"}}` |
| `body.address.country` | `USA` |

HTTP status selection (`response_*.yaml` conditions):

- response_200.yaml: {'condition': '$zosAssetResponse.message."OUTPUT-AREA"."MSG-OUT" = "00000000000000000000000000000000"', 'httpStatusCode': 200}
- response_400.yaml: {'httpStatusCode': 400}
- response_401.yaml: {'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_404.yaml: {'httpStatusCode': 404}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 403, 404, 500 return constant `{code, message}` bodies (see mapping files).

### 17. GET `/ims/customers/{customerId}/accounts` — `getIMSCustomerAccounts`

- z/OS asset: `src/api/src/main/zosAssets/IBACSUM/zosAsset.yaml` → IMS `IBACSUM` (transid `IMS tran IBACSUM`)
- Mapping files: `src/api/src/main/operations/getIMSCustomerAccounts/`
- Parameters: `customerId` (path)
- Request body schema: `—`
- Responses: 200 `AccountList`, 400 `—`, 401 `—`, 403 `—`, 404 `—`, 500 `—`


`request.yaml` (API → copybook)

| Target | Source / expression |
|---|---|
| `message.INPUT-AREA.TRAN-CODE` | `IBACSUM` |
| `message.INPUT-AREA.IN-CUSTID` | `{{$pathParameters.customerId}}` |

`response_200.yaml` (copybook → API)

| Target | Source / expression |
|---|---|
| `body.accounts` | `None` |

HTTP status selection (`response_*.yaml` conditions):

- response_200.yaml: {'condition': '$exists($zosAssetResponse.message."OUTPUT-AREA"."ACCOUNT-SUMMARY")', 'httpStatusCode': 200}
- response_400.yaml: {'httpStatusCode': 400}
- response_401.yaml: {'httpStatusCode': 401}
- response_403.yaml: {'httpStatusCode': 403}
- response_404.yaml: {'httpStatusCode': 404}
- response_500.yaml: {'condition': True, 'httpStatusCode': 500}

Error responses 400, 401, 403, 404, 500 return constant `{code, message}` bodies (see mapping files).

## Schemas (`components.schemas`)


**CreateCustomerRequest** — required: firstName, lastName

| Property | Type | Format / constraints |
|---|---|---|
| `title` | string | enum=['Professor', 'Mr', 'Mrs', 'Miss', 'Ms', 'Dr', 'Drs', 'Lord', 'Sir', 'Lady'], example=Mr |
| `firstName` | string | minLength=1, maxLength=50, example=John |
| `lastName` | string | minLength=1, maxLength=50, example=Smith |
| `dateOfBirth` | string | format=date, example=1985-06-15 |
| `phoneNumber` | string | maxLength=20, example=+44 20 7946 0958 |
| `address` | $ref Address |  |
| `customerStatus` | string | maxLength=10, example=ACTIVE |

**CreatedCustomer** — required: customerId, sortCode

| Property | Type | Format / constraints |
|---|---|---|
| `customerId` | string | example=0000000042 |
| `sortCode` | string | example=987654 |

**Customer** — required: customerId, firstName, lastName

| Property | Type | Format / constraints |
|---|---|---|
| `customerId` | string | example=CUST123456 |
| `title` | string | example=Mr |
| `firstName` | string | example=John |
| `lastName` | string | example=Smith |
| `dateOfBirth` | string | format=date, example=1985-06-15 |
| `phoneNumber` | string | example=+44 20 7946 0958 |
| `address` | $ref Address |  |
| `customerStatus` | string | enum=['ACTIVE', 'INACTIVE', 'SUSPENDED'], example=ACTIVE |
| `createdDate` | string | format=date-time, example=2020-01-15T10:30:00Z |

**Address**

| Property | Type | Format / constraints |
|---|---|---|
| `addressLine1` | string | example=123 High Street |
| `addressLine2` | string | example=Apartment 4B |
| `city` | string | example=London |
| `postalCode` | string | example=SW1A 1AA |
| `country` | string | example=United Kingdom |

**CustomerUpdate**

| Property | Type | Format / constraints |
|---|---|---|
| `title` | string | example=Mr, enum=['Professor', 'Mr', 'Mrs', 'Miss', 'Ms', 'Dr', 'Drs', 'Lord', 'Sir', 'Lady'] |
| `firstName` | string | example=John, maxLength=50 |
| `lastName` | string | example=Smith, maxLength=50 |
| `dateOfBirth` | string | format=date, example=1985-06-15 |
| `phoneNumber` | string | example=+44 20 7946 0958, maxLength=20 |
| `address` | object |  |
| `customerStatus` | string | enum=['ACTIVE', 'INACTIVE', 'SUSPENDED'], example=ACTIVE |

**AccountList** — required: accounts

| Property | Type | Format / constraints |
|---|---|---|
| `accounts` | array of Account |  |
| `totalCount` | integer | example=3 |

**Account** — required: accountId, accountType, currency, status

| Property | Type | Format / constraints |
|---|---|---|
| `accountId` | string | example=ACC987654321 |
| `accountType` | string | enum=['CURRENT', 'SAVINGS', 'CREDIT_CARD', 'LOAN'], example=CURRENT |
| `accountSubType` | string | example=PERSONAL_CURRENT |
| `currency` | string | example=GBP |
| `nickname` | string | example=My Main Account |
| `accountNumber` | string | example=12345678 |
| `sortCode` | string | example=12-34-56 |
| `iban` | string | example=GB29NWBK60161331926819 |
| `status` | string | enum=['ACTIVE', 'INACTIVE', 'CLOSED'], example=ACTIVE |
| `openingDate` | string | format=date, example=2020-01-15 |
| `customerId` | string | example=CUST123456 |

**BalanceList** — required: balances

| Property | Type | Format / constraints |
|---|---|---|
| `balances` | array of Balance |  |

**Balance** — required: balanceType, amount, currency, dateTime

| Property | Type | Format / constraints |
|---|---|---|
| `balanceType` | string | enum=['AVAILABLE', 'CURRENT', 'OPENING_AVAILABLE', 'OPENING_BOOKED'], example=AVAILABLE |
| `amount` | number | format=double, example=1234.56 |
| `currency` | string | example=GBP |
| `creditDebitIndicator` | string | enum=['CREDIT', 'DEBIT'], example=CREDIT |
| `dateTime` | string | format=date-time, example=2026-03-13T09:57:22Z |

**TransactionList** — required: transactions

| Property | Type | Format / constraints |
|---|---|---|
| `transactions` | array of Transaction |  |
| `totalCount` | integer | example=150 |
| `limit` | integer | example=50 |
| `offset` | integer | example=0 |

**Transaction** — required: transactionId, accountId, amount, currency, creditDebitIndicator, status, bookingDateTime

| Property | Type | Format / constraints |
|---|---|---|
| `transactionId` | string | example=TXN456789 |
| `accountId` | string | example=ACC987654321 |
| `amount` | number | format=double, example=50.0 |
| `currency` | string | example=GBP |
| `creditDebitIndicator` | string | enum=['CREDIT', 'DEBIT'], example=DEBIT |
| `status` | string | enum=['PENDING', 'BOOKED', 'CANCELLED'], example=BOOKED |
| `bookingDateTime` | string | format=date-time, example=2026-03-12T14:30:00Z |
| `valueDateTime` | string | format=date-time, example=2026-03-12T14:30:00Z |
| `transactionInformation` | string | example=Payment to ABC Store |
| `merchantDetails` | $ref MerchantDetails |  |
| `balance` | $ref Balance |  |

**MerchantDetails**

| Property | Type | Format / constraints |
|---|---|---|
| `merchantName` | string | example=ABC Store |
| `merchantCategoryCode` | string | example=5411 |

**DepositRequest** — required: amount, sortCode

| Property | Type | Format / constraints |
|---|---|---|
| `amount` | number | format=double, minimum=0.01, example=100.0 |
| `sortCode` | string | pattern=^\d{6}$, example=987654 |
| `description` | string | maxLength=40, example=Cash deposit |

**DepositResponse** — required: accountId, availableBalance, actualBalance

| Property | Type | Format / constraints |
|---|---|---|
| `accountId` | string | example=ACC987654321 |
| `availableBalance` | number | format=double, example=1334.56 |
| `actualBalance` | number | format=double, example=1334.56 |
| `transactionReference` | string | example=DEP20260619001 |

**Error** — required: code, message

| Property | Type | Format / constraints |
|---|---|---|
| `code` | string | example=INVALID_REQUEST |
| `message` | string | example=The request parameters are invalid |
| `details` | array of object |  |
