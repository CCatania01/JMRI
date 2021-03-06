//LocationEditFrameTest.java
package jmri.jmrit.operations.locations;

import java.awt.GraphicsEnvironment;
import jmri.InstanceManager;
import jmri.jmrit.operations.OperationsSwingTestCase;
import jmri.util.JUnitUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the Operations Locations GUI class
 *
 * @author	Dan Boudreau Copyright (C) 2009
 */
public class LocationEditFrameTest extends OperationsSwingTestCase {

    final static int ALL = Track.EAST + Track.WEST + Track.NORTH + Track.SOUTH;

    @Test
    public void testLocationEditFrame() {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        loadLocations();

        LocationEditFrame f = new LocationEditFrame(null);
        f.setTitle("Test Add Location Frame");

        f.locationNameTextField.setText("New Test Location");
        enterClickAndLeave(f.addLocationButton);

        LocationManager lManager = InstanceManager.getDefault(LocationManager.class);
        Assert.assertEquals("should be 6 locations", 6, lManager.getLocationsByNameList().size());
        Location newLoc = lManager.getLocationByName("New Test Location");

        Assert.assertNotNull(newLoc);

        // add a yard track
        enterClickAndLeave(f.addYardButton);

        // add an interchange track
        enterClickAndLeave(f.addInterchangeButton);

        // add a staging track
        enterClickAndLeave(f.addStagingButton);

        // add a yard track
        enterClickAndLeave(f.addYardButton);

        f.locationNameTextField.setText("Newer Test Location");
        enterClickAndLeave(f.saveLocationButton);

        Assert.assertEquals("changed location name", "Newer Test Location", newLoc.getName());

        // test delete button
        enterClickAndLeave(f.deleteLocationButton);
        Assert.assertEquals("should be 6 locations", 6, lManager.getLocationsByNameList().size());
        // confirm delete dialog window should appear
        pressDialogButton(f, Bundle.getMessage("deletelocation?"), Bundle.getMessage("ButtonYes"));
        // location now deleted
        Assert.assertEquals("should be 5 locations", 5, lManager.getLocationsByNameList().size());

        JUnitUtil.dispose(f);
    }

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }
}
