// TrainSwitchLists.java

package jmri.jmrit.operations.trains;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.MessageFormat;
import java.util.List;

import jmri.jmrit.operations.locations.Location;
import jmri.jmrit.operations.rollingstock.cars.Car;
import jmri.jmrit.operations.rollingstock.engines.Engine;
import jmri.jmrit.operations.routes.Route;
import jmri.jmrit.operations.routes.RouteLocation;
import jmri.jmrit.operations.setup.Setup;
import jmri.util.FileUtil;

/**
 * Builds a switch list for a location on the railroad
 * 
 * @author Daniel Boudreau (C) Copyright 2008, 2011, 2012, 2013, 2015
 * @version $Revision: 21846 $
 * 
 */
public class TrainSwitchLists extends TrainCommon {

	TrainManager trainManager = TrainManager.instance();
	private static final char FORM_FEED = '\f';
	private static final boolean IS_PRINT_HEADER = true;

	String messageFormatText = ""; // the text being formated in case there's an exception

	/**
	 * builds a switch list for a location
	 * 
	 * @param location
	 *            The Location needing a switch list
	 */
	public void buildSwitchList(Location location) {
		// Append switch list data if not operating in real time
		boolean newTrainsOnly = !Setup.isSwitchListRealTime();
		boolean append = false;
		boolean checkFormFeed = true; // used to determine if FF needed between trains
		if (newTrainsOnly) {
			if (!location.getStatus().equals(Location.MODIFIED) && !Setup.isSwitchListAllTrainsEnabled())
				return; // nothing to add
			append = location.getSwitchListState() == Location.SW_APPEND;
			if (location.getSwitchListState() != Location.SW_APPEND)
				location.setSwitchListState(Location.SW_APPEND);
			location.setStatus(Location.UPDATED);
		}

		log.debug("Append: {} for location ({})", append, location.getName());

		// create switch list file
		File file = TrainManagerXml.instance().createSwitchListFile(location.getName());

		PrintWriter fileOut = null;
		try {
			fileOut = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, append),
					"UTF-8")), true); // NOI18N
		} catch (IOException e) {
			log.error("Can not open switchlist file: {}", file.getName());
			return;
		}
		try {
			// build header
			if (!append) {
				newLine(fileOut, Setup.getRailroadName());
				newLine(fileOut);
				newLine(fileOut, MessageFormat.format(messageFormatText = TrainSwitchListText.getStringSwitchListFor(),
						new Object[] { splitString(location.getName()) }));
				if (!location.getSwitchListComment().equals(""))
					newLine(fileOut, location.getSwitchListComment());
			}

			String valid = MessageFormat.format(messageFormatText = TrainManifestText.getStringValid(),
					new Object[] { getDate(true) });
			if (Setup.isPrintTimetableNameEnabled()) {
				TrainSchedule sch = TrainScheduleManager.instance().getScheduleById(
						trainManager.getTrainScheduleActiveId());
				if (sch != null)
					valid = valid + " (" + sch.getName() + ")";
			}

			// get a list of trains sorted by arrival time
			List<Train> trains = trainManager.getTrainsArrivingThisLocationList(location);
			for (Train train : trains) {
				if (!train.isBuilt())
					continue; // train wasn't built so skip
				if (newTrainsOnly && train.getSwitchListStatus().equals(Train.PRINTED))
					continue; // already printed this train
				Route route = train.getRoute();
				if (route == null)
					continue; // no route for this train
				// determine if train works this location
				boolean works = isThereWorkAtLocation(train, location);
				if (!works && !Setup.isSwitchListAllTrainsEnabled()) {
					log.debug("No work for train ({}) at location ({})", train.getName(), location.getName());
					continue;
				}
				// we're now going to add to the switch list
				if (checkFormFeed) {
					if (append && !Setup.getSwitchListPageFormat().equals(Setup.PAGE_NORMAL)) {
						fileOut.write(FORM_FEED);
					}
					if (Setup.isPrintValidEnabled())
						newLine(fileOut, valid);
				} else if (!Setup.getSwitchListPageFormat().equals(Setup.PAGE_NORMAL)) {
					fileOut.write(FORM_FEED);
				}
				checkFormFeed = false; // done with FF for this train
				// some cars booleans and the number of times this location get's serviced
				pickupCars = false;	// when true there was a car pick up
				dropCars = false;	// when true there was a car set out
				int stops = 1;
				boolean trainDone = false;
				// get engine and car lists
				List<Engine> engineList = engineManager.getByTrainBlockingList(train);
				List<Car> carList = carManager.getByTrainDestinationList(train);
				List<RouteLocation> routeList = route.getLocationsBySequenceList();
				RouteLocation rlPrevious = null;
				// does the train stop once or more at this location?
				for (RouteLocation rl : routeList) {
					if (!splitString(rl.getName()).equals(splitString(location.getName()))) {
						rlPrevious = rl;
						continue;
					}
					String expectedArrivalTime = train.getExpectedArrivalTime(rl);
					if (expectedArrivalTime.equals(Train.ALREADY_SERVICED)) {
						trainDone = true;
					}
					// first time at this location?
					if (stops == 1) {
						newLine(fileOut);
						newLine(fileOut, MessageFormat.format(messageFormatText = TrainSwitchListText
								.getStringScheduledWork(), new Object[] { train.getName(), train.getDescription() }));
						if (train.isTrainInRoute()) {
							if (!trainDone) {
								newLine(fileOut, MessageFormat.format(messageFormatText = TrainSwitchListText
										.getStringDepartedExpected(), new Object[] {
										splitString(train.getTrainDepartsName()), expectedArrivalTime,
										rl.getTrainDirectionString() }));
							}
						} else if (!train.isLocalSwitcher()) {
							if (rl == train.getRoute().getDepartsRouteLocation()) {
								newLine(fileOut, MessageFormat.format(messageFormatText = TrainSwitchListText
										.getStringDepartsAt(), new Object[] { splitString(train.getTrainDepartsName()),
										rl.getTrainDirectionString(), train.getFormatedDepartureTime() }));
							} else {
								newLine(fileOut, MessageFormat.format(messageFormatText = TrainSwitchListText
										.getStringDepartsAtExpectedArrival(), new Object[] {
										splitString(train.getTrainDepartsName()), train.getFormatedDepartureTime(),
										expectedArrivalTime, rl.getTrainDirectionString() }));
							}
						}
					} else {
						// multiple visits to this location
						// Print visit number only if previous location wasn't the same
						if (rlPrevious == null || !splitString(rl.getName()).equals(splitString(rlPrevious.getName()))) {
							if (Setup.getSwitchListPageFormat().equals(Setup.PAGE_PER_VISIT)) {
								fileOut.write(FORM_FEED);
							}
							newLine(fileOut);
							if (train.isTrainInRoute()) {
								if (expectedArrivalTime.equals(Train.ALREADY_SERVICED))
									newLine(fileOut, MessageFormat.format(messageFormatText = TrainSwitchListText
											.getStringVisitNumberDone(), new Object[] { stops, train.getName(),
											train.getDescription() }));
								else if (rl != train.getRoute().getTerminatesRouteLocation())
									newLine(fileOut, MessageFormat
											.format(messageFormatText = TrainSwitchListText
													.getStringVisitNumberDeparted(), new Object[] { stops,
													train.getName(), expectedArrivalTime, rl.getTrainDirectionString(),
													train.getDescription() }));
								else
									newLine(fileOut, MessageFormat.format(messageFormatText = TrainSwitchListText
											.getStringVisitNumberTerminatesDeparted(), new Object[] { stops,
											train.getName(), expectedArrivalTime, splitString(rl.getName()),
											train.getDescription() }));
							} else {
								// train hasn't departed
								if (rl != train.getRoute().getTerminatesRouteLocation())
									newLine(fileOut, MessageFormat
											.format(messageFormatText = TrainSwitchListText.getStringVisitNumber(),
													new Object[] { stops, train.getName(), expectedArrivalTime,
															rl.getTrainDirectionString(), train.getDescription() }));
								else
									newLine(fileOut, MessageFormat.format(messageFormatText = TrainSwitchListText
											.getStringVisitNumberTerminates(), new Object[] { stops, train.getName(),
											expectedArrivalTime, splitString(rl.getName()), train.getDescription() }));
							}
						} else {
							stops--; // don't bump stop count, same location
							// Does the train reverse direction?
							if (rl.getTrainDirection() != rlPrevious.getTrainDirection()
									&& !TrainSwitchListText.getStringTrainDirectionChange().equals(""))
								newLine(fileOut, MessageFormat.format(messageFormatText = TrainSwitchListText
										.getStringTrainDirectionChange(), new Object[] { train.getName(),
										rl.getTrainDirectionString(), train.getDescription(),
										train.getTrainTerminatesName() }));
						}
					}
					
					rlPrevious = rl;	// save current location in case there's back to back location with the same name

					// add route comment
					if (Setup.isSwitchListRouteLocationCommentEnabled() && !rl.getComment().trim().equals(""))
						newLine(fileOut, rl.getComment());

					if (Setup.getManifestFormat().equals(Setup.STANDARD_FORMAT)) {
						pickupEngines(fileOut, engineList, rl, !IS_MANIFEST);
						dropEngines(fileOut, engineList, rl, !IS_MANIFEST);
						blockCarsByTrack(fileOut, train, carList, routeList, rl, IS_PRINT_HEADER, !IS_MANIFEST);
					} else if (Setup.getManifestFormat().equals(Setup.TWO_COLUMN_FORMAT)) {
						blockLocosTwoColumn(fileOut, engineList, rl, !IS_MANIFEST);
						blockCarsByTrackTwoColumn(fileOut, train, carList, routeList, rl, IS_PRINT_HEADER, !IS_MANIFEST);
					} else {
						blockLocosTwoColumn(fileOut, engineList, rl, !IS_MANIFEST);
						blockCarsByTrackNameTwoColumn(fileOut, train, carList, routeList, rl, IS_PRINT_HEADER,
								!IS_MANIFEST);
					}
					if (Setup.isPrintHeadersEnabled() || !Setup.getManifestFormat().equals(Setup.STANDARD_FORMAT))
						printHorizontalLine(fileOut, !IS_MANIFEST);

					stops++;

					if (rl != train.getRoute().getTerminatesRouteLocation()) {
						RouteLocation nextRl = train.getRoute().getNextRouteLocation(rl);
						if (splitString(rl.getName()).equals(splitString(nextRl.getName())))
							continue; // the current location name is the "same" as the next
						// print departure text if not a switcher and not the last location in the route
						if (!train.isLocalSwitcher()) {
							String trainDeparts = "";
							if (Setup.isPrintLoadsAndEmptiesEnabled()) {
								int emptyCars = train.getNumberEmptyCarsInTrain(rl);
								// Message format: Train departs Boston Westbound with 4 loads, 8 empties, 450 feet,
								// 3000 tons
								trainDeparts = MessageFormat.format(TrainSwitchListText.getStringTrainDepartsLoads(),
										new Object[] { TrainCommon.splitString(rl.getName()),
												rl.getTrainDirectionString(),
												train.getNumberCarsInTrain(rl) - emptyCars, emptyCars,
												train.getTrainLength(rl), Setup.getLengthUnit().toLowerCase(),
												train.getTrainWeight(rl), train.getTrainTerminatesName(),
												train.getName() });
							} else {
								// Message format: Train departs Boston Westbound with 12 cars, 450 feet, 3000 tons
								trainDeparts = MessageFormat.format(TrainSwitchListText.getStringTrainDepartsCars(),
										new Object[] { TrainCommon.splitString(rl.getName()),
												rl.getTrainDirectionString(), train.getNumberCarsInTrain(rl),
												train.getTrainLength(rl), Setup.getLengthUnit().toLowerCase(),
												train.getTrainWeight(rl), train.getTrainTerminatesName(),
												train.getName() });
							}
							newLine(fileOut, trainDeparts);
						}
					}
				}
				if (trainDone && !pickupCars && !dropCars) {
					newLine(fileOut, MessageFormat.format(messageFormatText = TrainSwitchListText.getStringTrainDone(),
							new Object[] { train.getName(), train.getDescription(), splitString(location.getName()) }));
				} else {
					if (stops > 1 && !pickupCars) {
						newLine(fileOut, MessageFormat.format(messageFormatText = TrainSwitchListText
								.getStringNoCarPickUps(), new Object[] { train.getName(), train.getDescription(),
								splitString(location.getName()) }));
					}
					if (stops > 1 && !dropCars) {
						newLine(fileOut, MessageFormat.format(messageFormatText = TrainSwitchListText
								.getStringNoCarDrops(), new Object[] { train.getName(), train.getDescription(),
								splitString(location.getName()) }));
					}
				}
			}

		} catch (IllegalArgumentException e) {
			newLine(fileOut, MessageFormat.format(Bundle.getMessage("ErrorIllegalArgument"), new Object[] {
					Bundle.getMessage("TitleSwitchListText"), e.getLocalizedMessage() }));
			newLine(fileOut, messageFormatText);
			e.printStackTrace();
		}

		// Are there any cars that need to be found?
		addCarsLocationUnknown(fileOut, !IS_MANIFEST);
		fileOut.flush();
		fileOut.close();
	}

	public void printSwitchList(Location location, boolean isPreview) {
		File buildFile = TrainManagerXml.instance().getSwitchListFile(location.getName());
		if (!buildFile.exists()) {
			log.warn("Switch list file missing for location ({})", location.getName());
			return;
		}
		if (isPreview && Setup.isManifestEditorEnabled()) {
			TrainPrintUtilities.openDesktopEditor(buildFile);
		} else {
			TrainPrintUtilities.printReport(buildFile, location.getName(), isPreview, Setup.getFontName(), false,
					FileUtil.getExternalFilename(Setup.getManifestLogoURL()), location.getDefaultPrinterName(), Setup
							.getSwitchListOrientation(), Setup.getManifestFontSize());
		}
		if (!isPreview) {
			location.setStatus(Location.PRINTED);
			location.setSwitchListState(Location.SW_PRINTED);
		}
	}

	protected void newLine(PrintWriter file, String string) {
		newLine(file, string, !IS_MANIFEST);
	}

	static Logger log = LoggerFactory.getLogger(TrainSwitchLists.class.getName());
}