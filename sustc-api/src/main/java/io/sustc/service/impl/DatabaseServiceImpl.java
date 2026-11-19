package io.sustc.service.impl;

import io.sustc.dto.DanmuRecord;
import io.sustc.dto.UserRecord;
import io.sustc.dto.VideoRecord;
import io.sustc.service.DatabaseService;
import io.sustc.service.impl.importer.BulkImporter;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseServiceImpl implements DatabaseService {

    /** The course framework uses this id to name the benchmark report file. */
    private static final List<Integer> GROUP_MEMBERS = List.of(12345678);

    /**
     * Truncates every table of the current schema in one statement and resets identity columns.
     * The audit log is truncated too; its TRUNCATE trigger on users then records this wipe, so the
     * log holds exactly that one record afterwards.
     */
    private static final String TRUNCATE_ALL =
            """
            do $$
            declare
                tables text;
            begin
                select string_agg(format('%I', tablename), ', ') into tables
                from pg_tables where schemaname = current_schema();
                if tables is not null then
                    execute 'truncate table ' || tables || ' restart identity cascade';
                end if;
            end
            $$
            """;

    private final JdbcTemplate jdbc;
    private final BulkImporter importer;

    public DatabaseServiceImpl(JdbcTemplate jdbc, BulkImporter importer) {
        this.jdbc = jdbc;
        this.importer = importer;
    }

    @Override
    public List<Integer> getGroupMembers() {
        return GROUP_MEMBERS;
    }

    @Override
    public void importData(
            List<DanmuRecord> danmuRecords, List<UserRecord> userRecords, List<VideoRecord> videoRecords) {
        importer.importData(danmuRecords, userRecords, videoRecords);
    }

    @Override
    public void truncate() {
        jdbc.execute(TRUNCATE_ALL);
    }

    @Override
    public Integer sum(int a, int b) {
        return jdbc.queryForObject("select ? + ?", Integer.class, a, b);
    }
}
