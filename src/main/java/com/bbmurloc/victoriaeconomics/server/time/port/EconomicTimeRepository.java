package com.bbmurloc.victoriaeconomics.server.time.port;

import com.bbmurloc.victoriaeconomics.server.time.domain.EconomicTime;

public interface EconomicTimeRepository {
    EconomicTime load();
    EconomicTime acceptNext(long expectedTick);
    void acknowledgeProductionCallback(long tick);
    void recordRecoveryGap();
}
