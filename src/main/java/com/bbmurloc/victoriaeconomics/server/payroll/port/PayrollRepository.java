package com.bbmurloc.victoriaeconomics.server.payroll.port;

import com.bbmurloc.victoriaeconomics.server.payroll.domain.*;
import java.util.*;

public interface PayrollRepository {
    PayrollAccrual load(UUID segmentId);
    List<PayrollLiability> liabilities(UUID segmentId);
}
