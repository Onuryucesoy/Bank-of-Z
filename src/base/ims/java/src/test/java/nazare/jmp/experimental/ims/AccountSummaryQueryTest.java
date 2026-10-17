package nazare.jmp.experimental.ims;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import nazare.jmp.cobol.Money;

@ExtendWith(ExperimentalFlag.class)
class AccountSummaryQueryTest {

    private FakeReader reader;
    private AccountSummaryQuery query;

    @BeforeEach
    void setUp() {
        reader = new FakeReader();
        query = new AccountSummaryQuery(reader);
    }

    private void link(int custId, long accId, int accNum, boolean accountExists) {
        reader.links.add(new CustomerAccountSegment(custId, accId, accNum));
        if (accountExists) {
            reader.accounts.put(accId, new AccountSegment(accId, accNum == 1 ? "c" : "s", Money.of("100.00"), 1));
        }
    }

    @Test
    void returnsAccountsInLinkOrderWithZeroFilledMessage() {
        link(2, 202, 2, true);
        link(2, 201, 1, true);
        AccountSummary s = query.summarize(2);
        assertEquals(AccountSummary.Outcome.OK, s.outcome());
        assertEquals("00000000000000000000000000000000", s.message());
        assertEquals(List.of(202L, 201L), s.accounts().stream().map(AccountSegment::accId).toList());
    }

    @Test
    void noLinksMeansNoCustomerEvenIfTheCustomerSegmentExists() {
        AccountSummary s = query.summarize(9);
        assertEquals(AccountSummary.Outcome.NO_CUSTOMER, s.outcome());
        assertEquals("CUSTOMER DOES NOT EXIST", s.message());
    }

    @Test
    void firstMissingAccountIsNoAccount() {
        link(3, 301, 1, false);
        link(3, 302, 2, true);
        AccountSummary s = query.summarize(3);
        assertEquals(AccountSummary.Outcome.NO_ACCOUNT, s.outcome());
        assertEquals("ACCOUNT DOES NOT EXIST", s.message());
        assertEquals(List.of(301L), reader.accountLookups, "stops at the first miss");
    }

    @Test
    void laterMissingAccountSilentlyEndsTheList() {
        link(4, 401, 1, true);
        link(4, 402, 2, false);
        link(4, 403, 3, true);
        AccountSummary s = query.summarize(4);
        assertEquals(AccountSummary.Outcome.OK, s.outcome());
        assertEquals(1, s.accounts().size());
        assertEquals(List.of(401L, 402L), reader.accountLookups);
    }

    @Test
    void sixAccountsFitSevenDoNot() {
        for (int i = 1; i <= 6; i++) {
            link(5, 500 + i, i, true);
        }
        assertEquals(6, query.summarize(5).accounts().size());
        link(5, 507, 7, true);
        AccountSummary s = query.summarize(5);
        assertEquals(AccountSummary.Outcome.MORE_THAN_SIX_ACCOUNTS, s.outcome());
        assertEquals(6, s.accounts().size());
    }

    @Test
    void otherFailuresBecomeBadStatus() {
        reader.failure = new SQLException("boom", "AI000");
        AccountSummary s = query.summarize(1);
        assertEquals(AccountSummary.Outcome.BAD_STATUS, s.outcome());
        assertEquals("BAD STATUS CODE WAS RECEIVED: AI", s.message());
    }
}
