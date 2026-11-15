package io.emberline.outlook.application.service;

/**
 * Fixed texts of the outlook bodies, as the contract examples show them (getGridOccurrence {@code arg_tip},
 * getAoiOccurrence {@code sgg_industrial}). The {@code data_release} tag is {@code ReleaseRef.dataRelease()}.
 */
final class OccurrenceTexts {

    /** Grid layer and grid summaries (the grey cells are persistent-source cells). */
    static final String GRID_NOTICE = "과거 감지 기록과 최근 활동으로 본 확률입니다. 날씨 예보가 아닙니다. 회색 격자는 공장·가스 플레어 같은 지속 열원 의심 지역입니다.";
    /** Municipality values (the file rows, single or listed). */
    static final String AOI_NOTICE = "과거 감지 기록과 최근 활동으로 본 확률입니다. 날씨 예보가 아니며, 공장 등 산업 열원으로 보이는 감지가 일부 섞여 있을 수 있습니다.";

    private OccurrenceTexts() {
    }
}
