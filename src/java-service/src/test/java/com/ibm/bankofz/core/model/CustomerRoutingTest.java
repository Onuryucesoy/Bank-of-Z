package com.ibm.bankofz.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ibm.bankofz.core.service.CustomerRouting;

import org.junit.jupiter.api.Test;

class CustomerRoutingTest {

    @Test
    void prefixSelectsBackendLikeFrontendParseCustomerId() {
        assertEquals(new CustomerRouting.RoutedId(CustomerRouting.Backend.CICS, "1"), CustomerRouting.parse("C1"));
        assertEquals(new CustomerRouting.RoutedId(CustomerRouting.Backend.IMS, "123"), CustomerRouting.parse("i123"));
        assertThrows(IllegalArgumentException.class, () -> CustomerRouting.parse("X1"));
        assertThrows(IllegalArgumentException.class, () -> CustomerRouting.parse("C"));
        assertEquals("I42", CustomerRouting.format(CustomerRouting.Backend.IMS, "42"));
    }

    @Test
    void cobolDatesKeepNonCalendarValues() {
        assertEquals(new CobolDate(1, 2, 2020), CobolDate.ofDdMmYyyy(1022020));
        assertEquals(new CobolDate(15, 6, 1980), CobolDate.ofYyyyMmDd(19800615));
        assertEquals("01.02.2020", new CobolDate(1, 2, 2020).toEurString());
        assertEquals(new CobolDate(1, 2, 2020), CobolDate.ofEurString("01.02.2020"));
        assertEquals(true, new CobolDate(31, 2, 2020).toLocalDate().isEmpty());
    }
}
