package dev.gridiron.core.ingest

/** A build's numbers failed a check; the new database is discarded and the old one kept. */
public class ValidationException(public val problems: List<String>) :
    IllegalStateException("${problems.size} validation failure(s); first: ${problems.first()}")
