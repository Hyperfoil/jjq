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

    /** POJO equivalent of SimpleRecord for benchmarking POJO vs record mapping performance. */
    @JqMapped
    public static class SimplePojo {
        private String name;
        private int age;
        private double score;
        private boolean active;
        private String email;

        public SimplePojo() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    @JqMapped
    public record Address(String city, String zip, String country) {}

    @JqMapped
    public record PersonRecord(String name, int age, Address address) {}

    @JqMapped
    public record Item(String name, double price, int quantity) {}

    @JqMapped
    public record OrderRecord(String id, String customer, List<Item> items) {}
}
