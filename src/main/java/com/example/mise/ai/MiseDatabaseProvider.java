package com.example.mise.ai;

import com.example.mise.domain.reports.ReportSnapshotService;
import com.vaadin.flow.component.ai.provider.DatabaseProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * UC-012 (BR-02/BR-03): the AI's only SQL surface. Exposes the curated
 * reporting schema from {@link ReportSnapshotService} and executes SELECT-only
 * queries against it. The schema description names just the snapshot tables, so
 * the model never learns about the JPA tables; the SELECT-only guard blocks
 * DML/DDL outright.
 */
@Component
public class MiseDatabaseProvider implements DatabaseProvider {

    private final transient JdbcTemplate jdbc;
    private final transient ReportSnapshotService snapshot;

    public MiseDatabaseProvider(JdbcTemplate jdbc, ReportSnapshotService snapshot) {
        this.jdbc = jdbc;
        this.snapshot = snapshot;
    }

    @Override
    public String getSchema() {
        return "SQL dialect: H2.\n\n"
                + ReportSnapshotService.SCHEMA_DDL + "\n"
                + ReportSnapshotService.SCHEMA_NOTES;
    }

    @Override
    public List<Map<String, Object>> executeQuery(String sql) {
        String trimmed = sql == null ? "" : sql.strip();
        // BR-03: SELECT-only, single statement. The error text goes back to the
        // model as a tool result, so it can self-correct.
        if (!trimmed.toLowerCase().startsWith("select")
                || trimmed.replaceAll(";\\s*$", "").contains(";")) {
            throw new IllegalArgumentException(
                    "Only single SELECT statements are allowed against the reporting schema.");
        }
        snapshot.rebuildIfDirty();
        try {
            return jdbc.queryForList(trimmed);
        } catch (DataAccessException e) {
            // The error text is fed back to the model as a tool result so it can self-correct.
            // A bare "Function DATE_FORMAT not found" leaves the model retrying the same MySQL
            // syntax blindly (#85); append an H2-dialect hint so the next attempt switches.
            String detail = e.getMostSpecificCause().getMessage();
            String hint = "";
            if (detail != null && detail.toUpperCase().contains("DATE_FORMAT")) {
                hint = " H2 has no DATE_FORMAT(); for month bucketing use "
                        + "FORMATDATETIME(col, 'yyyy-MM') (or YEAR(col)/MONTH(col)).";
            }
            throw new IllegalArgumentException(
                    "Query failed (H2): " + detail + hint, e);
        }
    }
}
