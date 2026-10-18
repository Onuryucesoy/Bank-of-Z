#!/usr/bin/env bash
# Test Case: Get Account Details (CICS path)
# Customer ID : C0000001 (CICS customer, numeric ID 0000001)
# Endpoints   : GET /api/customers/{customerId}/accounts                   (INQACCCU)
#               GET /api/accounts/{accountId}                              (INQACC)
#               GET /api/accounts/{accountId}/balances                     (INQACC)
#               GET /api/accounts                                          (INQACCS)
#               GET /api/accounts/{sortCode-accountId}/transactions        (INQTRANL)
#               GET /api/accounts/{sortCode-accountId}/transactions/{id}   (INQTRAND)
# Expectation : customer has at least one account; account and balance details match it;
#               transaction list is returned and, when non-empty, its first entry can be fetched.
#               Account IDs are read from the customer's account list because BANKDATA
#               generates them; transaction detail is skipped when PROCTRAN has no rows.
#               Verified on both HTTP and HTTPS ports (HTTPS skipped when not configured)
#
# Environment variables:
#   BASE_URL       Base URL of the z/OS Connect API server (default: http://localhost:9080/api)
#   BASE_HTTPS_URL HTTPS base URL (default: https://localhost:9444/api; skipped when not set)
set -e

# shellcheck source=test-setup.sh
source "$(dirname "$0")/test-setup.sh"

CUSTOMER_ID="0000001"           # Strip the C prefix as per frontend parseCustomerId logic
PASS=0
FAIL=0
SKIP=0

pass() { echo "PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $1" >&2; FAIL=$((FAIL+1)); }
skip() { echo "SKIP: $1"; SKIP=$((SKIP+1)); }

# get_json URL - print the response body; never fails so set -e does not abort the run
get_json() {
    curl --silent --max-time 10 --insecure \
        --header "Content-Type: application/json" \
        "$1" || true
}

# json_eval JSON EXPR - evaluate a Python expression against the parsed body bound to 'd'
json_eval() {
    echo "$1" | python3 -c "import sys,json; d=json.load(sys.stdin); print($2)" 2>/dev/null || true
}

show() {
    echo "    Response:"
    echo "$1" | python3 -m json.tool 2>/dev/null || echo "$1"
    echo ""
}

