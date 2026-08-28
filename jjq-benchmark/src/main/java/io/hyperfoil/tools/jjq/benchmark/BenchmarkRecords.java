package io.hyperfoil.tools.jjq.benchmark;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

import java.util.List;

/**
 * Record types used in mapper benchmarks.
 * Top-level class so the annotation processor can generate mappings correctly.
 */
public final class BenchmarkRecords {
    private BenchmarkRecords() {}

    @JqMapped
    public record SimpleRecord(String name, int age, double score, boolean active, String email) {}

    @JqMapped
    public record Address(String city, String zip, String country) {}

    @JqMapped
    public record PersonRecord(String name, int age, Address address) {}

    @JqMapped
    public record Item(String name, double price, int quantity) {}

    @JqMapped
    public record OrderRecord(String id, String customer, List<Item> items) {}
}
