package io.datavortex.adinsight.ingestion.infrastructure.persistence;

import io.datavortex.adinsight.ingestion.domain.model.DateRange;
import io.datavortex.adinsight.ingestion.domain.model.IngestionJob;
import io.datavortex.adinsight.ingestion.domain.model.IngestionRequest;
import io.datavortex.adinsight.ingestion.domain.model.IngestionStage;
import io.datavortex.adinsight.ingestion.domain.model.IngestionStatus;
import io.datavortex.adinsight.ingestion.domain.model.ReportKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

final class IngestionJobRowMapper implements RowMapper<IngestionJob> {
    @Override
    public IngestionJob mapRow(ResultSet row, int index) throws SQLException {
        var request =
                new IngestionRequest(
                        UUID.fromString(row.getString("tenant_id")),
                        UUID.fromString(row.getString("account_id")),
                        ReportKind.valueOf(row.getString("report_kind")),
                        new DateRange(
                                row.getDate("date_from").toLocalDate(),
                                row.getDate("date_to").toLocalDate()),
                        row.getString("logical_request_id"));
        return new IngestionJob(
                UUID.fromString(row.getString("id")),
                request,
                row.getLong("run_sequence"),
                IngestionStatus.valueOf(row.getString("status")),
                IngestionStage.valueOf(row.getString("stage")),
                row.getInt("attempt"),
                row.getLong("rows_received"),
                row.getLong("rows_loaded"),
                row.getLong("rows_rejected"),
                instant(row, "created_at"),
                instant(row, "started_at"),
                instant(row, "finished_at"),
                instant(row, "next_retry_at"),
                row.getString("error_code"),
                row.getString("raw_manifest_uri"),
                row.getString("normalized_manifest_uri"),
                row.getString("rejected_uri"));
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
