package io.openems.edge.controller.ess.topofcharge;

import static io.openems.common.test.TestUtils.createDummyClock;
import static io.openems.edge.controller.ess.topofcharge.ControllerEssTopOfCharge.ChannelId.ACTIVE_MAX_SOC;
import static io.openems.edge.controller.ess.topofcharge.ControllerEssTopOfCharge.ChannelId.CURRENTLY_LIMITED;
import static io.openems.edge.ess.api.ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_GREATER_OR_EQUALS;
import static io.openems.edge.ess.api.SymmetricEss.ChannelId.SOC;

import org.junit.Test;

import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.controller.test.ControllerTest;
import io.openems.edge.ess.test.DummyManagedSymmetricEss;

public class ControllerEssTopOfChargeImplTest {

	private static final String ALWAYS_80_PERCENT = "["
			+ "{ \"@type\": \"Task\", \"start\": \"2020-01-01T00:00:00\", \"duration\": \"P1D\","
			+ "  \"recurrenceRules\": [{ \"frequency\": \"daily\", \"until\": \"2030-12-31\" }],"
			+ "  \"openems.io:payload\": 80 }" //
			+ "]";

	private static final String ONLY_COVERS_THE_PAST = "["
			+ "{ \"@type\": \"Task\", \"start\": \"2019-01-01T00:00:00\", \"duration\": \"P1D\","
			+ "  \"recurrenceRules\": [{ \"frequency\": \"daily\", \"until\": \"2019-12-31\" }],"
			+ "  \"openems.io:payload\": 80 }" //
			+ "]";

	@Test
	public void test_withinConfiguredPeriod() throws Exception {
		new ControllerTest(new ControllerEssTopOfChargeImpl()) //
				.addReference("componentManager", new DummyComponentManager(createDummyClock())) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.withSoc(70) //
						.withCapacity(9000)) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setEssId("ess0") //
						.setJsCalendar(ALWAYS_80_PERCENT) //
						.build())
				.next(new TestCase() //
						.input("ess0", SOC, 70) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS, null) //
						.output(ACTIVE_MAX_SOC, 80) //
						.output(CURRENTLY_LIMITED, false)) //
				.next(new TestCase() //
						.input("ess0", SOC, 79) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS, null) //
						.output(CURRENTLY_LIMITED, false)) //
				.next(new TestCase() //
						.input("ess0", SOC, 80) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS, 0) //
						.output(CURRENTLY_LIMITED, true)) //
				.next(new TestCase() //
						.input("ess0", SOC, 85) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS, 0) //
						.output(CURRENTLY_LIMITED, true)) //
				.next(new TestCase() //
						.input("ess0", SOC, 79) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS, null) //
						.output(CURRENTLY_LIMITED, false)) //
				.next(new TestCase() //
						.input("ess0", SOC, null) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS, null) //
						.output(CURRENTLY_LIMITED, false)) //
				.deactivate();
	}

	@Test
	public void test_emptySchedule_neverLimited() throws Exception {
		new ControllerTest(new ControllerEssTopOfChargeImpl()) //
				.addReference("componentManager", new DummyComponentManager(createDummyClock())) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.withSoc(95) //
						.withCapacity(9000)) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setEssId("ess0") //
						.setJsCalendar("[]") //
						.build())
				.next(new TestCase() //
						.input("ess0", SOC, 95) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS, null) //
						.output(ACTIVE_MAX_SOC, null) //
						.output(CURRENTLY_LIMITED, false)) //
				.deactivate();
	}

	@Test
	public void test_outsideConfiguredPeriod_notLimited() throws Exception {
		new ControllerTest(new ControllerEssTopOfChargeImpl()) //
				.addReference("componentManager", new DummyComponentManager(createDummyClock())) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.withSoc(95) //
						.withCapacity(9000)) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setEssId("ess0") //
						.setJsCalendar(ONLY_COVERS_THE_PAST) //
						.build())
				.next(new TestCase() //
						.input("ess0", SOC, 95) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS, null) //
						.output(ACTIVE_MAX_SOC, null) //
						.output(CURRENTLY_LIMITED, false)) //
				.deactivate();
	}

}
