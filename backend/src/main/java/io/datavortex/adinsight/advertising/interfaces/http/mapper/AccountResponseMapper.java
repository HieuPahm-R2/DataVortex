package io.datavortex.adinsight.advertising.interfaces.http.mapper;

import io.datavortex.adinsight.advertising.domain.model.AdAccount;
import io.datavortex.adinsight.advertising.interfaces.http.dto.AccountListResponse;
import io.datavortex.adinsight.advertising.interfaces.http.dto.AccountResponse;
import java.util.List;

public final class AccountResponseMapper {
    private AccountResponseMapper() {}

    public static AccountListResponse from(List<AdAccount> accounts, String requestId) {
        return new AccountListResponse(
                accounts.stream().map(AccountResponseMapper::from).toList(), requestId);
    }

    public static AccountResponse from(AdAccount account) {
        return new AccountResponse(
                account.id().toString(),
                account.platform().name(),
                account.sourceAccountId(),
                account.currency().getCurrencyCode(),
                account.timezone().getId(),
                account.status());
    }
}
