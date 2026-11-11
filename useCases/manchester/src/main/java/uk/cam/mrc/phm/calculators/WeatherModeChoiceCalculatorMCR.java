package uk.cam.mrc.phm.calculators;

import de.tum.bgu.msm.data.*;
import de.tum.bgu.msm.data.travelTimes.TravelTimes;
import de.tum.bgu.msm.modules.modeChoice.ModeChoiceCalculator;
import org.matsim.core.utils.collections.Tuple;
import uk.cam.mrc.phm.modules.WeatherModeShifts;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;

/**
 * Adds the weather of the trip's day to the utilities of another mode choice calculator (the
 * mode-shift channel, see {@link WeatherModeShifts}). Wraps the calibrated calculator, so the
 * shift comes on top of the calibrated constants. Only the trip-aware call that
 * {@link de.tum.bgu.msm.modules.modeChoice.ModeChoice} makes carries a day; the other methods
 * are passed through unchanged.
 */
public final class WeatherModeChoiceCalculatorMCR implements ModeChoiceCalculator {

    private final ModeChoiceCalculator base;
    private final WeatherModeShifts shifts;

    public WeatherModeChoiceCalculatorMCR(ModeChoiceCalculator base, WeatherModeShifts shifts) {
        this.base = base;
        this.shifts = shifts;
    }

    @Override
    public EnumMap<Mode, Double> calculateUtilities(MitoTrip trip, Purpose purpose, MitoHousehold household,
                                                    MitoPerson person, MitoZone originZone, MitoZone destinationZone,
                                                    TravelTimes travelTimes, double travelDistanceAuto,
                                                    double travelDistanceNMT, double peakHour_s) {
        EnumMap<Mode, Double> utilities = base.calculateUtilities(purpose, household, person, originZone,
                destinationZone, travelTimes, travelDistanceAuto, travelDistanceNMT, peakHour_s);
        if (utilities != null && trip instanceof MitoTrip7days) {
            Day day = ((MitoTrip7days) trip).getDepartureDay();
            utilities.replaceAll((mode, utility) -> utility + shifts.getShift(day, mode));
        }
        return utilities;
    }

    @Override
    public EnumMap<Mode, Double> calculateUtilities(Purpose purpose, MitoHousehold household, MitoPerson person,
                                                    MitoZone originZone, MitoZone destinationZone,
                                                    TravelTimes travelTimes, double travelDistanceAuto,
                                                    double travelDistanceNMT, double peakHour_s) {
        return base.calculateUtilities(purpose, household, person, originZone, destinationZone, travelTimes,
                travelDistanceAuto, travelDistanceNMT, peakHour_s);
    }

    @Override
    public EnumMap<Mode, Double> calculateProbabilities(Purpose purpose, MitoHousehold household, MitoPerson person,
                                                        MitoZone originZone, MitoZone destinationZone,
                                                        TravelTimes travelTimes, double travelDistanceAuto,
                                                        double travelDistanceNMT, double peakHour_s) {
        return base.calculateProbabilities(purpose, household, person, originZone, destinationZone, travelTimes,
                travelDistanceAuto, travelDistanceNMT, peakHour_s);
    }

    @Override
    public EnumMap<Mode, Double> calculateGeneralizedCosts(Purpose purpose, MitoHousehold household, MitoPerson person,
                                                           MitoZone originZone, MitoZone destinationZone,
                                                           TravelTimes travelTimes, double travelDistanceAuto,
                                                           double travelDistanceNMT, double peakHour_s) {
        return base.calculateGeneralizedCosts(purpose, household, person, originZone, destinationZone, travelTimes,
                travelDistanceAuto, travelDistanceNMT, peakHour_s);
    }

    @Override
    public List<Tuple<EnumSet<Mode>, Double>> getNests() {
        return base.getNests();
    }

    @Override
    public EnumSet<Mode> getChoiceSet() {
        return base.getChoiceSet();
    }
}
