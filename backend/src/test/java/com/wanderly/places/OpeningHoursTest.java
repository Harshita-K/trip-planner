package com.wanderly.places;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpeningHoursTest {

    private static OpeningHours.Weekly parse(String raw) {
        return OpeningHours.parse(raw).orElseThrow(() -> new AssertionError("could not parse " + raw));
    }

    @Test
    void everyDaySameHours() {
        OpeningHours.Weekly w = parse("Mo-Su 08:00-23:30");
        assertThat(w.opens()).isEqualTo(LocalTime.of(8, 0));
        assertThat(w.closes()).isEqualTo(LocalTime.of(23, 30));
        assertThat(w.closedOn()).isEmpty();
    }

    @Test
    void bareTimeMeansEveryDay() {
        assertThat(parse("08:00-23:45").closedOn()).isEmpty();
    }

    @Test
    void museumClosedOnMonday() {
        OpeningHours.Weekly w = parse("Tu-Su 10:00-17:00; Mo off");
        assertThat(w.closedOn()).containsExactly(DayOfWeek.MONDAY);
        assertThat(w.opens()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    void daysNotMentionedAreClosed() {
        assertThat(parse("Mo-Fr 09:00-17:00").closedOn()).containsExactlyInAnyOrder(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    @Test
    void typicalWindowIsTheMostCommon() {
        OpeningHours.Weekly w = parse("Mo-Fr 09:00-18:00; Sa 10:00-14:00; Su off");
        assertThat(w.opens()).isEqualTo(LocalTime.of(9, 0));
        assertThat(w.closes()).isEqualTo(LocalTime.of(18, 0));
        assertThat(w.closedOn()).containsExactly(DayOfWeek.SUNDAY);
    }

    @Test
    void laterRulesOverrideEarlierOnes() {
        assertThat(parse("Mo-Su 09:00-18:00; We off").closedOn()).containsExactly(DayOfWeek.WEDNESDAY);
    }

    @Test
    void splitRangesCollapseToFirstOpenLastClose() {
        OpeningHours.Weekly w = parse("Mo-Sa 09:00-12:30,14:00-18:00");
        assertThat(w.opens()).isEqualTo(LocalTime.of(9, 0));
        assertThat(w.closes()).isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    void overnightAnd24ClampToMidnight() {
        assertThat(parse("Mo-Su 17:00-04:00").closes()).isEqualTo(LocalTime.of(23, 59));
        assertThat(parse("Mo-Su 07:00-24:00").closes()).isEqualTo(LocalTime.of(23, 59));
        assertThat(parse("18:00+").closes()).isEqualTo(LocalTime.of(23, 59));
    }

    @Test
    void wrapAroundDayRange() {
        assertThat(parse("Fr-Mo 10:00-16:00").closedOn())
                .containsExactlyInAnyOrder(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY);
    }

    @Test
    void alwaysOpen() {
        OpeningHours.Weekly w = parse("24/7");
        assertThat(w.opens()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(w.closedOn()).isEqualTo(Set.of());
    }

    @Test
    void holidayTokensAreIgnored() {
        assertThat(parse("Mo-Sa 10:00-18:00; PH off").closedOn()).containsExactly(DayOfWeek.SUNDAY);
    }

    @Test
    void unsupportedSyntaxIsRejectedNotGuessed() {
        assertThat(OpeningHours.parse("Jan-Mar 10:00-16:00")).isEmpty();
        assertThat(OpeningHours.parse("sunrise-sunset")).isEmpty();
        assertThat(OpeningHours.parse("by appointment")).isEmpty();
        assertThat(OpeningHours.parse("")).isEmpty();
        assertThat(OpeningHours.parse(null)).isEmpty();
    }
}
