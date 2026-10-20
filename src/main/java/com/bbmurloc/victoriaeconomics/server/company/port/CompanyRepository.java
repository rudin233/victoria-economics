package com.bbmurloc.victoriaeconomics.server.company.port;

import com.bbmurloc.victoriaeconomics.server.company.domain.*;
import java.util.Optional;

public interface CompanyRepository {
    void create(Company company);
    Optional<Company> find(CompanyId id);
}
