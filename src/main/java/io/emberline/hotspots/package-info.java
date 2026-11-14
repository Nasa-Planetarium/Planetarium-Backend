/**
 * Bounded context <b>hotspots</b>: harmonized hotspot points read straight from the active release's
 * {@code harmonized_hotspots/} parquet folders (DuckDB, read-only). Other modules may only use its {@code api} package.
 */
@org.springframework.modulith.ApplicationModule(displayName = "hotspots")
package io.emberline.hotspots;
