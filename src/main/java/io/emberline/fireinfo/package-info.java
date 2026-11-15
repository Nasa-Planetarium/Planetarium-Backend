/**
 * Bounded context <b>fireinfo</b>: the v1.2 fire-info layers of the model release — fire events, FWI, the spread
 * nowcast and the fire regime badge — loaded per release into its own schema. Other modules may only use its
 * {@code api} package.
 */
@org.springframework.modulith.ApplicationModule(displayName = "fireinfo")
package io.emberline.fireinfo;
