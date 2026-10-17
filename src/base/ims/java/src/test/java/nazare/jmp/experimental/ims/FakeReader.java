package nazare.jmp.experimental.ims;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class FakeReader implements ImsSegmentReader {
    final Map<Integer, CustomerSegment> customers = new HashMap<>();
    final List<CustomerAccountSegment> links = new ArrayList<>();
    final Map<Long, AccountSegment> accounts = new HashMap<>();
    final List<Long> accountLookups = new ArrayList<>();
    SQLException failure;

    @Override
    public Optional<CustomerSegment> findCustomer(int custId) throws SQLException {
        if (failure != null) {
            throw failure;
        }
        return Optional.ofNullable(customers.get(custId));
    }

    @Override
    public List<CustomerAccountSegment> findCustomerAccounts(int custId) throws SQLException {
        if (failure != null) {
            throw failure;
        }
        return links.stream().filter(l -> l.custId() == custId).toList();
    }

    @Override
    public Optional<AccountSegment> findAccount(long accId) {
        accountLookups.add(accId);
        return Optional.ofNullable(accounts.get(accId));
    }
}
