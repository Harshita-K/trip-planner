package com.wanderly.travel;

import com.wanderly.common.GeoPoint;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Builds the commute options between two places (F8). Pure and deterministic: no I/O, same inputs
 * give the same plan, so it's unit-tested directly.
 *
 * <p>Distances are real (road distance from OSRM, great-circle for flights, rail ~ road x 1.05).
 * <b>Fares and timetables are simulated</b>, as the design doc planned: real Indian rail, bus and
 * airline fare feeds are paid partner APIs. The formulas are calibrated to typical published fares:
 * <ul>
 *   <li>Train: Sleeper ~0.45/km, AC 3-tier ~1.25/km, AC 2-tier ~1.80/km (+ base), avg 58 km/h; +30% within
 *       2 days (Tatkal-style quota). Not offered for hill/island destinations without a railhead.</li>
 *   <li>Bus: non-AC ~1.0/km, AC sleeper ~1.6/km, road time x 1.3 for stops; under 1,200 km.</li>
 *   <li>Flight: 1,500 + 4.2/km, x1.9 within 2 days down to x1.0 three weeks out, +12% on Fri/Sun;
 *       offered only over 300 km with an airport within 120 km at both ends.</li>
 *   <li>Car: fuel ~6.5/km + tolls ~1.2/km on long trips, per vehicle (4 seats).</li>
 * </ul>
 * Door-to-door times include airport/station access and buffers, so the modes compare fairly.
 */
public final class TravelPlanner {

    /** Destinations with no practical railhead (hill stations, islands, the north-east hills). */
    static final Set<String> NO_RAIL = Set.of("leh", "port blair", "gangtok", "manali", "munnar", "kohima", "kasauli",
            "mussoorie", "nainital", "kodaikanal", "madikeri", "shillong", "imphal", "aizawl", "darjeeling", "ooty");

    private TravelPlanner() {
    }

    public record Place(String name, GeoPoint point, Optional<Airport> airport) {
    }

    public static TravelDtos.TravelPlan plan(Place from, Place to, LocalDate date, LocalDate today, int travellers,
                                             RoadRouter.Route road) {
        long daysAhead = ChronoUnit.DAYS.between(today, date);
        double straightKm = from.point().distanceKm(to.point());
        Random rnd = new Random((from.name() + "|" + to.name() + "|" + date).toLowerCase(Locale.ROOT).hashCode());

        List<Draft> drafts = new ArrayList<>();
        flight(from, to, straightKm, date, daysAhead, rnd).ifPresent(drafts::add);
        if (railAvailable(from, to, road.km())) {
            drafts.add(train(road.km(), daysAhead, rnd));
        }
        if (road.km() >= 40 && road.km() <= 1200) {
            drafts.add(bus(road, date, rnd));
        }
        if (road.km() <= 2500) {
            drafts.add(car(road, travellers));
        }

        // Badges compare per-person cost (car split across its occupants), time and emissions.
        Draft cheapest = drafts.stream().min(Comparator.comparingDouble(d -> d.perPersonCost(travellers))).orElse(null);
        Draft fastest = drafts.stream().min(Comparator.comparingInt(d -> d.doorToDoor)).orElse(null);
        Draft greenest = drafts.stream().min(Comparator.comparingDouble(d -> d.co2PerPerson(travellers))).orElse(null);

        List<TravelDtos.Option> options = new ArrayList<>();
        for (Draft d : drafts) {
            List<String> badges = new ArrayList<>();
            if (d == fastest) badges.add("Fastest");
            if (d == cheapest) badges.add("Cheapest");
            if (d == greenest) badges.add("Greenest");
            int total = d.mode.equals("car") ? d.fromPrice * (int) Math.ceil(travellers / 4.0) : d.fromPrice * travellers;
            options.add(new TravelDtos.Option(d.mode, d.title, d.route, round1(d.km), d.doorToDoor, d.fromPrice, total,
                    round1(d.co2PerPerson(travellers)), badges, d.notes, d.departures));
        }
        options.sort(Comparator.comparingInt(TravelDtos.Option::doorToDoorMinutes));
        return new TravelDtos.TravelPlan(from.name(), to.name(), date.toString(), travellers, options, road.estimated(),
                "Distances are real; fares and timetables are indicative estimates, not live availability.");
    }

