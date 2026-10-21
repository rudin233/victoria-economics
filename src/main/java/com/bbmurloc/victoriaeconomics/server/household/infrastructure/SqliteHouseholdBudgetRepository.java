package com.bbmurloc.victoriaeconomics.server.household.infrastructure;

import com.bbmurloc.victoriaeconomics.server.household.domain.*;
import com.bbmurloc.victoriaeconomics.server.household.port.HouseholdBudgetRepository;
import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import com.bbmurloc.victoriaeconomics.server.finance.infrastructure.SqliteFinanceAccountRepository;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import java.sql.*;
import java.util.*;

public final class SqliteHouseholdBudgetRepository implements HouseholdBudgetRepository {
    private final Connection connection;private final Object lock;
    private final SqliteFinanceAccountRepository finance;
    public SqliteHouseholdBudgetRepository(Connection c,Object lock){connection=c;this.lock=lock;finance=new SqliteFinanceAccountRepository(c,lock);}
    @Override public HouseholdBudget load(RegionId region,CurrencyId currency){synchronized(lock){return SqliteTransactions.read(connection,tx->{
        var budget=read(region,currency);budget.requireCoveredBy(finance.inSnapshot(tx,budget.sector().accountId()));return budget;
    });}}
    private HouseholdBudget read(RegionId region,CurrencyId currency)throws SQLException {
        try(var s=connection.prepareStatement("SELECT b.*,h.account_id FROM regional_household_budgets b JOIN regional_household_sectors h USING(region_id,currency_id) WHERE b.region_id=? AND b.currency_id=?")){
            s.setString(1,region.toString());s.setString(2,currency.toString());try(var r=s.executeQuery()){
                if(!r.next())throw new IllegalStateException("Explicit household account/sector registration required");
                return new HouseholdBudget(new RegionalHouseholdSector(region,currency,UUID.fromString(r.getString("account_id"))),r.getLong("consumption"),r.getLong("savings"),r.getLong("carried_period"),r.getLong("revision"));
            }
        }
    }
    public Session inTransaction(SqliteTransactions.WriteSession tx){tx.requireConnection(connection);return new Session(tx);}
    public final class Session {
        private final SqliteTransactions.WriteSession tx;
        private Session(SqliteTransactions.WriteSession tx){this.tx=tx;}
        public HouseholdBudget load(RegionId region,CurrencyId currency)throws SQLException{return tx.perform(()->{
            var b=read(region,currency);b.requireCoveredBy(finance.inTransaction(tx).load(b.sector().accountId()));return b;
        });}
        public Optional<HouseholdBudget> find(RegionId region,CurrencyId currency)throws SQLException{return tx.perform(()->{
            try(var s=connection.prepareStatement("SELECT 1 FROM regional_household_sectors WHERE region_id=? AND currency_id=?")){
                s.setString(1,region.toString());s.setString(2,currency.toString());try(var r=s.executeQuery()){return r.next()?Optional.of(load(region,currency)):Optional.empty();}
            }
        });}
        public HouseholdBudget open(RegionId region,CurrencyId currency,UUID account)throws SQLException{return tx.perform(()->{
            finance.inTransaction(tx).open(account,FinanceAccount.OwnerKind.REGION,region.value(),currency);
            try(var s=connection.prepareStatement("SELECT account_id FROM regional_household_sectors WHERE region_id=? AND currency_id=?")){
                s.setString(1,region.toString());s.setString(2,currency.toString());try(var r=s.executeQuery()){
                    if(r.next()){if(!r.getString(1).equals(account.toString()))throw new IllegalArgumentException("Household account identity conflict");return load(region,currency);}
                }
            }
            try(var s=connection.prepareStatement("INSERT INTO regional_household_sectors VALUES(?,?,?)")){
                s.setString(1,region.toString());s.setString(2,currency.toString());s.setString(3,account.toString());s.executeUpdate();
            }
            try(var s=connection.prepareStatement("INSERT INTO regional_household_budgets VALUES(?,?,0,0,?,0)")){
                s.setString(1,region.toString());s.setString(2,currency.toString());s.setLong(3,SqliteEconomicTimeRepository.inTransaction(connection,tx).periodId());s.executeUpdate();
            }
            return load(region,currency);
        });}
        public HouseholdBudget save(HouseholdBudget changed,long expected)throws SQLException{return tx.perform(()->{
            var old=read(changed.sector().region(),changed.sector().currency());
            if(old.revision()!=expected||changed.revision()!=Math.incrementExact(expected)||!old.sector().equals(changed.sector())
                    ||changed.carriedPeriod()<old.carriedPeriod()||changed.carriedPeriod()>SqliteEconomicTimeRepository.inTransaction(connection,tx).periodId())throw new IllegalStateException("Household budget CAS/period conflict");
            changed.requireCoveredBy(finance.inTransaction(tx).load(changed.sector().accountId()));
            try(var s=connection.prepareStatement("UPDATE regional_household_budgets SET consumption=?,savings=?,carried_period=?,revision=? WHERE region_id=? AND currency_id=? AND revision=?")){
                s.setLong(1,changed.consumption());s.setLong(2,changed.savings());s.setLong(3,changed.carriedPeriod());s.setLong(4,changed.revision());
                s.setString(5,changed.sector().region().toString());s.setString(6,changed.sector().currency().toString());s.setLong(7,expected);
                if(s.executeUpdate()!=1)throw new IllegalStateException("Household budget CAS conflict");
            }
            return changed;
        });}
        public void carryAll(long period)throws SQLException {tx.perform(()->{
            var all=new ArrayList<HouseholdBudget>();
            try(var s=connection.createStatement();var r=s.executeQuery("SELECT region_id,currency_id FROM regional_household_budgets")){
                while(r.next())all.add(load(RegionId.parse(r.getString(1)),CurrencyId.parse(r.getString(2))));
            }
            for(var b:all)if(b.carriedPeriod()<period)save(b.carryThrough(period),b.revision());
            return null;
        });}
    }
}
