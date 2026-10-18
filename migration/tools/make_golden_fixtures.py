#!/usr/bin/env python3
"""Write the synthetic Phase 0 golden-master fixtures and the Java stub responses.

Fixtures are *synthetic* (hand-picked values, not production captures): each read-only operation gets
  migration/golden-master/fixtures/<ASSET>/request.json       -- z/OS Connect zosAsset request (COMMAREA JSON)
  migration/golden-master/fixtures/<ASSET>/response.json      -- z/OS Connect zosAsset response (COMMAREA JSON)
  migration/golden-master/fixtures/<ASSET>/api-response.json  -- the OpenAPI-shaped body the API returns
  migration/golden-master/fixtures/<ASSET>/manifest.json      -- which API call / schema the files belong to
Every value is shaped by the generated providerFiles/gen/*Schema.json, so the skeleton (every property present,
arrays at minItems, strings blank, numbers 0) is overlaid with the realistic values below.
"""
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/api/src/main/zosAssets"
FIX = ROOT / "migration/golden-master/fixtures"
STUBS = ROOT / "migration/java-service/src/main/resources/stubs/openapi-stub-responses.json"


def skeleton(schema):
    t = schema.get("type")
    if t == "object":
        return {k: skeleton(v) for k, v in schema.get("properties", {}).items() if k != "abendCode"}
    if t == "array":
        return [skeleton(schema["items"]) for _ in range(schema.get("minItems", 1))]
    if t == "string":
        return ""
    return 0


def deep_set(obj, values):
    for k, v in values.items():
        if isinstance(v, dict) and isinstance(obj.get(k), dict):
            deep_set(obj[k], v)
        else:
            obj[k] = v
    return obj


def load_schema(asset, kind):
    return json.loads((ASSETS / asset / "providerFiles/gen" / f"{kind}Schema.json").read_text())


def build(asset, request_values, response_values):
    req = deep_set(skeleton(load_schema(asset, "request")), request_values)
    resp = deep_set(skeleton(load_schema(asset, "response")), response_values)
    return req, resp


def ddmmyyyy(prefix, d, m, y, names=("DD", "MM", "YYYY")):
    return {f"{prefix}-{names[0]}": d, f"{prefix}-{names[1]}": m, f"{prefix}-{names[2]}": y}


CUSTOMER = {
    "INQCUST-EYE": "CUST", "INQCUST-SCODE": "987654", "INQCUST-CUSTNO": 1,
    "INQCUST-NAME": {"INQCUST-TITLE": "Mr", "INQCUST-FIRST-NAME": "John", "INQCUST-LAST-NAME": "Smith"},
    "INQCUST-DOB": ddmmyyyy("INQCUST-DOB", 15, 6, 1985),
    "INQCUST-PHONE": "+44 20 7946 0958",
    "INQCUST-ADDR": {"INQCUST-ADDR-LINE1": "123 High Street", "INQCUST-ADDR-LINE2": "Apartment 4B",
                     "INQCUST-CITY": "London", "INQCUST-POSTCODE": "SW1A 1AA", "INQCUST-COUNTRY": "United Kingdom"},
    "INQCUST-STATUS": "ACTIVE",
    "INQCUST-CREATED-DATE": ddmmyyyy("INQCUST-CREATED", 15, 1, 2020),
    "INQCUST-CREDIT-SCORE": 742,
    "INQCUST-CS-REVIEW-DT": ddmmyyyy("INQCUST-CS-REVIEW", 1, 3, 2026),
    "INQCUST-INQ-SUCCESS": "Y", "INQCUST-INQ-FAIL-CD": "0",
}

ACC_GROUP = lambda p, d, m, y: {f"{p}-DAY": d, f"{p}-MONTH": m, f"{p}-YEAR": y}

INQACC = {
    "INQACC-EYE": "ACCT", "INQACC-CUSTNO": 1, "INQACC-SCODE": 987654, "INQACC-ACCNO": 3000,
    "INQACC-ACC-TYPE": "CURRENT", "INQACC-INT-RATE": 0.75,
    "INQACC-OPENED": 15012020, "INQACC-OPENED-GROUP": ACC_GROUP("INQACC-OPENED", 15, 1, 2020),
    "INQACC-OVERDRAFT": 500,
    "INQACC-LAST-STMT-DT": 1022026, "INQACC-LAST-STMT-GROUP": ACC_GROUP("INQACC-LAST-STMT", 1, 2, 2026),
    "INQACC-NEXT-STMT-DT": 1032026, "INQACC-NEXT-STMT-GROUP": ACC_GROUP("INQACC-NEXT-STMT", 1, 3, 2026),
    "INQACC-AVAIL-BAL": 1234.56, "INQACC-ACTUAL-BAL": 1234.56, "INQACC-SUCCESS": "Y",
}


