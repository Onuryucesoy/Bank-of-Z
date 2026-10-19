package com.bankofz.migration.model;

/**
 * {@code 05 CUSTOMER-ADDRESS} group of CUSTOMER.cpy (Db2 columns CUSTOMER_ADDR_LINE1
 * .. CUSTOMER_COUNTRY in CUSTDB2.cpy). Corresponds to the OpenAPI {@code Address}
 * schema: addressLine1, addressLine2, city, postalCode, country.
 * Strings are stored without the COBOL blank padding.
 */
public record Address(
        @Pic(copybook = Copybooks.CUSTOMER, field = "CUSTOMER-ADDR-LINE1", value = Address.PIC_LINE)
        String addressLine1,
        @Pic(copybook = Copybooks.CUSTOMER, field = "CUSTOMER-ADDR-LINE2", value = Address.PIC_LINE)
        String addressLine2,
        @Pic(copybook = Copybooks.CUSTOMER, field = "CUSTOMER-CITY", value = Address.PIC_LINE)
        String city,
        @Pic(copybook = Copybooks.CUSTOMER, field = "CUSTOMER-POSTCODE", value = Address.PIC_POSTCODE)
        String postcode,
        @Pic(copybook = Copybooks.CUSTOMER, field = "CUSTOMER-COUNTRY", value = Address.PIC_LINE)
        String country) {

    public static final String PIC_LINE = "X(50)";
    public static final String PIC_POSTCODE = "X(10)";

    public Address {
        addressLine1 = CobolFields.text(addressLine1, PIC_LINE, "CUSTOMER-ADDR-LINE1");
        addressLine2 = CobolFields.text(addressLine2, PIC_LINE, "CUSTOMER-ADDR-LINE2");
        city = CobolFields.text(city, PIC_LINE, "CUSTOMER-CITY");
        postcode = CobolFields.text(postcode, PIC_POSTCODE, "CUSTOMER-POSTCODE");
        country = CobolFields.text(country, PIC_LINE, "CUSTOMER-COUNTRY");
    }
}