check_accounts_url() {
    local api_url="$1"

    # -----------------------------------------------------------------------
    # 1. GET /api/customers/{customerId}/accounts
    # -----------------------------------------------------------------------
    echo "--- Endpoint : GET ${api_url}/customers/${CUSTOMER_ID}/accounts"
    local list_resp
    list_resp=$(get_json "${api_url}/customers/${CUSTOMER_ID}/accounts")
    show "${list_resp}"

    local count acc_no sort_code
    count=$(json_eval "${list_resp}" "len(d.get('accounts',[]))")
    acc_no=$(json_eval "${list_resp}" "d['accounts'][0].get('accountId','').strip()")
    sort_code=$(json_eval "${list_resp}" "d['accounts'][0].get('sortCode','').strip()")

    if [ "${count:-0}" -gt 0 ] 2>/dev/null && [ -n "${acc_no}" ] && [ -n "${sort_code}" ]; then
        pass "customer has ${count} account(s); using account ${sort_code}-${acc_no} (${api_url})"
    else
        fail "customer account list is empty or missing accountId/sortCode (${api_url})"
        skip "remaining CICS account checks need an account ID (${api_url})"
        echo ""
        return
    fi

    # -----------------------------------------------------------------------
    # 2. GET /api/accounts/{accountId}
    # -----------------------------------------------------------------------
    echo "--- Endpoint : GET ${api_url}/accounts/${acc_no}"
    local acc_resp
    acc_resp=$(get_json "${api_url}/accounts/${acc_no}")
    show "${acc_resp}"

    [ "$(json_eval "${acc_resp}" "int(d.get('accountNumber') or -1) == int('${acc_no}')")" = "True" ] \
        && pass "accountNumber matches ${acc_no} (${api_url})" \
        || fail "accountNumber does not match ${acc_no} (${api_url})"
    [ "$(json_eval "${acc_resp}" "int(d.get('customerId') or -1) == int('${CUSTOMER_ID}')")" = "True" ] \
        && pass "customerId matches ${CUSTOMER_ID} (${api_url})" \
        || fail "customerId does not match ${CUSTOMER_ID} (${api_url})"
    [ "$(json_eval "${acc_resp}" "str(d.get('sortCode','')).strip()")" = "${sort_code}" ] \
        && pass "sortCode matches ${sort_code} (${api_url})" \
        || fail "sortCode does not match ${sort_code} (${api_url})"

    # -----------------------------------------------------------------------
    # 3. GET /api/accounts/{accountId}/balances
    # -----------------------------------------------------------------------
    echo "--- Endpoint : GET ${api_url}/accounts/${acc_no}/balances"
    local bal_resp
    bal_resp=$(get_json "${api_url}/accounts/${acc_no}/balances")
    show "${bal_resp}"

    [ "$(json_eval "${bal_resp}" "d.get('balanceType','')")" = "AVAILABLE" ] \
        && pass "balanceType is AVAILABLE (${api_url})" \
        || fail "balanceType is not AVAILABLE (${api_url})"
    [ "$(json_eval "${bal_resp}" "isinstance(d.get('amount'), (int, float)) and not isinstance(d.get('amount'), bool)")" = "True" ] \
        && pass "amount is numeric (${api_url})" \
        || fail "amount is missing or not numeric (${api_url})"
    [ "$(json_eval "${bal_resp}" "d.get('currency','')")" = "GBP" ] \
        && pass "currency is GBP (${api_url})" \
        || fail "currency is not GBP (${api_url})"

    # -----------------------------------------------------------------------
    # 4. GET /api/accounts
    # -----------------------------------------------------------------------
    echo "--- Endpoint : GET ${api_url}/accounts"
    local all_resp
    all_resp=$(get_json "${api_url}/accounts")
    show "${all_resp}"

    local all_count
    all_count=$(json_eval "${all_resp}" "len(d.get('accounts',[]))")
    [ "${all_count:-0}" -gt 0 ] 2>/dev/null \
        && pass "accounts list has ${all_count} entry/entries (${api_url})" \
        || fail "accounts list is empty or missing (${api_url})"

    # -----------------------------------------------------------------------
    # 5. GET /api/accounts/{sortCode-accountId}/transactions
    # -----------------------------------------------------------------------
    local tx_acc="${sort_code}-${acc_no}"
    echo "--- Endpoint : GET ${api_url}/accounts/${tx_acc}/transactions"
    local tx_resp
    tx_resp=$(get_json "${api_url}/accounts/${tx_acc}/transactions")
    show "${tx_resp}"

    local tx_count tx_id
    tx_count=$(json_eval "${tx_resp}" "len(d['transactions']) if isinstance(d.get('transactions'), list) else ''")
    if [ -n "${tx_count}" ]; then
        pass "transactions list returned with ${tx_count} entry/entries (${api_url})"
    else
        fail "transactions list is missing (${api_url})"
        echo ""
        return
    fi

    # -----------------------------------------------------------------------
    # 6. GET /api/accounts/{sortCode-accountId}/transactions/{transactionId}
    # -----------------------------------------------------------------------
    if [ "${tx_count}" -eq 0 ]; then
        skip "transaction detail: account ${tx_acc} has no transactions (${api_url})"
        echo ""
        return
    fi
    tx_id=$(json_eval "${tx_resp}" "d['transactions'][0].get('transactionId','').strip()")
    echo "--- Endpoint : GET ${api_url}/accounts/${tx_acc}/transactions/${tx_id}"
    local txd_resp
    txd_resp=$(get_json "${api_url}/accounts/${tx_acc}/transactions/${tx_id}")
    show "${txd_resp}"

    [ -n "${tx_id}" ] && [ "$(json_eval "${txd_resp}" "d.get('transactionId','').strip()")" = "${tx_id}" ] \
        && pass "transactionId matches ${tx_id} (${api_url})" \
        || fail "transactionId does not match \"${tx_id}\" (${api_url})"
    [ "$(json_eval "${txd_resp}" "isinstance(d.get('amount'), (int, float)) and not isinstance(d.get('amount'), bool)")" = "True" ] \
        && pass "transaction amount is numeric (${api_url})" \
        || fail "transaction amount is missing or not numeric (${api_url})"

    echo ""
}

echo "=== Test: Get Account Details (CICS) ==="
echo "Customer ID : C${CUSTOMER_ID}"
echo ""

check_accounts_url "${BASE_URL}"

if [[ -n "${BASE_HTTPS_URL:-}" ]]; then
    check_accounts_url "${BASE_HTTPS_URL}"
else
    skip "HTTPS API test (ZOSCONNECT_HTTPS_PORT not configured)"
fi

echo "=== Summary: ${PASS} passed, ${FAIL} failed, ${SKIP} skipped ==="
[ "${FAIL}" -eq 0 ] && exit 0 || exit 1
