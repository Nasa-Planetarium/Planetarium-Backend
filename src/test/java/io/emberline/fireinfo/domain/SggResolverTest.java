package io.emberline.fireinfo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P2-1 (unit): the MAPPING §8.4 rule on a small SGG list shaped like aoi_list — a unique name resolves
 * whatever the event's sido_nm (border crossing), a homonym only through the parent SIDO, anything else is dropped and
 * counted. The fixture-wide check is FireInfoApiTest.p2_1.
 */
class SggResolverTest {

    static final List<SggResolver.Sgg> SGGS = List.of(
            new SggResolver.Sgg("SGG_42820", "고성군", "강원특별자치도"),
            new SggResolver.Sgg("SGG_48820", "고성군", "경상남도"),
            new SggResolver.Sgg("SGG_47170", "안동시", "경상북도"),
            new SggResolver.Sgg("SGG_47730", "의성군", "경상북도"),
            new SggResolver.Sgg("SGG_43745", "증평군", "충청북도"));
    final SggResolver resolver = new SggResolver(SGGS);

    @Test
    void uniqueNameResolvesEvenAcrossASidoBorder() {
        assertThat(resolver.resolve("경상북도", "안동시,의성군").sggIds()).containsExactly("SGG_47170", "SGG_47730");
        // the event's sido_nm names another SIDO: the unique name still resolves to its own SGG
        SggResolver.Result crossing = resolver.resolve("충청북도", "증평군,안동시");
        assertThat(crossing.sggIds()).containsExactly("SGG_43745", "SGG_47170");
        assertThat(crossing.dropped()).isZero();
    }

    @Test
    void homonymResolvesOnlyThroughTheParentSido() {
        assertThat(resolver.homonyms()).containsExactly("고성군");
        assertThat(resolver.resolve("경상남도", "고성군").sggIds()).containsExactly("SGG_48820");
        assertThat(resolver.resolve("강원특별자치도", "고성군").sggIds()).containsExactly("SGG_42820");
        SggResolver.Result elsewhere = resolver.resolve("경상북도", "고성군");
        assertThat(elsewhere.sggIds()).isEmpty();
        assertThat(elsewhere.dropped()).isEqualTo(1);
        SggResolver.Result noSido = resolver.resolve(null, "고성군");
        assertThat(noSido.sggIds()).isEmpty();
        assertThat(noSido.dropped()).isEqualTo(1);
    }

    @Test
    void unknownNameIsDroppedAndCountedOncePerName() {
        SggResolver.Result r = resolver.resolve("경상북도", "안동시, 없는군 ,없는군,,안동시");
        assertThat(r.sggIds()).containsExactly("SGG_47170");
        assertThat(r.dropped()).isEqualTo(1);
        assertThat(resolver.resolve("경상북도", null).sggIds()).isEmpty();
    }
}
