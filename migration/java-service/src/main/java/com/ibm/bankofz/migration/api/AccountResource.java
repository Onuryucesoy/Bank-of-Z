package com.ibm.bankofz.migration.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;

/** CICS/Db2 account and transaction operations ({@code /accounts}). */
@Path("accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccountResource {

    private final StubResponses stubs;

    public AccountResource(StubResponses stubs) {
        this.stubs = stubs;
    }

    /** getAccounts → INQACCS. */
    @GET
    public Response getAccounts(@QueryParam("accountType") String accountType, @QueryParam("status") String status) {
        ObjectNode out = stubs.body("getAccounts");
        ArrayNode filtered = out.arrayNode();
        for (JsonNode a : out.withArray("accounts")) {
            if ((accountType == null || accountType.equals(a.path("accountType").asText()))
                    && (status == null || status.equals(a.path("status").asText()))) {
                filtered.add(a);
            }
        }
        out.set("accounts", filtered);
        out.put("totalCount", filtered.size());
        return StubResponses.ok(200, ZosAsset.INQACCS, out);
    }

    /** getAccount → INQACC. */
    @GET
    @Path("{accountId}")
    public Response getAccount(@PathParam("accountId") String accountId) {
        return StubResponses.ok(200, ZosAsset.INQACC, stubs.body("getAccount").put("accountId", accountId));
    }

    /** getAccountBalances → INQACC. */
    @GET
    @Path("{accountId}/balances")
    public Response getAccountBalances(@PathParam("accountId") String accountId) {
        return StubResponses.ok(200, ZosAsset.INQACC, stubs.body("getAccountBalances"));
    }

    /** depositToAccount → DBCRFUN. */
    @POST
    @Path("{accountId}/deposit")
    public Response depositToAccount(@PathParam("accountId") String accountId, JsonNode body) {
        if (!DepositValidation.isValid(body)) {
            return StubResponses.error(400, "BAD_REQUEST", DepositValidation.MESSAGE);
        }
        return StubResponses.ok(201, ZosAsset.DBCRFUN, stubs.body("depositToAccount").put("accountId", accountId));
    }

    /** getAccountTransactions → INQTRANL. */
    @GET
    @Path("{accountId}/transactions")
    public Response getAccountTransactions(@PathParam("accountId") String accountId,
                                           @QueryParam("fromDate") String fromDate,
                                           @QueryParam("toDate") String toDate,
                                           @QueryParam("limit") @DefaultValue("50") int limit,
                                           @QueryParam("offset") @DefaultValue("0") int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            return StubResponses.error(400, "BAD_REQUEST", "limit must be 1-100 and offset >= 0");
        }
        ObjectNode out = stubs.body("getAccountTransactions");
        ArrayNode all = out.withArray("transactions");
        ArrayNode page = out.arrayNode();
        for (int i = offset; i < Math.min(all.size(), offset + limit); i++) {
            page.add(((ObjectNode) all.get(i)).put("accountId", accountId));
        }
        out.set("transactions", page);
        out.put("totalCount", all.size()).put("limit", limit).put("offset", offset);
        return StubResponses.ok(200, ZosAsset.INQTRANL, out);
    }

    /** getTransaction → INQTRAND. */
    @GET
    @Path("{accountId}/transactions/{transactionId}")
    public Response getTransaction(@PathParam("accountId") String accountId, @PathParam("transactionId") String transactionId) {
        ObjectNode out = stubs.body("getTransaction").put("accountId", accountId).put("transactionId", transactionId);
        return StubResponses.ok(200, ZosAsset.INQTRAND, out);
    }

    static final class DepositValidation {
        static final String MESSAGE = "amount (>= 0.01) and a 6-digit sortCode are required";

        private DepositValidation() {
        }

        static boolean isValid(JsonNode body) {
            return body != null
                    && body.path("amount").isNumber()
                    && body.path("amount").decimalValue().compareTo(new BigDecimal("0.01")) >= 0
                    && body.path("sortCode").asText("").matches("\\d{6}");
        }
    }
}