    private static Optional<Draft> flight(Place from, Place to, double straightKm, LocalDate date, long daysAhead, Random rnd) {
        if (straightKm < 300 || from.airport().isEmpty() || to.airport().isEmpty()) {
            return Optional.empty();
        }
        Airport a = from.airport().get();
        Airport b = to.airport().get();
        if (a.iata().equals(b.iata())) {
            return Optional.empty();
        }
        double airKm = a.point().distanceKm(b.point());
        int airMinutes = (int) Math.round(airKm / 700.0 * 60 + 25);
        int access = driveMinutes(from.point().distanceKm(a.point())) + driveMinutes(to.point().distanceKm(b.point()));
        int doorToDoor = access + 105 + airMinutes + 30;   // 1h45 before departure, 30 min to exit

        double demand = daysAhead <= 2 ? 1.9 : daysAhead <= 6 ? 1.45 : daysAhead <= 20 ? 1.15 : 1.0;
        DayOfWeek dow = date.getDayOfWeek();
        double weekend = dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SUNDAY ? 1.12 : 1.0;
        double base = (1500 + airKm * 4.2) * demand * weekend;

        int count = a.isMajor() && b.isMajor() ? 4 : 2;
        List<TravelDtos.Departure> deps = new ArrayList<>();
        for (LocalTime t : times(rnd, count, 6, 21)) {
            int price = round10(base * (0.9 + rnd.nextDouble() * 0.25));
            deps.add(departure("Flight " + a.iata() + "-" + b.iata(), t, airMinutes,
                    List.of(new TravelDtos.Fare("Economy", price), new TravelDtos.Fare("Business", round10(price * 3.2)))));
        }
        Draft d = new Draft("flight", "Fly " + a.iata() + " → " + b.iata(), a.city() + " (" + a.iata() + ") → " + b.city() + " (" + b.iata() + ")",
                airKm, doorToDoor, minFare(deps), 0.13 * airKm, deps);
        d.notes.add("Includes ~" + access + " min to and from the airports and a 1h45 check-in buffer");
        if (demand > 1.0) {
            d.notes.add("Fares rise close to the date; booking 3+ weeks ahead is usually cheapest");
        }
        return Optional.of(d);
    }

    private static boolean railAvailable(Place from, Place to, double roadKm) {
        return roadKm >= 60 && roadKm <= 2800
                && !NO_RAIL.contains(from.name().toLowerCase(Locale.ROOT))
                && !NO_RAIL.contains(to.name().toLowerCase(Locale.ROOT));
    }

    private static Draft train(double roadKm, long daysAhead, Random rnd) {
        double railKm = roadKm * 1.05;
        int minutes = (int) Math.round(railKm / 58.0 * 60 + 15);
        double tatkal = daysAhead <= 2 ? 1.3 : 1.0;
        List<TravelDtos.Departure> deps = new ArrayList<>();
        String[] names = {"Morning Express", "Superfast", "Overnight Mail"};
        List<LocalTime> slots = times(rnd, 3, 5, 23);
        for (int i = 0; i < slots.size(); i++) {
            int jitter = (int) (minutes * (rnd.nextDouble() * 0.15 - 0.05));
            deps.add(departure(names[i], slots.get(i), minutes + jitter, List.of(
                    new TravelDtos.Fare("Sleeper", round10(Math.max(150, (40 + 0.45 * railKm) * tatkal))),
                    new TravelDtos.Fare("AC 3-tier", round10(Math.max(500, (60 + 1.25 * railKm) * tatkal))),
                    new TravelDtos.Fare("AC 2-tier", round10(Math.max(750, (60 + 1.80 * railKm) * tatkal))))));
        }
        Draft d = new Draft("train", "Train", "Rail via the nearest stations", railKm, minutes + 60, minFare(deps), 0.03 * railKm, deps);
        d.notes.add("Includes ~1 h to reach stations and board");
        if (tatkal > 1) {
            d.notes.add("Within 2 days: Tatkal-style quota prices");
        }
        return d;
    }

