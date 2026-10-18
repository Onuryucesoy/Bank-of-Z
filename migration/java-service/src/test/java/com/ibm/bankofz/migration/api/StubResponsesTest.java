package com.ibm.bankofz.migration.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class StubResponsesTest {

    static final Set<String> OPERATION_IDS_WITH_BODY = Set.of(
            "createCustomer", "getCustomer", "updateCustomer", "getCustomerAccounts",
            "getAccounts", "getAccount", "getAccountBalances", "depositToAccount",
            "getAccountTransactions", "getTransaction",
            "getIMSCustomer", "updateIMSCustomer", "getIMSCustomerAccounts",
            "getIMSAccount", "getIMSAccountBalances", "depositToIMSAccount");

    @Test
    void everyOperationWithAResponseBodyHasAStub() {
        StubResponses stubs = StubResponses.loadDefault();
        assertEquals(OPERATION_IDS_WITH_BODY, stubs.all().keySet());
    }

    @Test
    void bodiesAreDefensiveCopiesAndKeepDecimalPrecision() {
        StubResponses stubs = StubResponses.loadDefault();
        assertNotSame(stubs.body("getAccountBalances"), stubs.body("getAccountBalances"));
        assertTrue(stubs.body("getAccountBalances").get("amount").isBigDecimal());
        assertEquals("1234.56", stubs.body("getAccountBalances").get("amount").decimalValue().toPlainString());
    }
}
