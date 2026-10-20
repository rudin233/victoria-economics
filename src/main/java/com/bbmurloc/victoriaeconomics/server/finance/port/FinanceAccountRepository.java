package com.bbmurloc.victoriaeconomics.server.finance.port;

import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import java.util.*;

public interface FinanceAccountRepository {
    FinanceAccount load(UUID id);
    List<LedgerEntry> ledger(UUID accountId);
    Optional<TransferReceipt> verify(TransferReceipt command);
}
