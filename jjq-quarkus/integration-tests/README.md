# jjq-quarkus integration tests

A real consumer application following the pattern in core Quarkus's
`integration-tests/jackson`: application resources and models live in
`src/main`, HTTP assertions live in a `@QuarkusTest`, and a
`@QuarkusIntegrationTest` subclass reuses those assertions against the packaged
application. Nothing is assembled with ShrinkWrap, and tests do not inject
application beans into the external test process.

The app depends on `jjq-quarkus`, both annotation bridge modules, and one REST
stack. It does not enable `quarkus-jackson`, a JSON-B serialization engine, or
Hibernate. The POM-only test dependency on `jjq-quarkus-deployment` establishes
reactor order without adding deployment code to the application runtime.
This test-only artifact is not published.

## Run the tests

Run commands from the repository root. The default `verify` runs both the
in-process JVM tests and the packaged JVM tests:

```sh
./mvnw -pl jjq-quarkus/integration-tests -am verify
```

The `classic` profile selects `quarkus-resteasy` instead of `quarkus-rest`.
It builds a separate application variant with the same HTTP contract; the
two stacks are never dependencies of the same build:

```sh
./mvnw -pl jjq-quarkus/integration-tests -am clean verify -Pclassic
```

Use `clean` when switching variants so packaged outputs cannot be mistaken
for those from a different REST stack. Native builds are opt-in. Select a
GraalVM installation compatible with Quarkus 3.40.1, for example the installed
SDKMAN GraalVM CE 25.0.1:

```sh
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.1-graalce"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -pl jjq-quarkus/integration-tests -am verify -Pnative \
  -Dquarkus.native.native-image-xmx=4g \
  -Dquarkus.native.additional-build-args=--exact-reachability-metadata=io.hyperfoil.tools.jjq.quarkus.it
```

Use `-Pnative,classic` to run the same native tests with Classic RESTEasy.
`JjqResourceIT` targets whichever artifact was packaged, not a second
in-process test application. Both test modes use an ephemeral HTTP port.
There are no timing assertions or performance claims in these tests.

## Run the app manually

Install the current extension and its dependencies before launching this
module on its own:

```sh
./mvnw -pl jjq-quarkus/integration-tests -am install -DskipTests
./mvnw -pl jjq-quarkus/integration-tests quarkus:dev
```

Then POST JSON to port 8080:

```sh
curl -i -H 'Content-Type: application/json' \
  -d '{"message":"hello","count":3}' http://localhost:8080/jjq/record
curl -i -H 'Content-Type: application/json' \
  -d '{"display-name":"Alice","auth":"api-key"}' http://localhost:8080/jjq/jackson
```

Endpoints also cover a JSON-B property rename at `/jjq/jsonb`, `JqValue`
passthrough at `/jjq/value`, and application code using the injected mapper at
`/jjq/mapper`. `/jjq/mapper/strict` reports the mapper's unknown-property
setting. This fixture explicitly enables `quarkus.jjq.fail-on-unknown-properties`
to exercise the opt-in configuration. The extension's default remains false.
`/jjq/failure` deliberately throws an application exception to verify that it
remains a 500 rather than being translated into a client error.

## What the tests prove

The shared HTTP tests cover record and bridge round-trips, enum creator
failures, strict JSON syntax and UTF-8 validation, binding paths, null and empty
bodies, configured unknown-property rejection, bounded diagnostics, and the
framework's 413, 415, and 500 status handling.

Records use `jjq-mapper-processor` at application compilation. The extension
generates `AuthType_EnumBinding` during augmentation. No application model has
`@RegisterForReflection`, and there is no hand-written reflection configuration.
Existing deployment bytecode tests remain necessary: a native round-trip alone
does not prove the absence of reflection. This fixture does not claim to test
augmentation-time record generation without annotation processing or every
reflective fallback.

Hibernate integration stays in the deployment tests and should receive its own
consumer app rather than adding persistence startup work to this REST app.
