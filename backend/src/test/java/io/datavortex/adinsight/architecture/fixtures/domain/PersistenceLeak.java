package io.datavortex.adinsight.architecture.fixtures.domain;

public class PersistenceLeak {
    public Class<?> annotationType() {
        return jakarta.persistence.Entity.class;
    }
}
