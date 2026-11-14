-- catalog: aoi_list.parquet + aoi_boundaries.geojson, one LIST partition per release (created by CatalogReleaseLoader).
-- level holds the hand-off value (kr→country, sido→kr_sido, sgg→kr_sgg); level_file keeps the file value as-is.
CREATE TABLE aoi (
    release_id  bigint           NOT NULL,
    aoi_id      text             NOT NULL,
    name_ko     text,
    name_en     text,
    level       text             NOT NULL CHECK (level IN ('world', 'continent', 'country', 'adm1', 'kr_sido', 'kr_sgg', 'group')),
    level_file  text             NOT NULL,
    level_rank  smallint         NOT NULL,
    parent_id   text,
    n_cells     integer          NOT NULL CHECK (n_cells >= 0),
    hidden      boolean          NOT NULL,
    has_data    boolean          NOT NULL,
    first_month text,
    last_month  text,
    bbox_west   double precision,
    bbox_south  double precision,
    bbox_east   double precision,
    bbox_north  double precision
) PARTITION BY LIST (release_id);

-- geometry = the feature geometry object of aoi_boundaries.geojson as-is (PostGIS deferred); bbox computed with JTS.
CREATE TABLE aoi_boundary (
    release_id    bigint NOT NULL,
    aoi_id        text   NOT NULL,
    geometry_type text,
    geometry      jsonb
) PARTITION BY LIST (release_id);
