package com.ibm.bankofz.migration.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** CICS/Db2 customer operations ({@code /customers}). */
@Path("customers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CustomerResource {

    private final StubResponses stubs;

    public CustomerResource(StubResponses stubs) {
        this.stubs = stubs;
    }

    /** createCustomer → CRECUST. */
    @POST
    public Response createCustomer(JsonNode body) {
        if (body == null || !body.hasNonNull("firstName") || !body.hasNonNull("lastName")) {
            return StubResponses.error(400, "BAD_REQUEST", "firstName and lastName are required");
        }
        return StubResponses.ok(201, ZosAsset.CRECUST, stubs.body("createCustomer"));
    }

    /** getCustomer → INQCUST. */
    @GET
    @Path("{customerId}")
    public Response getCustomer(@PathParam("customerId") String customerId) {
        return StubResponses.ok(200, ZosAsset.INQCUST, stubs.body("getCustomer").put("customerId", customerId));
    }

    /** updateCustomer → UPDCUST. */
    @PUT
    @Path("{customerId}")
    public Response updateCustomer(@PathParam("customerId") String customerId, JsonNode body) {
        ObjectNode out = stubs.body("updateCustomer").put("customerId", customerId);
        if (body != null && body.isObject()) {
            body.fields().forEachRemaining(e -> out.set(e.getKey(), e.getValue()));
        }
        return StubResponses.ok(200, ZosAsset.UPDCUST, out);
    }

    /** deleteCustomer → DELCUS. */
    @DELETE
    @Path("{customerId}")
    public Response deleteCustomer(@PathParam("customerId") String customerId) {
        return StubResponses.ok(204, ZosAsset.DELCUS, null);
    }

    /** getCustomerAccounts → INQACCCU. */
    @GET
    @Path("{customerId}/accounts")
    public Response getCustomerAccounts(@PathParam("customerId") String customerId) {
        ObjectNode out = stubs.body("getCustomerAccounts");
        out.withArray("accounts").forEach(a -> ((ObjectNode) a).put("customerId", customerId));
        return StubResponses.ok(200, ZosAsset.INQACCCU, out);
    }
}