def accs_row(accno, typ, rate, avail, actual):
    return {"COMM-EYE": "ACCT", "COMM-CUSTNO": "0000000001", "COMM-SCODE": "987654", "COMM-ACCNO": accno,
            "COMM-ACC-TYPE": typ, "COMM-INT-RATE": rate,
            "COMM-OPENED": 15012020, "COMM-OPENED-GROUP": ACC_GROUP("COMM-OPENED", 15, 1, 2020),
            "COMM-OVERDRAFT": 0,
            "COMM-LAST-STMT-DT": 1022026, "COMM-LAST-STMT-GROUP": ACC_GROUP("COMM-LAST-STMT", 1, 2, 2026),
            "COMM-NEXT-STMT-DT": 1032026, "COMM-NEXT-STMT-GROUP": ACC_GROUP("COMM-NEXT-STMT", 1, 3, 2026),
            "COMM-AVAIL-BAL": avail, "COMM-ACTUAL-BAL": actual}


TRAN = dict(ref=123456789012, date=20260312, time=143000, typ="DEB", desc="Payment to ABC Store", amount=-50.00)


def tran_row(prefix, i, t):
    return {f"{prefix}-TRAN-ID": f"987654-3000-{t['date']}-{t['time']:06d}-{t['ref'] + i}",
            f"{prefix}-TRAN-SORTCODE": 987654, f"{prefix}-TRAN-ACCNO": 3000}


