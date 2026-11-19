package io.sustc.service.impl.importer;

/**
 * Thrown when the bulk import fails. The message names the failed phase, and the cause is the
 * original failure. If the failure happened after the import started changing the database, the
 * imported tables are left empty with their full schema; the users audit log keeps the failed
 * attempt's records (see {@link BulkImporter}). A non-empty database is refused with an {@link
 * IllegalStateException} instead, before anything is changed.
 */
public class ImportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ImportException(String message, Throwable cause) {
        super(message, cause);
    }

    public ImportException(String message) {
        super(message);
    }
}
