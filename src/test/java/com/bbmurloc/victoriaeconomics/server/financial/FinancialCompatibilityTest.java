package com.bbmurloc.victoriaeconomics.server.financial;

import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FinancialCompatibilityTest {
    @TempDir Path dir;
    @Test void completeV9FinanceAndPayrollGraphPassesReadOnlyGateWithoutByteChanges() throws Exception {
        Path file=dir.resolve("valid.sqlite");
        try(var w=new FinancialTestWorld(file)) {
            UUID a=w.companyAccount(),b=w.regionAccount();w.fundingFixture(a,100);
            w.finance.reserve(new CashReservation(UUID.randomUUID(),a,30,"fixture order",true));
            w.finance.transfer(new TransferReceipt(UUID.randomUUID(),a,b,FinancialTestWorld.CURRENCY,20));
            UUID building=w.staffingFixture(3,7);w.enroll(building);w.acceptedTimeFixture(1301);w.accrual.recoverAcceptedTime();
        }
        byte[] before=Files.readAllBytes(file);try(var ignored=SqliteEconomyDatabase.open(file)) {}assertArrayEquals(before,Files.readAllBytes(file));
    }
    @ParameterizedTest @ValueSource(strings={"missingClock","payrollAhead","badCurrency","unrecordedCash","missingLedger","unbalancedTransfer","cashOverreserved","missingLiability","fakePaid","badRemainder","legacyBatchTag","legacyInventoryTag","fractionalWage"})
    void incompatibleFinancialFactsAreRejectedBeforeAnyBusinessWrites(String mutation) throws Exception {
        Path file=dir.resolve("invalid.sqlite");
        try(var w=new FinancialTestWorld(file)) {
            UUID a=w.companyAccount(),b=w.regionAccount();w.fundingFixture(a,100);UUID building=w.staffingFixture(1,7);w.enroll(building);
            w.finance.transfer(new TransferReceipt(UUID.randomUUID(),a,b,FinancialTestWorld.CURRENCY,20));
            w.acceptedTimeFixture(1200);w.accrual.recoverAcceptedTime();
            w.sql("PRAGMA foreign_keys=OFF");w.sql("PRAGMA ignore_check_constraints=ON");
            switch(mutation) {
                case "missingClock"->w.sql("DELETE FROM economic_time");
                case "payrollAhead"->w.sql("UPDATE payroll_runtime SET processed_tick=1201");
                case "badCurrency"->w.sql("UPDATE currencies SET id='implicit-default'");
                case "unrecordedCash"->w.sql("UPDATE finance_accounts SET balance=999");
                case "missingLedger"->w.sql("DELETE FROM finance_ledger WHERE line=1");
                case "unbalancedTransfer"->w.sql("UPDATE finance_events SET amount=19 WHERE kind='TRANSFER'");
                case "cashOverreserved"->w.sql("INSERT INTO cash_reservations VALUES('"+UUID.randomUUID()+"','"+a+"',100,'bad',1)");
                case "missingLiability"->w.sql("DELETE FROM payroll_liabilities");
                case "fakePaid"->{w.sql("UPDATE payroll_accruals SET paid=1");w.sql("UPDATE payroll_liabilities SET paid=1");}
                case "badRemainder"->w.sql("UPDATE payroll_accruals SET remainder=1200");
                case "fractionalWage"->w.sql("UPDATE regional_wage_rates SET amount=1.5");
                case "legacyInventoryTag"->{var stock=new com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore(w.c,w.world.lock);stock.createLocation(building,100);w.sql("UPDATE inventory_locations SET state_json=replace(state_json,'\"formatVersion\":9','\"formatVersion\":7')");}
                default->{
                    var recipe=new com.bbmurloc.victoriaeconomics.server.production.domain.ResolvedProductionRecipe(Map.of("a",1L),Map.of("b",1L),Map.of("laborer",1));
                    var snapshot=new com.bbmurloc.victoriaeconomics.server.production.domain.ProductionBatchConfiguration(Map.of("base","test"),recipe,new com.bbmurloc.victoriaeconomics.server.production.domain.WorkforcePlan(Map.of("laborer",1),1,1));
                    try(var s=w.c.prepareStatement("INSERT INTO production_batches VALUES(?,?,?,0,'ACTIVE')")) {s.setString(1,UUID.randomUUID().toString());s.setString(2,building.toString());s.setString(3,EconomyStateCodec.encodeBatchConfiguration(snapshot).replace("\"formatVersion\":9","\"formatVersion\":7"));s.executeUpdate();}
                }
            }
        }
        byte[] before=Files.readAllBytes(file);assertThrows(IllegalStateException.class,()->SqliteEconomyDatabase.open(file));assertArrayEquals(before,Files.readAllBytes(file));
    }
}
