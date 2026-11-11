package uk.cam.mrc.phm.modules;

import de.tum.bgu.msm.data.*;
import de.tum.bgu.msm.modules.Module;
import de.tum.bgu.msm.resources.Resources;
import de.tum.bgu.msm.util.MitoUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Weather channel 1 of 2: trip cancellation (and, for some groups, extra trips).
 *
 * MITO generates trips per person and week. The weather acts on single days, so this module
 * runs right after the day-of-week choice, once every trip has its day, and before trip
 * distribution and mode choice. For every trip it computes a trip-rate multiplier from the
 * weather of the trip's day, the trip purpose and the traveller (gender, age group, occupation,
 * household car ownership, urban/rural home, weekday/weekend), and then
 * <ul>
 *     <li>keeps the trip with probability {@code multiplier} if the multiplier is below 1
 *     (binomial thinning, equivalent to scaling the mean of the Poisson / negative binomial
 *     count the trip was drawn from);</li>
 *     <li>duplicates the trip with probability {@code multiplier - 1} if it is above 1.</li>
 * </ul>
 * Because the coefficients come from a daily model with person fixed effects, thinning the
 * trips of one day reproduces the daily model exactly: no weekly weather aggregate is needed.
 *
 * The multipliers are by purpose, not by mode. The change in the mode mix is the second
 * channel, handled in mode choice by
 * {@link uk.cam.mrc.phm.calculators.WeatherModeChoiceCalculatorMCR}. Trips by mode = all trips
 * x mode share, so the two channels together reproduce the by-mode response of the NTS without
 * counting it twice. The coefficient file must therefore only have {@code mode = all} rows
 * ({@code weatherTripGenCoefficients_purposeOnly.csv}); a file with mode-specific rows is
 * rejected.
 *
 * The multiplier is {@code exp(sum of applicable coefficients)}. Coefficients come from the
 * conditional fixed-effects Poisson models estimated on the National Travel Survey in the
 * extremeWeather project (analysis/mito/weather_tripgen_sensitivities.ipynb). There are two
 * kinds of rows:
 * <ul>
 *     <li>{@code bin}: a step that applies when {@code lower <= value < upper}, where value is
 *     the daily rainfall in mm (hazard {@code rain}) or the daily maximum temperature in degC
 *     (hazard {@code temp});</li>
 *     <li>{@code slope}: a coefficient multiplied by a continuous severity (hazards
 *     {@code rain}, {@code cold}, {@code heat}, see {@link #severity}). The {@code base} row
 *     applies to everyone, the other rows to members of the named group.</li>
 * </ul>
 *
 * Properties (MITO properties file), plus the weather of each day, see {@link WeatherOfDay}:
 * <pre>
 * weather.tripgen.run          = true
 * weather.tripgen.coefficients = input/mito/tripGeneration/weatherTripGenCoefficients_purposeOnly.csv
 * </pre>
 */
public final class WeatherTripAdjustment extends Module {

    private static final Logger logger = LogManager.getLogger(WeatherTripAdjustment.class);

    public static final String RUN = "weather.tripgen.run";
    public static final String COEFFICIENTS = "weather.tripgen.coefficients";

    private static final String KIND_BIN = "bin";
    private static final String KIND_SLOPE = "slope";
    private static final String RAIN = "rain";
    private static final String TEMP = "temp";
    private static final String COLD = "cold";
    private static final String HEAT = "heat";
    private static final String BASE = "base";
    private static final String MODE_ALL = "all";

    // Effect modifiers. Definitions must match the notebook that estimates the coefficients.
    private static final String FEMALE = "female";
    private static final String AGE_0_15 = "age_0_15";
    private static final String AGE_16_29 = "age_16_29";
    private static final String AGE_50_64 = "age_50_64";
    private static final String AGE_65_PLUS = "age_65plus";
    private static final String WEEKEND = "weekend";
    private static final String NO_CAR = "nocar";
    private static final String RURAL = "rural";
    private static final String RETIRED = "occ_retired";
    private static final String NON_WORKING = "occ_nonworking";
    private static final Set<String> KNOWN_MODIFIERS = new HashSet<>(Arrays.asList(
            BASE, FEMALE, AGE_0_15, AGE_16_29, AGE_50_64, AGE_65_PLUS, WEEKEND, NO_CAR, RURAL,
            RETIRED, NON_WORKING));

    /** Upper bound on a multiplier: a trip is duplicated at most once. */
    private static final double MAX_MULTIPLIER = 2.;

    /** A step effect for one weather bin. */
    private static final class Step {
        final String hazard;
        final String name;
        final double lower;
        final double upper;
        final EnumMap<Purpose, Double> coefficients;

        Step(String hazard, String name, double lower, double upper, EnumMap<Purpose, Double> coefficients) {
            this.hazard = hazard;
            this.name = name;
            this.lower = lower;
            this.upper = upper;
            this.coefficients = coefficients;
        }
    }

    /** Slopes on one continuous severity, by modifier. */
    private static final class Slope {
        final double lower;
        final double upper;
        final Map<String, EnumMap<Purpose, Double>> coefficientsByModifier = new HashMap<>();

        Slope(double lower, double upper) {
            this.lower = lower;
            this.upper = upper;
        }
    }

    /** The weather of one simulated day, reduced to what the multiplier needs. */
    private static final class DayEffect {
        /** Sum of the applicable steps, by purpose. */
        final EnumMap<Purpose, Double> stepEffect = new EnumMap<>(Purpose.class);
        final Map<String, Double> severityByHazard = new HashMap<>();
        final List<String> description = new ArrayList<>();
    }

    private final List<Step> steps = new ArrayList<>();
    private final Map<String, Slope> slopeByHazard = new LinkedHashMap<>();
    private final EnumMap<Day, DayEffect> effectByDay = new EnumMap<>(Day.class);
    private final MitoTripFactory tripFactory = new MitoTripFactory7days();

    public WeatherTripAdjustment(DataSet dataSet, List<Purpose> purposes, WeatherOfDay weather) {
        super(dataSet, purposes);
        readCoefficients(Resources.instance.getRelativePath(COEFFICIENTS));
        for (Day day : Day.values()) {
            if (!weather.hasWeather(day)) {
                continue;
            }
            DayEffect effect = new DayEffect();
            Double rain = weather.getRainMm(day);
            Double tasmax = weather.getTasmaxC(day);
            if (rain != null) {
                addHazard(effect, RAIN, rain, "rain " + rain + " mm");
            }
            if (tasmax != null) {
                addHazard(effect, TEMP, tasmax, "maximum temperature " + tasmax + " C");
            }
            effectByDay.put(day, effect);
            logger.info("Weather on " + day + ": " + String.join(", ", effect.description));
        }
        if (effectByDay.isEmpty()) {
            logger.warn(RUN + " is set but no weather is given for any day ("
                    + WeatherOfDay.RAIN_MM + ".<day> / " + WeatherOfDay.TASMAX_C + ".<day>). Trips will not be adjusted.");
        }
    }

    @Override
    public void run() {
        // Seeded from MITO's random stream, like the trip generators. Fixed trip order and exactly
        // one draw per trip, so that two runs which differ only in the weather share their random
        // numbers: a trip lost in light rain is also lost in heavy rain.
        Random random = new Random(MitoUtil.getRandomObject().nextLong());
        List<MitoTrip> trips = new ArrayList<>(dataSet.getTrips().values());
        trips.sort(Comparator.comparingInt(MitoTrip::getId));
        int nextTripId = trips.isEmpty() ? 1 : trips.get(trips.size() - 1).getId() + 1;

        EnumMap<Day, EnumMap<Purpose, int[]>> counts = new EnumMap<>(Day.class);

        for (MitoTrip trip : trips) {
            double draw = random.nextDouble();
            if (!(trip instanceof MitoTrip7days)) {
                continue;
            }
            Day day = ((MitoTrip7days) trip).getDepartureDay();
            if (day == null || !effectByDay.containsKey(day)) {
                continue;
            }
            Purpose purpose = trip.getTripPurpose();
            double multiplier = getMultiplier(purpose, trip.getPerson(), day);

            // counts: [0] trips before, [1] removed, [2] added
            int[] count = counts.computeIfAbsent(day, d -> new EnumMap<>(Purpose.class))
                    .computeIfAbsent(purpose, p -> new int[3]);
            count[0]++;
            if (multiplier < 1.) {
                if (draw >= multiplier) {
                    removeTrip(trip);
                    count[1]++;
                }
            } else if (draw < multiplier - 1.) {
                duplicateTrip((MitoTrip7days) trip, nextTripId++);
                count[2]++;
            }
        }
        logSummary(counts);
    }

    /** Trip-rate multiplier of one trip. 1 if no weather is given for the day. */
    public double getMultiplier(Purpose purpose, MitoPerson person, Day day) {
        DayEffect effect = effectByDay.get(day);
        if (effect == null) {
            return 1.;
        }
        List<String> modifiers = new ArrayList<>(7);
        modifiers.add(BASE);
        if (MitoGender.FEMALE.equals(person.getMitoGender())) {
            modifiers.add(FEMALE);
        }
        int age = person.getAge();
        if (age <= 15) {
            modifiers.add(AGE_0_15);
        } else if (age <= 29) {
            modifiers.add(AGE_16_29);
        } else if (age >= 65) {
            modifiers.add(AGE_65_PLUS);
        } else if (age >= 50) {
            modifiers.add(AGE_50_64);
        }
        if (Day.saturday.equals(day) || Day.sunday.equals(day)) {
            modifiers.add(WEEKEND);
        }
        MitoHousehold household = person.getHousehold();
        if (household.getAutos() == 0) {
            modifiers.add(NO_CAR);
        }
        if (AreaTypes.RType.RURAL.equals(household.getHomeZone().getAreaTypeR())) {
            modifiers.add(RURAL);
        }
        // Occupation: in work is the reference; children are covered by their age group.
        MitoOccupationStatus occupation = person.getMitoOccupationStatus();
        if (MitoOccupationStatus.RETIRED.equals(occupation)) {
            modifiers.add(RETIRED);
        } else if (age >= 16 && (MitoOccupationStatus.STUDENT.equals(occupation)
                || MitoOccupationStatus.UNEMPLOYED.equals(occupation))) {
            modifiers.add(NON_WORKING);
        }

        double logMultiplier = effect.stepEffect.getOrDefault(purpose, 0.);
        for (Map.Entry<String, Double> entry : effect.severityByHazard.entrySet()) {
            Slope slope = slopeByHazard.get(entry.getKey());
            double coefficient = 0.;
            for (String modifier : modifiers) {
                EnumMap<Purpose, Double> byPurpose = slope.coefficientsByModifier.get(modifier);
                if (byPurpose != null) {
                    coefficient += byPurpose.getOrDefault(purpose, 0.);
                }
            }
            logMultiplier += entry.getValue() * coefficient;
        }
        return Math.min(Math.exp(logMultiplier), MAX_MULTIPLIER);
    }

    /**
     * Continuous severity of a hazard. {@code lower} and {@code upper} come from the coefficient
     * file, so the definitions below are the only thing shared with the estimation notebook:
     * <pre>
     * rain: ln(1 + mm), mm limited to [lower, upper]
     * cold: (upper - tasmax) / 10, tasmax floored at lower, zero at or above upper
     * heat: (tasmax - lower) / 10, tasmax capped at upper, zero at or below lower
     * </pre>
     */
    private static double severity(String hazard, double value, double lower, double upper) {
        switch (hazard) {
            case RAIN:
                return Math.log1p(Math.min(Math.max(value, lower), upper));
            case COLD:
                return Math.max(0., upper - Math.max(value, lower)) / 10.;
            case HEAT:
                return Math.max(0., Math.min(value, upper) - lower) / 10.;
            default:
                throw new RuntimeException("Unknown severity '" + hazard + "'");
        }
    }

    /** Adds the steps and severities driven by one weather variable (rain or temperature). */
    private void addHazard(DayEffect effect, String variable, double value, String label) {
        StringBuilder text = new StringBuilder(label);
        for (Step step : steps) {
            if (step.hazard.equals(variable) && value >= step.lower && value < step.upper) {
                step.coefficients.forEach((purpose, coefficient) -> effect.stepEffect.merge(purpose, coefficient, Double::sum));
                text.append(" [").append(step.name).append("]");
            }
        }
        for (Map.Entry<String, Slope> entry : slopeByHazard.entrySet()) {
            String hazard = entry.getKey();
            // the rain severity is driven by rainfall, the cold and heat severities by temperature
            if (RAIN.equals(variable) == RAIN.equals(hazard)) {
                effect.severityByHazard.put(hazard, severity(hazard, value, entry.getValue().lower, entry.getValue().upper));
            }
        }
        effect.description.add(text.toString());
    }

    private void removeTrip(MitoTrip trip) {
        MitoPerson person = trip.getPerson();
        person.removeTripFromPerson(trip);
        person.getHousehold().getTripsForPurpose(trip.getTripPurpose()).remove(trip);
        dataSet.removeTrip(trip.getId());
    }

    /** Copies a trip before distribution and mode choice: only origin, destination (if already set) and day. */
    private void duplicateTrip(MitoTrip7days trip, int newTripId) {
        MitoTrip7days copy = (MitoTrip7days) tripFactory.createTrip(newTripId, trip.getTripPurpose());
        MitoPerson person = trip.getPerson();
        copy.setPerson(person);
        copy.setTripOrigin(trip.getTripOrigin());
        copy.setTripDestination(trip.getTripDestination());
        copy.setDepartureDay(trip.getDepartureDay());
        person.addTrip(copy);
        person.getHousehold().addTripsForPurpose(trip.getTripPurpose(), copy);
        dataSet.addTrip(copy);
    }

    private void logSummary(EnumMap<Day, EnumMap<Purpose, int[]>> counts) {
        for (Map.Entry<Day, EnumMap<Purpose, int[]>> dayEntry : counts.entrySet()) {
            int before = 0;
            int after = 0;
            StringBuilder byPurpose = new StringBuilder();
            for (Map.Entry<Purpose, int[]> entry : dayEntry.getValue().entrySet()) {
                int[] count = entry.getValue();
                int purposeAfter = count[0] - count[1] + count[2];
                before += count[0];
                after += purposeAfter;
                byPurpose.append(String.format(Locale.ROOT, " %s %+.1f%%", entry.getKey(),
                        100. * (purposeAfter - count[0]) / count[0]));
            }
            logger.info(String.format(Locale.ROOT, "Weather adjustment %s: %,d -> %,d trips (%+.1f%%).%s",
                    dayEntry.getKey(), before, after, 100. * (after - before) / before, byPurpose));
        }
    }

    /**
     * Reads the coefficient table. Columns: kind, hazard, bin, lower, upper, mode, modifier, then
     * one column per purpose. Purposes without a column are not adjusted.
     */
    private void readCoefficients(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.ISO_8859_1)) {
            List<String> header = Arrays.asList(reader.readLine().split(","));
            int kindIndex = requireColumn(header, "kind", path);
            int hazardIndex = requireColumn(header, "hazard", path);
            int binIndex = requireColumn(header, "bin", path);
            int lowerIndex = requireColumn(header, "lower", path);
            int upperIndex = requireColumn(header, "upper", path);
            int modeIndex = requireColumn(header, "mode", path);
            int modifierIndex = requireColumn(header, "modifier", path);
            Map<Integer, Purpose> purposeByColumn = new HashMap<>();
            for (Purpose purpose : Purpose.values()) {
                int index = header.indexOf(purpose.toString());
                if (index >= 0) {
                    purposeByColumn.put(index, purpose);
                }
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] record = line.split(",");
                String kind = record[kindIndex];
                String hazard = record[hazardIndex];
                String modifier = record[modifierIndex];
                double lower = Double.parseDouble(record[lowerIndex]);
                double upper = Double.parseDouble(record[upperIndex]);
                if (!MODE_ALL.equals(record[modeIndex])) {
                    throw new RuntimeException("Row with mode '" + record[modeIndex] + "' in " + path
                            + ". Trip cancellation is by purpose only (the mode shift is in mode choice):"
                            + " use the purpose-only file, weatherTripGenCoefficients_purposeOnly.csv.");
                }
                if (!KNOWN_MODIFIERS.contains(modifier)) {
                    throw new RuntimeException("Unknown modifier '" + modifier + "' in " + path
                            + ". The coefficient file and WeatherTripAdjustment are out of sync.");
                }
                EnumMap<Purpose, Double> byPurpose = new EnumMap<>(Purpose.class);
                for (Map.Entry<Integer, Purpose> entry : purposeByColumn.entrySet()) {
                    byPurpose.put(entry.getValue(), Double.parseDouble(record[entry.getKey()]));
                }
                if (KIND_BIN.equals(kind)) {
                    if (!BASE.equals(modifier) || !(RAIN.equals(hazard) || TEMP.equals(hazard))) {
                        throw new RuntimeException("Unsupported bin row (hazard '" + hazard + "', modifier '"
                                + modifier + "') in " + path);
                    }
                    steps.add(new Step(hazard, record[binIndex], lower, upper, byPurpose));
                } else if (KIND_SLOPE.equals(kind)) {
                    severity(hazard, 0., lower, upper);   // fails early on an unknown severity
                    slopeByHazard.computeIfAbsent(hazard, h -> new Slope(lower, upper))
                            .coefficientsByModifier.put(modifier, byPurpose);
                } else {
                    throw new RuntimeException("Unknown row kind '" + kind + "' in " + path);
                }
            }
            logger.info("Read weather trip generation coefficients for " + purposeByColumn.size()
                    + " purposes (" + steps.size() + " steps, " + slopeByHazard.size() + " severities) from " + path);
        } catch (IOException e) {
            throw new RuntimeException("Could not read weather trip generation coefficients from " + path, e);
        }
    }

    static int requireColumn(List<String> header, String column, Path path) {
        int index = header.indexOf(column);
        if (index < 0) {
            throw new RuntimeException("Column '" + column + "' is missing in " + path);
        }
        return index;
    }
}
