package de.tum.bgu.msm.modules.modeChoice;

import de.tum.bgu.msm.data.*;
import de.tum.bgu.msm.data.travelTimes.TravelTimes;
import org.matsim.core.utils.collections.Tuple;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;

public interface ModeChoiceCalculator {

    /**
     * For the time being implementations of this interface should adhere to the following order in the result array:
     * [0] probability Auto driver
     * [1] probability Auto passenger
     * [2] probability bicyle
     * [3] probability bus
     * [4] probability train
     * [5] probability tram or metro
     * [6] probability walk
     */
    default List<Tuple<EnumSet<Mode>, Double>> getNests() {
        return null;
    }

    default EnumSet<Mode> getChoiceSet() {
        return null;
    }

    EnumMap<Mode, Double> calculateProbabilities(Purpose purpose,
                                                 MitoHousehold household,
                                                 MitoPerson person,
                                                 MitoZone originZone,
                                                 MitoZone destinationZone,
                                                 TravelTimes travelTimes,
                                                 double travelDistanceAuto,
                                                 double travelDistanceNMT,
                                                 double peakHour_s);

    /**
     * For the time being implementations of this interface should adhere to the following order in the result array:
     * [0] probability Auto driver
     * [1] probability Auto passenger
     * [2] probability bicyle
     * [3] probability bus
     * [4] probability train
     * [5] probability tram or metro
     * [6] probability walk
     */
    /**
     * Utilities of one trip. {@link ModeChoice} calls this version. By default it ignores the
     * trip and returns {@link #calculateUtilities(Purpose, MitoHousehold, MitoPerson, MitoZone,
     * MitoZone, TravelTimes, double, double, double)}; a calculator that needs trip attributes
     * the other arguments do not carry (e.g. the day of a 7-day trip) overrides it.
     */
    default EnumMap<Mode, Double> calculateUtilities(MitoTrip trip,
                                                     Purpose purpose,
                                                     MitoHousehold household,
                                                     MitoPerson person,
                                                     MitoZone originZone,
                                                     MitoZone destinationZone,
                                                     TravelTimes travelTimes,
                                                     double travelDistanceAuto,
                                                     double travelDistanceNMT,
                                                     double peakHour_s) {
        return calculateUtilities(purpose, household, person, originZone, destinationZone, travelTimes,
                travelDistanceAuto, travelDistanceNMT, peakHour_s);
    }

    EnumMap<Mode, Double> calculateUtilities(Purpose purpose,
                                    MitoHousehold household,
                                    MitoPerson person,
                                    MitoZone originZone,
                                    MitoZone destinationZone,
                                    TravelTimes travelTimes,
                                    double travelDistanceAuto,
                                    double travelDistanceNMT,
                                    double peakHour_s);


    /**
     * For the time being implementations of this interface should adhere to the following order in the result array:
     * [0] probability Auto driver
     * [1] probability Auto passenger
     * [2] probability bicyle
     * [3] probability bus
     * [4] probability train
     * [5] probability tram or metro
     * [6] probability walk
     */
    EnumMap<Mode, Double> calculateGeneralizedCosts(Purpose purpose,
                                MitoHousehold household,
                                MitoPerson person,
                                MitoZone originZone,
                                MitoZone destinationZone,
                                TravelTimes travelTimes,
                                double travelDistanceAuto,
                                double travelDistanceNMT,
                                double peakHour_s);
}
