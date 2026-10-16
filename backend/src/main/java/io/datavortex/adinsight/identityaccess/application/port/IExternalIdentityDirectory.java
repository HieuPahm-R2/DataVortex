package io.datavortex.adinsight.identityaccess.application.port;

import java.util.Optional;

public interface IExternalIdentityDirectory {
    Optional<String> activeUserId(String issuer, String subject);
}
