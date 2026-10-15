package io.datavortex.adinsight.architecture.fixtures.application;

public record DatabaseLeak(org.springframework.jdbc.core.JdbcTemplate database) {}
