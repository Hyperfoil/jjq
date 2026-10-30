package io.hyperfoil.tools.jjq.quarkus.deployment;

import java.util.List;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * Structured result of the JSON endpoint report (issue #198): one entry
 * per endpoint direction, plus the counts the summary line prints.
 * Produced so the reporting step always executes; tests consume it
 * instead of parsing log output.
 */
public final class JjqEndpointReportBuildItem extends SimpleBuildItem {

    private final List<EndpointReport.Entry> entries;

    public JjqEndpointReportBuildItem(List<EndpointReport.Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** Entries sorted by path, method, and direction (never null). */
    public List<EndpointReport.Entry> entries() {
        return entries;
    }
}
