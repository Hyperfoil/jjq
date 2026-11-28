package io.hyperfoil.tools.jjq.quarkus.test;

import io.hyperfoil.tools.jjq.mapper.JqMapped;

/**
 * Flat JavaBean request fixture (issue #4): private fields with public
 * getters/setters and a public no-arg constructor — the openapi-generator
 * model shape, eligible for direct reader generation.
 */
@JqMapped
public class DirectPojo {

    private String name;
    private int age;
    private Long score;

    public DirectPojo() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getAge() { return age; }
    public void setAge(int age) { this.age = age; }
    public Long getScore() { return score; }
    public void setScore(Long score) { this.score = score; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DirectPojo p)) return false;
        return age == p.age && java.util.Objects.equals(name, p.name)
                && java.util.Objects.equals(score, p.score);
    }

    @Override
    public int hashCode() { return java.util.Objects.hash(name, age, score); }

    @Override
    public String toString() { return "DirectPojo[" + name + "," + age + "," + score + "]"; }
}
