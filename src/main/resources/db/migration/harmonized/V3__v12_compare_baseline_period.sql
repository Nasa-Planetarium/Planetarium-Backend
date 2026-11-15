-- data v1.2 stage r3 (model api_contract 「예년 비교 기준 기간」): aoi_month_compare names the baseline
-- period of the region (baseline_period '2001-2025' | '2012-2025'), the continuity check behind it (continuity_ratio) and
-- whose ratio it is (continuity_source 'own' | 'parent:<aoi_id>'). Stored as the file has them; NULL for earlier releases.
ALTER TABLE aoi_month_compare ADD COLUMN baseline_period text, ADD COLUMN continuity_ratio double precision,
    ADD COLUMN continuity_source text;
