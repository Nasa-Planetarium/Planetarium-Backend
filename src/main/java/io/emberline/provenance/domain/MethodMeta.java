package io.emberline.provenance.domain;

/** provenance.method_meta of one release: m2v_meta.json text as-is and what ingest recorded. */
public record MethodMeta(String methodVersion, String metaJson, String aliasesUsedJson, String warningsJson, String filesJson) {
}
