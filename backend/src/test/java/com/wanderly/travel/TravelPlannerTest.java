package com.wanderly.travel;

import com.wanderly.common.GeoPoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TravelPlannerTest {

    private static final Airport DEL = new Airport("DEL", "Indira Gandhi Intl", "New Delhi", 28.5665, 77.1031, "large");
    private static final Airport UDR = new Airport("UDR", "Maharana Pratap", "Udaipur", 24.6177, 73.8961, "medium");
    private static final Airport IXL = new Airport("IXL", "Leh Kushok Bakula Rimpochee", "Leh", 34.1359, 77.5465, "medium");

    private static final TravelPlanner.Place DELHI = new TravelPlanner.Place("Delhi", new GeoPoint(28.6139, 77.2090), Optional.of(DEL));
    private static final TravelPlanner.Place UDAIPUR = new TravelPlanner.Place("Udaipur", new GeoPoint(24.5787, 73.6863), Optional.of(UDR));
    private static final TravelPlanner.Place AGRA = new TravelPlanner.Place("Agra", new GeoPoint(27.1767, 78.0081), Optional.empty());
    private static final TravelPlanner.Place LEH = new TravelPlanner.Place("Leh", new GeoPoint(34.1642, 77.5848), Optional.of(IXL));

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);   // a Tuesday

    private static TravelDtos.TravelPlan plan(TravelPlanner.Place from, TravelPlanner.Place to, LocalDate date, int travellers, double roadKm) {
        return TravelPlanner.plan(from, to, date, TODAY, travellers, new RoadRouter.Route(roadKm, roadKm / 55, false));
    }

    private static List<String> modes(TravelDtos.TravelPlan p) {
        return p.options().stream().map(TravelDtos.Option::mode).toList();
    }

    @Test
    void longTripOffersEveryModeWithBadges() {
        TravelDtos.TravelPlan p = plan(DELHI, UDAIPUR, TODAY.plusDays(30), 2, 660);

        assertThat(modes(p)).containsExactlyInAnyOrder("flight", "train", "bus", "car");
        assertThat(p.options().get(0).mode()).isEqualTo("flight");            // sorted by door-to-door time
        assertThat(p.options()).anySatisfy(o -> assertThat(o.badges()).contains("Fastest"));
        assertThat(p.options()).anySatisfy(o -> assertThat(o.badges()).contains("Cheapest"));
        TravelDtos.Option train = p.options().stream().filter(o -> o.mode().equals("train")).findFirst().orElseThrow();
        assertThat(train.badges()).contains("Greenest");
        assertThat(train.departures()).hasSize(3).allSatisfy(d -> assertThat(d.fares()).hasSize(3));
        assertThat(p.disclaimer()).contains("indicative");
    }

    @Test
    void shortTripHasNoFlightAndNoAirportMeansNoFlight() {
        assertThat(modes(plan(DELHI, AGRA, TODAY.plusDays(10), 1, 230))).doesNotContain("flight").contains("train", "bus", "car");
    }

    @Test
    void hillDestinationsWithoutARailheadHaveNoTrain() {
        assertThat(modes(plan(DELHI, LEH, TODAY.plusDays(10), 1, 1000))).contains("flight").doesNotContain("train");
    }

    @Test
    void flightsCostMoreCloseToTheDate() {
        int lastMinute = flight(plan(DELHI, UDAIPUR, TODAY.plusDays(1), 1, 660)).fromPrice();
        int planned = flight(plan(DELHI, UDAIPUR, TODAY.plusDays(40), 1, 660)).fromPrice();
        assertThat(lastMinute).isGreaterThan((int) (planned * 1.4));
    }

    @Test
    void carIsPricedPerVehicleAndTotalsScaleWithTravellers() {
        TravelDtos.TravelPlan p = plan(DELHI, AGRA, TODAY.plusDays(10), 6, 230);
        TravelDtos.Option car = p.options().stream().filter(o -> o.mode().equals("car")).findFirst().orElseThrow();
        TravelDtos.Option train = p.options().stream().filter(o -> o.mode().equals("train")).findFirst().orElseThrow();
        assertThat(car.total()).isEqualTo(car.fromPrice() * 2);       // 6 people -> 2 cars
        assertThat(train.total()).isEqualTo(train.fromPrice() * 6);
    }

    @Test
    void sameInputsGiveTheSamePlan() {
        assertThat(plan(DELHI, UDAIPUR, TODAY.plusDays(12), 2, 660)).isEqualTo(plan(DELHI, UDAIPUR, TODAY.plusDays(12), 2, 660));
    }

    private static TravelDtos.Option flight(TravelDtos.TravelPlan p) {
        return p.options().stream().filter(o -> o.mode().equals("flight")).findFirst().orElseThrow();
    }
}
