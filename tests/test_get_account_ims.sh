#!/usr/bin/env bash
# Test Case: Get Account Summary (IMS path)
# Customer ID : 000000015 (IMS customer - routed via /api/ims/)
# Endpoint    : GET /api/ims/accounts/{customerId}   (IBACSUM)
#               The {accountId} path parameter is mapped to IN-CUSTID by the IBACSUM
#               asset, so it takes a customer ID - the same way src/frontend/js/api.js calls it.
# Expectation : accountId present, currency USD, status ACTIVE
#               Verified on both HTTP and HTTPS ports (HTTPS skipped when not configured)
#
# Environment variables:
#   BASE_URL       Base URL of the z/OS Connect API server (default: http://localhost:9080/api)
#   BASE_HTTPS_URL HTTPS base URL (default: https://localhost:9444/api; skipped when not set)
set -e

# shellcheck source=test-setup.sh
source "$(dirname "$0")/test-setup.sh"

CUSTOMER_ID="000000015"
PASS=0
FAIL=0
SKIP=0

pass() { echo "PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $1" >&2; FAIL=$((FAIL+1)); }
skip() { echo "SKIP: $1"; SKIP=$((SKIP+1)); }

check_ims_account_url() {
    local api_url="$1"

    echo "--- Endpoint : GET ${api_url}/ims/accounts/${CUSTOMER_ID}"
    RESPONSE=$(curl --silent --max-time 10 --insecure \
        --header "Content-Type: application/json" \
        "${api_url}/ims/accounts/${CUSTOMER_ID}" || true)

    echo "    Response:"
    echo "${RESPONSE}" | python3 -m json.tool 2>/dev/null || echo "${RESPONSE}"
    echo ""

    ACCOUNT_ID=$(echo "${RESPONSE}" | python3 -c \
        "import sys,json; print(str(json.load(sys.stdin).get('accountId','')).strip())" 2>/dev/null || true)
    CURRENCY=$(echo "${RESPONSE}" | python3 -c \
        "import sys,json; print(json.load(sys.stdin).get('currency',''))" 2>/dev/null || true)
    STATUS=$(echo "${RESPONSE}" | python3 -c \
        "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || true)

    [ -n "${ACCOUNT_ID}" ]         && pass "accountId is \"${ACCOUNT_ID}\" (${api_url})" || fail "accountId is empty or missing (${api_url})"
    [ "${CURRENCY}" = "USD" ]      && pass "currency is USD (${api_url})"                || fail "expected currency \"USD\" but got \"${CURRENCY}\" (${api_url})"
    [ "${STATUS}" = "ACTIVE" ]     && pass "status is ACTIVE (${api_url})"               || fail "expected status \"ACTIVE\" but got \"${STATUS}\" (${api_url})"

    echo ""
}

echo "=== Test: Get Account Summary (IMS) ==="
echo "Customer ID : ${CUSTOMER_ID}"
echo ""

check_ims_account_url "${BASE_URL}"

if [[ -n "${BASE_HTTPS_URL:-}" ]]; then
    check_ims_account_url "${BASE_HTTPS_URL}"
else
    skip "HTTPS API test (ZOSCONNECT_HTTPS_PORT not configured)"
fi

echo "=== Summary: ${PASS} passed, ${FAIL} failed, ${SKIP} skipped ==="
[ "${FAIL}" -eq 0 ] && exit 0 || exit 1
