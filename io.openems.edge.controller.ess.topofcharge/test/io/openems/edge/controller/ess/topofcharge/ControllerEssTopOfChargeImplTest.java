package io.openems.edge.controller.ess.topofcharge;

import static io.openems.edge.controller.ess.topofcharge.ControllerEssTopOfCharge.ChannelId.CURRENTLY_LIMITED;
import static io.openems.edge.ess.api.ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_GREATER_OR_EQUALS;
import static io.openems.edge.ess.api.SymmetricEss.ChannelId.SOC;

import org.junit.Test;

import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.controller.test.ControllerTest;
import io.openems.edge.ess.test.DummyManagedSymmetricEss;

public class ControllerEssTopOfChargeImplTest {

	@Test
	public void test() throws Exception {
		new ControllerTest(new ControllerEssTopOfChargeImpl()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.withSoc(70) //
						.withCapacity(9000)) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setEssId("ess0") //
						.setMaxSoc(80) //
						.build())
				.next(new TestCase() //
						.input("ess0", SOC, 70) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS, null) //
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

}
