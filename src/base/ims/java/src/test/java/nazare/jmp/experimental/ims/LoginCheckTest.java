package nazare.jmp.experimental.ims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import nazare.jmp.experimental.ims.LoginCheck.Decision;

@ExtendWith(ExperimentalFlag.class)
class LoginCheckTest {

    private static CustomerSegment customer(String status, String password) {
        return new CustomerSegment(1, "Antonelli", "Martha", "922 East Meighan Boulevard", "ART", "TX", "76820",
                "914-513-5089", status, password, "c", "2003-07-23 17:56:00.64");
    }

    @Test
    void decisionOrderMatchesIblogin() {
        assertEquals(Decision.NO_CUSTOMER, LoginCheck.decide(Optional.empty(), "x"));
        assertEquals(Decision.PASSWORD_INVALID, LoginCheck.decide(Optional.of(customer("1", "secret")), "wrong"),
                "password is checked before the logged-in flag");
        assertEquals(Decision.ALREADY_LOGGED_IN, LoginCheck.decide(Optional.of(customer("1", "secret")), "secret"));
        assertEquals(Decision.WOULD_LOG_IN, LoginCheck.decide(Optional.of(customer("0", "secret")), "secret"));
    }

    @Test
    void passwordsCompareAsChar16() {
        assertEquals(Decision.WOULD_LOG_IN, LoginCheck.decide(Optional.of(customer("0", "secret          ")), "secret"));
        assertEquals(Decision.WOULD_LOG_IN,
                LoginCheck.decide(Optional.of(customer("0", "pppppppppppppppp")), "ppppppppppppppppIGNORED"));
        assertEquals(Decision.PASSWORD_INVALID, LoginCheck.decide(Optional.of(customer("0", "Secret")), "secret"),
                "case sensitive");
    }

    @Test
    void anyStatusOtherThanOneCountsAsLoggedOut() {
        assertEquals(Decision.WOULD_LOG_IN, LoginCheck.decide(Optional.of(customer(" ", "p")), "p"));
    }

    @Test
    void messagesMatchTheProgramConstants() {
        assertEquals("CUSTOMER DOES NOT EXIST", LoginCheck.message(Decision.NO_CUSTOMER));
        assertEquals("PASSWORD INVALID", LoginCheck.message(Decision.PASSWORD_INVALID));
        assertEquals("CUSTOMER ALREADY LOGGED IN", LoginCheck.message(Decision.ALREADY_LOGGED_IN));
        assertEquals("LOGIN SUCCESSFUL", LoginCheck.message(Decision.WOULD_LOG_IN));
    }

    @Test
    void toStringRedactsPassword() {
        assertFalse(customer("0", "hunter2").toString().contains("hunter2"));
    }

    @Test
    void checkUsesTheReader() throws Exception {
        FakeReader reader = new FakeReader();
        reader.customers.put(1, customer("0", "pw"));
        LoginCheck check = new LoginCheck(reader);
        assertEquals(Decision.WOULD_LOG_IN, check.check(1, "pw"));
        assertEquals(Decision.NO_CUSTOMER, check.check(2, "pw"));
    }
}
