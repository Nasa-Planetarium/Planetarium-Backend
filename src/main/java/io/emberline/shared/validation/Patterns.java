package io.emberline.shared.validation;

/** Input patterns shared by request records (path and query validation). */
public final class Patterns {

    /**
     * aoi_id: the contract pattern {@code ^[A-Za-z0-9_:.\-]{1,64}$} widened by {@code +} and {@code ?}, because the
     * release really has ids such as {@code ADM1_ATA+00?} (30 rows; E1: the file is canonical). See docs/DEVIATIONS.md.
     */
    public static final String AOI_ID = "^[A-Za-z0-9_:.+?\\-]{1,64}$";
    public static final String AOI_LEVEL = "^(world|continent|country|adm1|kr_sido|kr_sgg|group)$";
    public static final String METHOD_VERSION = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";
    public static final String CURSOR = "^[A-Za-z0-9_-]{1,512}$";
    public static final String DATE = "^\\d{4}-\\d{2}-\\d{2}$";

    private Patterns() {
    }
}
