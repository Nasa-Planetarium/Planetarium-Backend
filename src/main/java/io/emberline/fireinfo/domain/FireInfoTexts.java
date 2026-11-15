package io.emberline.fireinfo.domain;

/**
 * Fixed texts of the fire-info responses. FWI and spread notices are the contract examples' texts; the event notice
 * and label repeat the contract's {@code burned_area_km2} wording.
 */
public final class FireInfoTexts {

    /** {@code FireEventDetail.burned_area_label} (contract const). */
    public static final String BURNED_AREA_LABEL = "위성 확인 연소 면적(하한)";
    /** {@code FireEventList.notice} — the burned area lower-bound note (contract {@code listFireEvents}). */
    public static final String EVENTS_NOTICE = "burned_area_km2 = 위성 확인 연소 면적(하한, MCD64A1) — 공식 면적보다 작을 수 있음. "
            + "null = 자료 없음, 0 = 확인 안 됨, 양수 = 확인.";
    /** {@code FwiResponse.notice} (contract example {@code arg_first_day}). */
    public static final String FWI_NOTICE = "기상으로 본 산불 위험도(캐나다 FWI, NASA POWER 일 자료 근사). 실제 발화·확산을 예측하지 않음.";
    /** {@code SpreadNowcast.notice} (contract example {@code arg_top}). */
    public static final String SPREAD_NOTICE = "위성 감지 기반 단기 확산 가능성입니다. 물리 시뮬레이션이 아니며, 오늘 감지 지점에서 반경 약 7.5 km "
            + "밖으로 번지는 경우와 새로 발생하는 불은 예측 대상이 아닙니다.";
    /** {@code FwiResponse.badge} (contract const). */
    public static final String FWI_BADGE = "fwi_rel_class";

    private FireInfoTexts() {
    }
}
