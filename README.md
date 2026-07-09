# StandpointSHIQ → SHIQ Translation Pipeline

A Protégé plugin that translates OWL ontologies annotated with **standpoint modal logic** into standard SHIQ, producing a translated `.rdf` output file.

---

## What it does

The pipeline runs in three stages:

1. **Normalisation** — converts OWL axioms to SubClassOf GCIs and applies negation normal form (NNF).
2. **Precisification** — builds the set of possible worlds from standpoint-annotated axioms and sharpening constraints.
3. **Translation** — encodes each world as standard SHIQ axioms and writes the result to an `.rdf` file.

---

## Before you run

> **Important: enable "Provided" scope in your Run Configuration.**

The core OWL API (`owlapi-distribution`) and Protégé framework (`protege-editor-owl`) are declared as `provided` scope in `pom.xml` — meaning Maven assumes the host environment (Protégé) supplies them at runtime, so it does **not** bundle them on the classpath.

When running `Main.java` standalone from ide, those jars are absent by default, causing `ClassNotFoundException` at startup.

**Fix (IDE):**

1. Open **Run → Edit Configurations…**
2. Select (or create) a configuration for `Main`.
3. Click **Modify Options** → tick **"Include dependencies with 'Provided' scope"**.
4. Apply and run.

This adds the `provided`-scoped jars to the runtime classpath for that configuration only — the OSGi bundle packaging is unaffected.

---

## Running standalone

Edit the two paths at the top of `Main.java` to point to your input ontology and desired output location:

```java
File inputFile  = new File("/path/to/your/ontology.rdf");
File outputFile = new File("/path/to/output/translated.rdf");
```

Then run `Main.main()`. Console output is controlled by `PipelineLogger.setLevel(...)`:

- `Level.ON` — full pipeline trace
- `Level.OFF` — silent

---

## Example ontologies

Place `.rdf` example files in the [`examples/`](examples/) folder.  
See [`annotation_syntax.md`](annotation_syntax.md) for the full standpoint annotation syntax used in input ontologies.

---

## Building

```bash
mvn clean package
```

The output OSGi bundle (`target/*.jar`) can be dropped into Protégé's `plugins/` directory.

**Requirements:** Java 11, Maven 3.x.