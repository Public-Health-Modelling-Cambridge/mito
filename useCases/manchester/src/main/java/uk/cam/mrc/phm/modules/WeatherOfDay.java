package uk.cam.mrc.phm.modules;

import de.tum.bgu.msm.data.Day;
import de.tum.bgu.msm.resources.Resources;

import java.util.EnumMap;

/**
 * The weather of each simulated day, read from the MITO properties file. Shared by the two
 * weather responses, {@link WeatherTripAdjustment} (trip cancellation) and
 * {@link uk.cam.mrc.phm.calculators.WeatherModeChoiceCalculatorMCR} (mode shift), so that both
 * see the same day.
 * <pre>
 * weather.rain.mm.thursday  = 15      # daily rainfall total, mm
 * weather.tasmax.c.thursday = 8       # daily maximum temperature, degC
 * weather.rain.mm           = 0       # optional default for days not listed
 * weather.tasmax.c          = 15      # optional default for days not listed
 * </pre>
 * A variable that is not given for a day (and has no default) is null: that day is not
 * adjusted for it.
 */
public final class WeatherOfDay {

    public static final String RAIN_MM = "weather.rain.mm";
    public static final String TASMAX_C = "weather.tasmax.c";

    private final EnumMap<Day, Double> rainMm = new EnumMap<>(Day.class);
    private final EnumMap<Day, Double> tasmaxC = new EnumMap<>(Day.class);

    public WeatherOfDay() {
        for (Day day : Day.values()) {
            Double rain = read(RAIN_MM, day);
            Double tasmax = read(TASMAX_C, day);
            if (rain != null) {
                rainMm.put(day, rain);
            }
            if (tasmax != null) {
                tasmaxC.put(day, tasmax);
            }
        }
    }

    /** Daily rainfall total in mm, or null if not given for this day. */
    public Double getRainMm(Day day) {
        return day == null ? null : rainMm.get(day);
    }

    /** Daily maximum temperature in degC, or null if not given for this day. */
    public Double getTasmaxC(Day day) {
        return day == null ? null : tasmaxC.get(day);
    }

    /** True if at least one weather variable is given for this day. */
    public boolean hasWeather(Day day) {
        return getRainMm(day) != null || getTasmaxC(day) != null;
    }

    /** True if no weather is given for any day. */
    public boolean isEmpty() {
        return rainMm.isEmpty() && tasmaxC.isEmpty();
    }

    private static Double read(String key, Day day) {
        String value = Resources.instance.getString(key + "." + day);
        if (value == null) {
            value = Resources.instance.getString(key);
        }
        return value == null || value.trim().isEmpty() ? null : Double.parseDouble(value.trim());
    }
}