    private static Draft bus(RoadRouter.Route road, LocalDate date, Random rnd) {
        int minutes = (int) Math.round(road.hours() * 60 * 1.3 + 30);
        DayOfWeek dow = date.getDayOfWeek();
        double weekend = dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY ? 1.1 : 1.0;
        List<TravelDtos.Departure> deps = new ArrayList<>();
        String[] names = {"Day coach", "Evening coach", "Night sleeper"};
        LocalTime[] slots = {LocalTime.of(7 + rnd.nextInt(3), rnd.nextInt(4) * 15),
                LocalTime.of(16 + rnd.nextInt(3), rnd.nextInt(4) * 15), LocalTime.of(21 + rnd.nextInt(2), rnd.nextInt(4) * 15)};
        for (int i = 0; i < 3; i++) {
            deps.add(departure(names[i], slots[i], minutes, List.of(
                    new TravelDtos.Fare("Non-AC seater", round10(Math.max(200, 1.0 * road.km() * weekend))),
                    new TravelDtos.Fare("AC sleeper", round10(Math.max(350, 1.6 * road.km() * weekend))))));
        }
        Draft d = new Draft("bus", "Bus", "Road coach, " + Math.round(road.km()) + " km", road.km(), minutes + 30,
                minFare(deps), 0.06 * road.km(), deps);
        d.notes.add("Includes stops along the way and ~30 min to reach the bus stand");
        return d;
    }

    private static Draft car(RoadRouter.Route road, int travellers) {
        int minutes = (int) Math.round(road.hours() * 60 * 1.1);   // + breaks
        int fuelAndTolls = round10(road.km() * 6.5 + (road.km() > 100 ? road.km() * 1.2 : 0));
        int cab = round10(Math.max(1500, road.km() * 13));
        Draft d = new Draft("car", "Drive", Math.round(road.km()) + " km by road", road.km(), minutes, fuelAndTolls,
                0.17 * road.km(), List.of());
        d.notes.add("Price is fuel + tolls per car (seats 4); a one-way cab costs about ₹" + String.format(Locale.ROOT, "%,d", cab));
        d.notes.add("Leave any time; includes short breaks");
        return d;
    }

    private static final class Draft {
        final String mode;
        final String title;
        final String route;
        final double km;
        final int doorToDoor;
        final int fromPrice;
        final double co2PerVehicleOrPerson;
        final List<TravelDtos.Departure> departures;
        final List<String> notes = new ArrayList<>();

        Draft(String mode, String title, String route, double km, int doorToDoor, int fromPrice, double co2,
              List<TravelDtos.Departure> departures) {
            this.mode = mode;
            this.title = title;
            this.route = route;
            this.km = km;
            this.doorToDoor = doorToDoor;
            this.fromPrice = fromPrice;
            this.co2PerVehicleOrPerson = co2;
            this.departures = departures;
        }

        double perPersonCost(int travellers) {
            return mode.equals("car") ? fromPrice * Math.ceil(travellers / 4.0) / travellers : fromPrice;
        }

        double co2PerPerson(int travellers) {
            return mode.equals("car") ? co2PerVehicleOrPerson / Math.min(4, travellers) : co2PerVehicleOrPerson;
        }
    }

    private static List<LocalTime> times(Random rnd, int count, int firstHour, int lastHour) {
        List<LocalTime> times = new ArrayList<>();
        double span = (lastHour - firstHour) / (double) count;
        for (int i = 0; i < count; i++) {
            int hour = firstHour + (int) (span * i + rnd.nextDouble() * span * 0.8);
            times.add(LocalTime.of(Math.min(hour, 23), rnd.nextInt(12) * 5));
        }
        return times;
    }

    private static TravelDtos.Departure departure(String label, LocalTime depart, int minutes, List<TravelDtos.Fare> fares) {
        LocalTime arrive = depart.plusMinutes(minutes);
        long days = (depart.toSecondOfDay() / 60 + minutes) / (24 * 60);
        String arrival = arrive.toString() + (days > 0 ? " (+" + days + ")" : "");
        return new TravelDtos.Departure(label, depart.toString(), arrival, minutes, fares);
    }

    private static int minFare(List<TravelDtos.Departure> deps) {
        return deps.stream().flatMap(d -> d.fares().stream()).mapToInt(TravelDtos.Fare::price).min().orElse(0);
    }

    private static int driveMinutes(double straightKm) {
        return (int) Math.round(straightKm * 1.3 / 35.0 * 60 + 10);
    }

    private static int round10(double v) {
        return (int) (Math.round(v / 10.0) * 10);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
