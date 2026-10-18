package com.ibm.bankofz.migration.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.bankofz.migration.api.ZosAsset.Backend;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Cases mirror the behaviour of validateCustomerId / parseCustomerId in src/frontend/js/utils.js. */
class CustomerIdRouterTest {

    @ParameterizedTest
    @CsvSource({
        "C1,            CICS, 1",
        "c0000000001,   CICS, 0000000001",
        "'  C42  ',     CICS, 42",
        "I000000015,    IMS,  000000015",
        "i123456789,    IMS,  123456789"
    })
    void routesValidIds(String input, Backend backend, String digits) {
        CustomerIdRouter.Route route = CustomerIdRouter.route(input).orElseThrow();
        assertEquals(backend, route.backend());
        assertEquals(digits, route.customerId());
        assertTrue(CustomerIdRouter.validate(input).valid());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "''           | Customer ID is required",
        "'   '        | Customer ID is required",
        "X123         | Customer ID must start with C (CICS) or I (IMS)",
        "123          | Customer ID must start with C (CICS) or I (IMS)",
        "C            | Customer ID must contain only digits after the prefix",
        "C12A         | Customer ID must contain only digits after the prefix",
        "C-1          | Customer ID must contain only digits after the prefix",
        "I15          | IMS customer ID must have exactly 9 digits after I prefix (e.g., I000000015)",
        "I0000000015  | IMS customer ID must have exactly 9 digits after I prefix (e.g., I000000015)"
    })
    void rejectsInvalidIdsWithFrontendMessages(String input, String error) {
        CustomerIdRouter.Validation v = CustomerIdRouter.validate(input);
        assertFalse(v.valid());
        assertEquals(error, v.error());
        assertTrue(CustomerIdRouter.route(input).isEmpty());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"I12345678A"})
    void rejectsNullAndNonNumericIms(String input) {
        assertTrue(CustomerIdRouter.route(input).isEmpty());
    }
}
