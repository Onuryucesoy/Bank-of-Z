package com.ibm.bankofz.migration.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.ws.rs.ext.ContextResolver;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;

/** JAX-RS application exposing the frozen {@code src/api/src/main/api/openapi.yaml} contract as stubs. */
public class BankOfZApplication extends ResourceConfig {

    public static final String BASE_PATH = "/api";

    /** Numbers are read as BigDecimal so monetary values never pass through double. */
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

    public BankOfZApplication() {
        this(StubResponses.loadDefault());
    }

    public BankOfZApplication(StubResponses stubs) {
        register(JacksonFeature.withoutExceptionMappers());
        register((ContextResolver<ObjectMapper>) type -> MAPPER);
        register(new CustomerResource(stubs));
        register(new AccountResource(stubs));
        register(new ImsResource(stubs));
    }
}
