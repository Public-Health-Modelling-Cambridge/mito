package uk.cam.mrc.phm.modules;

import de.tum.bgu.msm.data.Day;
import de.tum.bgu.msm.data.Mode;
import de.tum.bgu.msm.resources.Resources;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Weather channel 2 of 2: the change in mode utilities on each simulated day.
 *
 * The input is {@code weatherModeUtilityShifts.csv} from the extremeWeather project
 * (analysis/mito/weather_tripgen_sensitivities.ipynb). Per weather bin it gives, for walk, bike
 * and public transport, the change in the log-odds of choosing that mode against car, i.e. the
 * weather effect on the mode's trips minus the effect on car trips. In a multinomial logit, as
 * the Manchester mode choice is, adding this shift to the mode's utility reproduces that change
 * in the odds. Car modes (and any mode without a column) are the reference and get 0.
 * <pre>
 * hazard,bin,lower,upper,walk,bike,pt
 * rain,light,0.1,2.5,-0.012,-0.056,-0.003      # applies when lower <= rain mm < upper
 * temp,freezing,-Infinity,0.0,0.149,0.069,0.035 # applies when lower <= tasmax C < upper
 * </pre>
 * The shifts of the rain bin and of the temperature bin of a day are added. Days without
 * weather (see {@link WeatherOfDay}) and baseline bins (dry, mild: absent from the file) get no
 * shift, so the calibrated mode constants are unchanged on a reference day.
 *
 * Properties:
 * <pre>
 * weather.modechoice.run    = true
 * weather.modechoice.shifts = input/mito/modeChoice/weatherModeUtilityShifts.csv
 * </pre>
 */
public final class WeatherModeShifts {

    private static final Logger logger = LogManager.getLogger(WeatherModeShifts.class);

    public static final String RUN = "weather.modechoice.run";
    public static final String SHIFTS = "weather.modechoice.shifts";

    private static final String RAIN = "rain";
    private static final String TEMP = "temp";

    /** Coefficient-file column -> MITO modes it applies to. */
    private static final Map<String, EnumSet<Mode>> MODES_BY_COLUMN = new LinkedHashMap<>();
    static {
        MODES_BY_COLUMN.put("walk", EnumSet.of(Mode.walk));
        MODES_BY_COLUMN.put("bike", EnumSet.of(Mode.bicycle));
        MODES_BY_COLUMN.put("pt", EnumSet.of(Mode.pt, Mode.bus, Mode.train, Mode.tramOrMetro));
    }

    private final EnumMap<Day, EnumMap<Mode, Double>> shiftByDay = new EnumMap<>(Day.class);

    public WeatherModeShifts(WeatherOfDay weather) {
        Path path = Resources.instance.getRelativePath(SHIFTS);
        List<String[]> rows = new ArrayList<>();
        List<String> header;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.ISO_8859_1)) {
            header = Arrays.asList(reader.readLine().split(","));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    rows.add(line.split(","));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read weather mode utility shifts from " + path, e);
        }
        int hazardIndex = WeatherTripAdjustment.requireColumn(header, "hazard", path);
        int binIndex = WeatherTripAdjustment.requireColumn(header, "bin", path);
        int lowerIndex = WeatherTripAdjustment.requireColumn(header, "lower", path);
        int upperIndex = WeatherTripAdjustment.requireColumn(header, "upper", path);
        Map<Integer, EnumSet<Mode>> modesByIndex = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String column = header.get(i);
            if (i == hazardIndex || i == binIndex || i == lowerIndex || i == upperIndex) {
                continue;
            }
            EnumSet<Mode> modes = MODES_BY_COLUMN.get(column);
            if (modes == null) {
                throw new RuntimeException("Unknown mode column '" + column + "' in " + path
                        + ". Expected some of " + MODES_BY_COLUMN.keySet() + " (car is the reference).");
            }
            modesByIndex.put(i, modes);
        }

        for (Day day : Day.values()) {
            if (!weather.hasWeather(day)) {
                continue;
            }
            EnumMap<Mode, Double> shift = new EnumMap<>(Mode.class);
            List<String> bins = new ArrayList<>();
            for (String[] row : rows) {
                String hazard = row[hazardIndex];
                Double value;
                if (RAIN.equals(hazard)) {
                    value = weather.getRainMm(day);
                } else if (TEMP.equals(hazard)) {
                    value = weather.getTasmaxC(day);
                } else {
                    throw new RuntimeException("Unknown hazard '" + hazard + "' in " + path);
                }
                double lower = Double.parseDouble(row[lowerIndex]);
                double upper = Double.parseDouble(row[upperIndex]);
                if (value == null || value < lower || value >= upper) {
                    continue;
                }
                bins.add(hazard + " " + row[binIndex]);
                for (Map.Entry<Integer, EnumSet<Mode>> entry : modesByIndex.entrySet()) {
                    double coefficient = Double.parseDouble(row[entry.getKey()]);
                    for (Mode mode : entry.getValue()) {
                        shift.merge(mode, coefficient, Double::sum);
                    }
                }
            }
            if (!shift.isEmpty()) {
                shiftByDay.put(day, shift);
            }
            logger.info("Weather mode shifts on " + day + " " + bins + ": " + shift);
        }
    }

    /** Utility shift of a mode on a day; 0 for car modes, baseline weather or days without weather. */
    public double getShift(Day day, Mode mode) {
        if (day == null) {
            return 0.;
        }
        EnumMap<Mode, Double> shift = shiftByDay.get(day);
        return shift == null ? 0. : shift.getOrDefault(mode, 0.);
    }

    /** True if at least one day has a non-zero shift. */
    public boolean isActive() {
        return !shiftByDay.isEmpty();
    }
}
