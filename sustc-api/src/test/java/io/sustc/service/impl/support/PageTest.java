package io.sustc.service.impl.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PageTest {

    @Test
    void pagesAreNumberedFromOne() {
        assertThat(Page.of(10, 1))
                .hasValueSatisfying(p -> assertThat(p.offset()).isZero());
        assertThat(Page.of(10, 3))
                .hasValueSatisfying(p -> assertThat(p.offset()).isEqualTo(20));
    }

    @Test
    void nonPositiveSizesAndNumbersAreInvalid() {
        assertThat(Page.of(0, 1)).isEmpty();
        assertThat(Page.of(1, 0)).isEmpty();
        assertThat(Page.of(-5, 2)).isEmpty();
        assertThat(Page.of(5, -2)).isEmpty();
    }

    @Test
    void theOffsetDoesNotOverflow() {
        assertThat(Page.of(Integer.MAX_VALUE, Integer.MAX_VALUE)).hasValueSatisfying(p -> assertThat(p.offset())
                .isEqualTo((long) Integer.MAX_VALUE * (Integer.MAX_VALUE - 1)));
    }

    @Test
    void emptyStringsCountAsAbsent() {
        assertThat(Strings.isNullOrEmpty(null)).isTrue();
        assertThat(Strings.isNullOrEmpty("")).isTrue();
        assertThat(Strings.isNullOrEmpty(" ")).isFalse();
        assertThat(Strings.emptyToNull("")).isNull();
        assertThat(Strings.emptyToNull("x")).isEqualTo("x");
    }
}
