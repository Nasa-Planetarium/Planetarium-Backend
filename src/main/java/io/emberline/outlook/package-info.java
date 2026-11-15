/**
 * Bounded context <b>outlook</b>: the model team's next-month occurrence probability — the 0.5° world grid
 * (grid_occurrence_nextmonth_latest) and the Korean municipalities (aoi_occurrence_nextmonth_latest), served as the
 * files have them; and the FireOutlook Planner 30-day outlook, {@code POST /forecast}, passed through from the
 * model team's forecast service. Other modules may only use its {@code api} package (it has none: nothing depends on outlook).
 */
@org.springframework.modulith.ApplicationModule(displayName = "outlook")
package io.emberline.outlook;
