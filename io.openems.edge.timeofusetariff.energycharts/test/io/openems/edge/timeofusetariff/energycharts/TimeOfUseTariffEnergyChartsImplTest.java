package io.openems.edge.timeofusetariff.energycharts;

import static io.openems.edge.timeofusetariff.energycharts.Zone.GERMANY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.junit.Test;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.test.ComponentTest;

public class TimeOfUseTariffEnergyChartsImplTest {

	@Test
	public void test() throws Exception {
		var energyCharts = new TimeOfUseTariffEnergyChartsImpl();
		new ComponentTest(energyCharts) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setZone(GERMANY) //
						.build()) //
		;
	}

	@Test
	public void nonEmptyStringTest() throws OpenemsNamedException {
		// Parsing with custom data - matches the real Energy-Charts response shape
		// (parallel 'unix_seconds'/'price' arrays, already quarter-hourly).
		var prices = TimeOfUseTariffEnergyChartsImpl.parsePrices("""
				{
				   "license_info":"CC BY 4.0 (Bundesnetzagentur | SMARD.de)",
				   "unix_seconds":[1632402000,1632402900,1632403800,1632404700],
				   "price":[158.95,160.98,171.15,174.96],
				   "unit":"EUR / MWh",
				   "deprecated":false
				}"""); //

		// To check if the Map is not empty
		assertFalse(prices.isEmpty());

		// To check if a value is present in map.
		assertEquals(158.95, prices.getFirst(), 0.001);
	}

	@Test
	public void emptyStringTest() throws OpenemsNamedException {
		try {
			// Parsing with empty string
			TimeOfUseTariffEnergyChartsImpl.parsePrices("");
		} catch (OpenemsNamedException e) {
			// expected
			return;
		}

		fail("Expected Exception");
	}

}