def main():
    fixtures = {}

    # INQCUST ------------------------------------------------------------------
    fixtures["INQCUST"] = (
        build("INQCUST", {"commarea": {"INQCUSTZ": {"INQCUST-CUSTNO": 1}}}, {"commarea": {"INQCUSTZ": CUSTOMER}}),
        dict(operationId="getCustomer", method="GET", path="/customers/0000000001", status=200, schema="Customer"),
        {"customerId": "0000000001", "title": "Mr", "firstName": "John", "lastName": "Smith",
         "dateOfBirth": "1985-06-15", "phoneNumber": "+44 20 7946 0958",
         "address": {"addressLine1": "123 High Street", "addressLine2": "Apartment 4B", "city": "London",
                     "postalCode": "SW1A 1AA", "country": "United Kingdom"},
         "customerStatus": "ACTIVE", "createdDate": "2020-01-15T00:00:00Z"})

    # INQACC -------------------------------------------------------------------
    fixtures["INQACC"] = (
        build("INQACC", {"commarea": {"INQACC-COMMAREA": {"INQACC-ACCNO": 3000}}}, {"commarea": {"INQACC-COMMAREA": INQACC}}),
        dict(operationId="getAccount", method="GET", path="/accounts/3000", status=200, schema="Account"),
        {"accountId": "3000", "accountType": "CURRENT", "currency": "GBP", "accountNumber": "00003000",
         "sortCode": "987654", "status": "ACTIVE", "openingDate": "2020-01-15", "customerId": "0000000001"})

    # INQACCS ------------------------------------------------------------------
    rows = [accs_row(3000, "CURRENT", 0.75, 1234.56, 1234.56), accs_row(3001, "SAVINGS", 2.10, 10500.00, 10500.00),
            accs_row(3002, "LOAN", 6.99, -2500.00, -2500.00)]
    fixtures["INQACCS"] = (
        build("INQACCS", {"commarea": {"INQACCS": {"ACCOUNT-DETAILS": []}}},
              {"commarea": {"INQACCS": {"COMM-SUCCESS": "Y", "COMM-FAIL-CODE": "0", "ACCOUNT-DETAILS": rows}}}),
        dict(operationId="getAccounts", method="GET", path="/accounts", status=200, schema="AccountList"),
        {"accounts": [
            {"accountId": "3000", "accountType": "CURRENT", "currency": "GBP", "accountNumber": "00003000",
             "sortCode": "987654", "status": "ACTIVE", "openingDate": "2020-01-15", "customerId": "0000000001"},
            {"accountId": "3001", "accountType": "SAVINGS", "currency": "GBP", "accountNumber": "00003001",
             "sortCode": "987654", "status": "ACTIVE", "openingDate": "2020-01-15", "customerId": "0000000001"},
            {"accountId": "3002", "accountType": "LOAN", "currency": "GBP", "accountNumber": "00003002",
             "sortCode": "987654", "status": "ACTIVE", "openingDate": "2020-01-15", "customerId": "0000000001"}],
         "totalCount": 3})

    # INQTRAND -----------------------------------------------------------------
    t = TRAN
    tran_id = f"987654-3000-{t['date']}-{t['time']:06d}-{t['ref']}"
    fixtures["INQTRAND"] = (
        build("INQTRAND",
              {"commarea": {"INQTRAND-COMMAREA": {"INQTRAND-EYE": "PRTR", "INQTRAND-SORTCODE": 987654, "INQTRAND-ACCNO": 3000,
                                                  "INQTRAND-DATE": t["date"], "INQTRAND-TIME": t["time"], "INQTRAND-REF": t["ref"]}}},
              {"commarea": {"INQTRAND-COMMAREA": {
                  "INQTRAND-EYE": "PRTR", "INQTRAND-SORTCODE": 987654, "INQTRAND-ACCNO": 3000,
                  "INQTRAND-DATE": t["date"], "INQTRAND-TIME": t["time"], "INQTRAND-REF": t["ref"],
                  "INQTRAND-SUCCESS": "Y", "INQTRAND-FOUND": "Y", "INQTRAND-TRAN-ID": tran_id,
                  "INQTRAND-TRAN-SORTCODE": 987654, "INQTRAND-TRAN-ACCNO": 3000, "INQTRAND-TRAN-DATE": t["date"],
                  "INQTRAND-TRAN-DATE-GRP": {"INQTRAND-TRAN-DATE-YYYY": 2026, "INQTRAND-TRAN-DATE-MM": 3, "INQTRAND-TRAN-DATE-DD": 12},
                  "INQTRAND-TRAN-TIME": t["time"],
                  "INQTRAND-TRAN-TIME-GRP": {"INQTRAND-TRAN-TIME-HH": 14, "INQTRAND-TRAN-TIME-MM": 30, "INQTRAND-TRAN-TIME-SS": 0},
                  "INQTRAND-TRAN-REF": t["ref"], "INQTRAND-TRAN-TYPE": t["typ"], "INQTRAND-TRAN-DESC": t["desc"],
                  "INQTRAND-TRAN-AMOUNT": t["amount"]}}}),
        dict(operationId="getTransaction", method="GET", path=f"/accounts/3000/transactions/{tran_id}", status=200, schema="Transaction"),
        {"transactionId": tran_id, "accountId": "3000", "amount": 50.00, "currency": "GBP",
         "creditDebitIndicator": "DEBIT", "status": "BOOKED", "bookingDateTime": "2026-03-12T14:30:00Z",
         "valueDateTime": "2026-03-12T14:30:00Z", "transactionInformation": t["desc"]})

    # INQTRANL -----------------------------------------------------------------
    req, resp = build("INQTRANL",
                      {"commarea": {"INQTRANL-COMMAREA": {"INQTRANL-EYE": "PRTL", "INQTRANL-SORTCODE": 987654, "INQTRANL-ACCNO": 3000,
                                                          "INQTRANL-FROM-DATE": 20260101, "INQTRANL-TO-DATE": 20260313,
                                                          "INQTRANL-LIMIT": 50, "INQTRANL-OFFSET": 0}}},
                      {"commarea": {"INQTRANL-COMMAREA": {"INQTRANL-EYE": "PRTL", "INQTRANL-SORTCODE": 987654, "INQTRANL-ACCNO": 3000,
                                                          "INQTRANL-FROM-DATE": 20260101, "INQTRANL-TO-DATE": 20260313,
                                                          "INQTRANL-LIMIT": 50, "INQTRANL-OFFSET": 0, "INQTRANL-TOTAL-COUNT": 2,
                                                          "INQTRANL-RETURNED-COUNT": 2, "INQTRANL-SUCCESS": "Y"}}})
    slots = resp["commarea"]["INQTRANL-COMMAREA"]["INQTRANL-TRANSACTIONS"]
    trans = [dict(TRAN), dict(TRAN, ref=TRAN["ref"] + 1, date=20260310, time=91500, typ="CRE", desc="Salary", amount=2500.00)]
    api_trans = []
    for i, tr in enumerate(trans):
        slot = slots[i]
        for key, val in [("TRAN-ID", f"987654-3000-{tr['date']}-{tr['time']:06d}-{tr['ref']}"), ("TRAN-SORTCODE", 987654),
                         ("TRAN-ACCNO", 3000), ("TRAN-DATE", tr["date"]), ("TRAN-TIME", tr["time"]), ("TRAN-REF", tr["ref"]),
                         ("TRAN-TYPE", tr["typ"]), ("TRAN-DESC", tr["desc"]), ("TRAN-AMOUNT", tr["amount"])]:
            if f"INQTRANL-{key}" in slot:
                slot[f"INQTRANL-{key}"] = val
        d, tm = str(tr["date"]), f"{tr['time']:06d}"
        # REDEFINES views must agree with the 8/6-digit fields they overlay
        slot["INQTRANL-TRAN-DATE-GRP"] = {"INQTRANL-TRAN-DATE-YYYY": int(d[:4]), "INQTRANL-TRAN-DATE-MM": int(d[4:6]),
                                          "INQTRANL-TRAN-DATE-DD": int(d[6:])}
        slot["INQTRANL-TRAN-TIME-GRP"] = {"INQTRANL-TRAN-TIME-HH": int(tm[:2]), "INQTRANL-TRAN-TIME-MM": int(tm[2:4]),
                                          "INQTRANL-TRAN-TIME-SS": int(tm[4:])}
        ts = f"{d[:4]}-{d[4:6]}-{d[6:]}T{tm[:2]}:{tm[2:4]}:{tm[4:]}Z"
        api_trans.append({"transactionId": f"987654-3000-{tr['date']}-{tr['time']:06d}-{tr['ref']}", "accountId": "3000",
                          "amount": abs(tr["amount"]), "currency": "GBP",
                          "creditDebitIndicator": "DEBIT" if tr["amount"] < 0 else "CREDIT", "status": "BOOKED",
                          "bookingDateTime": ts, "valueDateTime": ts, "transactionInformation": tr["desc"]})
    fixtures["INQTRANL"] = ((req, resp),
                            dict(operationId="getAccountTransactions", method="GET", path="/accounts/3000/transactions", status=200,
                                 schema="TransactionList"),
                            {"transactions": api_trans, "totalCount": 2, "limit": 50, "offset": 0})

    for asset, ((req, resp), call, api) in fixtures.items():
        d = FIX / asset
        d.mkdir(parents=True, exist_ok=True)
        manifest = dict(asset=asset, synthetic=True,
                        note="Hand-made from the generated schemas; replace with z/OS Connect captures in Phase 2 (see ../../README.md)",
                        requestSchema=f"src/api/src/main/zosAssets/{asset}/providerFiles/gen/requestSchema.json",
                        responseSchema=f"src/api/src/main/zosAssets/{asset}/providerFiles/gen/responseSchema.json",
                        apiCall=call)
        for name, obj in (("manifest", manifest), ("request", req), ("response", resp), ("api-response", api)):
            (d / f"{name}.json").write_text(json.dumps(obj, indent=2) + "\n")

    # Java stub responses: wave-1 read ops reuse the golden-master API bodies; others are OpenAPI-shaped samples.
    by_op = {call["operationId"]: api for (_, call, api) in fixtures.values()}
    customer = by_op["getCustomer"]
    account = by_op["getAccount"]
    stubs = {
        "_comment": "Canned OpenAPI-shaped bodies keyed by operationId. Generated by migration/tools/make_golden_fixtures.py.",
        **by_op,
        "createCustomer": {"customerId": "0000000042", "sortCode": "987654"},
        "updateCustomer": customer,
        "getCustomerAccounts": {"accounts": [account], "totalCount": 1},
        "getAccountBalances": {"balanceType": "AVAILABLE", "amount": 1234.56, "currency": "GBP",
                               "creditDebitIndicator": "CREDIT", "dateTime": "2026-03-13T09:57:22Z"},
        "depositToAccount": {"accountId": "3000", "availableBalance": 1334.56, "actualBalance": 1334.56,
                             "transactionReference": "DEP20260619001"},
        "getIMSCustomer": dict(customer, customerId="000000015"),
        "updateIMSCustomer": dict(customer, customerId="000000015"),
        "getIMSCustomerAccounts": {"accounts": [{"accountId": "1504", "accountType": "SAVINGS", "currency": "USD",
                                                 "status": "ACTIVE", "customerId": "000000015"}], "totalCount": 1},
        "getIMSAccount": {"accountId": "1504", "accountType": "SAVINGS", "currency": "USD", "status": "ACTIVE"},
        "getIMSAccountBalances": {"balanceType": "AVAILABLE", "amount": 2048.10, "currency": "USD",
                                  "dateTime": "2026-03-13T09:57:22Z"},
        "depositToIMSAccount": {"accountId": "1504", "availableBalance": 2148.10, "actualBalance": 2148.10},
    }
    STUBS.parent.mkdir(parents=True, exist_ok=True)
    STUBS.write_text(json.dumps(stubs, indent=2) + "\n")
    print("wrote", ", ".join(fixtures), "and", STUBS.relative_to(ROOT))


if __name__ == "__main__":
    main()
