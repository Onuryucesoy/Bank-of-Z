package com.ibm.bankofz.migration.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * IMS operations ({@code /ims/...}). Customer ids here are the 9 digits after the {@code I} prefix
 * (see {@link com.ibm.bankofz.migration.routing.CustomerIdRouter}).
 */
@Path("ims")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ImsResource {

    private final StubResponses stubs;

    public ImsResource(StubResponses stubs) {
        this.stubs = stubs;
    }

    /** getIMSCustomer → IBGCUDAT. */
    @GET
    @Path("customers/{customerId}")
    public Response getImsCustomer(@PathParam("customerId") String customerId) {
        return StubResponses.ok(200, ZosAsset.IBGCUDAT, stubs.body("getIMSCustomer").put("customerId", customerId));
    }

    /** updateIMSCustomer → IBSCUDAT. */
    @PUT
    @Path("customers/{customerId}")
    public Response updateImsCustomer(@PathParam("customerId") String customerId, JsonNode body) {
        ObjectNode out = stubs.body("updateIMSCustomer").put("customerId", customerId);
        if (body != null && body.isObject()) {
            body.fields().forEachRemaining(e -> out.set(e.getKey(), e.getValue()));
        }
        return StubResponses.ok(200, ZosAsset.IBSCUDAT, out);
    }

    /** getIMSCustomerAccounts → IBACSUM. */
    @GET
    @Path("customers/{customerId}/accounts")
    public Response getImsCustomerAccounts(@PathParam("customerId") String customerId) {
        ObjectNode out = stubs.body("getIMSCustomerAccounts");
        out.withArray("accounts").forEach(a -> ((ObjectNode) a).put("customerId", customerId));
        return StubResponses.ok(200, ZosAsset.IBACSUM, out);
    }

    /** getIMSAccount → IBACSUM. */
    @GET
    @Path("accounts/{accountId}")
    public Response getImsAccount(@PathParam("accountId") String accountId) {
        return StubResponses.ok(200, ZosAsset.IBACSUM, stubs.body("getIMSAccount").put("accountId", accountId));
    }

    /** getIMSAccountBalances → IBACSUM. */
    @GET
    @Path("accounts/{accountId}/balances")
    public Response getImsAccountBalances(@PathParam("accountId") String accountId) {
        return StubResponses.ok(200, ZosAsset.IBACSUM, stubs.body("getIMSAccountBalances"));
    }

    /** depositToIMSAccount → IBTRAN. */
    @POST
    @Path("accounts/{customerId}/{accountId}/deposit")
    public Response depositToImsAccount(@PathParam("customerId") String customerId,
                                        @PathParam("accountId") String accountId, JsonNode body) {
        if (!AccountResource.DepositValidation.isValid(body)) {
            return StubResponses.error(400, "BAD_REQUEST", AccountResource.DepositValidation.MESSAGE);
        }
        return StubResponses.ok(201, ZosAsset.IBTRAN, stubs.body("depositToIMSAccount").put("accountId", accountId));
    }
}
