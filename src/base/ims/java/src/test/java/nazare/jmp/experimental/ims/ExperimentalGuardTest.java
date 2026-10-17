package nazare.jmp.experimental.ims;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import nazare.jmp.experimental.ExperimentalFeatures;

class ExperimentalGuardTest {

    @BeforeEach
    void flagIsOff() {
        System.clearProperty(ExperimentalFeatures.IMS_READ_ONLY_PROPERTY);
    }

    @Test
    void everyEntryPointIsDisabledByDefault() {
        assertFalse(ExperimentalFeatures.imsReadOnlyEnabled());
        assertThrows(IllegalStateException.class, () -> new JdbcImsSegmentReader(null, null));
        assertThrows(IllegalStateException.class, () -> new AccountSummaryQuery(new FakeReader()));
        assertThrows(IllegalStateException.class, () -> new LoginCheck(new FakeReader()));
        assertThrows(IllegalStateException.class,
                () -> ImsDataSources.type4("host", 9999, "IMS1", ImsDataSources.ACCOUNT_READ_PSB, "user"));
    }

    @Test
    void onlyReadOnlyPsbsAreAccepted() {
        System.setProperty(ExperimentalFeatures.IMS_READ_ONLY_PROPERTY, "true");
        try {
            assertThrows(IllegalArgumentException.class, () -> ImsDataSources.type4("host", 9999, "IMS1", "IBLOGIN", "user"));
            assertThrows(IllegalArgumentException.class, () -> ImsDataSources.type4("host", 9999, "IMS1", "IBTRAN", "user"));
            ImsDataSources.type4("host", 9999, "IMS1", ImsDataSources.CUSTOMER_READ_PSB, "user");
        } finally {
            System.clearProperty(ExperimentalFeatures.IMS_READ_ONLY_PROPERTY);
        }
    }
}
