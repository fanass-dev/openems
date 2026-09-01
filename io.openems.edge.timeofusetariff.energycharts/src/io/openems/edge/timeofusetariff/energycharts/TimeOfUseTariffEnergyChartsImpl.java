package io.openems.edge.timeofusetariff.energycharts;

import static io.openems.common.utils.JsonUtils.getAsDouble;
import static io.openems.common.utils.JsonUtils.getAsJsonArray;
import static io.openems.common.utils.JsonUtils.getAsLong;
import static io.openems.common.utils.JsonUtils.parseToJsonObject;
import static io.openems.edge.timeofusetariff.api.utils.TimeOfUseTariffUtils.generateDebugLog;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import com.google.common.collect.ImmutableSortedMap;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.utils.ThreadPoolUtils;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.meta.Meta;
import io.openems.edge.timeofusetariff.api.TimeOfUsePrices;
import io.openems.edge.timeofusetariff.api.TimeOfUseTariff;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * Reads day-ahead electricity prices from the Energy-Charts API
 * (https://api.energy-charts.info), operated by Fraunhofer ISE. For Germany
 * (Zone.GERMANY, bidding zone "DE-LU") the underlying data is republished
 * from Bundesnetzagentur | SMARD.de under a CC BY 4.0 license.
 *
 * <p>
 * Structurally mirrors {@code io.openems.edge.timeofusetariff.awattar} (same
 * simple OkHttp + single-thread-executor polling pattern, no API key
 * required) - deliberately kept close to that sibling bundle rather than the
 * BridgeHttp-cycle-service pattern used elsewhere, to fit in with the other
 * official {@code io.openems.edge.timeofusetariff.*} bundles.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "TimeOfUseTariff.EnergyCharts", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class TimeOfUseTariffEnergyChartsImpl extends AbstractOpenemsComponent
		implements TimeOfUseTariff, OpenemsComponent, TimeOfUseTariffEnergyCharts {

	private static final String BASE_URL = "https://api.energy-charts.info/price";

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private final AtomicReference<TimeOfUsePrices> prices = new AtomicReference<>(TimeOfUsePrices.EMPTY_PRICES);

	@Reference
	private Meta meta;

	@Reference
	private ComponentManager componentManager;

	private Config config = null;

	public TimeOfUseTariffEnergyChartsImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				TimeOfUseTariffEnergyCharts.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());

		if (!config.enabled()) {
			return;
		}

		this.config = config;
		this.executor.schedule(this.task, 0, TimeUnit.SECONDS);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
		ThreadPoolUtils.shutdownAndAwaitTermination(this.executor, 0);
	}

	private final Runnable task = () -> {

		/*
		 * Update Map of prices
		 */
		var client = new OkHttpClient();
		final var url = this.buildUrl();
		var request = new Request.Builder() //
				.url(url) //
				// Energy-Charts does not require an Apikey.
				.build();
		int httpStatusCode;
		try (var response = client.newCall(request).execute()) {
			httpStatusCode = response.code();

			if (!response.isSuccessful()) {
				throw new IOException("Unexpected code " + response);
			}

			// Parse the response for the prices
			this.prices.set(parsePrices(response.body().string()));

		} catch (IOException | OpenemsNamedException e) {
			e.printStackTrace();
			httpStatusCode = 0;
			// TODO Try again in x minutes
		}

		this.channel(TimeOfUseTariffEnergyCharts.ChannelId.HTTP_STATUS_CODE).setNextValue(httpStatusCode);

		/*
		 * Schedule next price update every hour
		 */
		var now = ZonedDateTime.now();
		var nextRun = now.plusHours(1).truncatedTo(ChronoUnit.HOURS);
		var delay = Duration.between(now, nextRun).getSeconds();

		this.executor.schedule(this.task, delay, TimeUnit.SECONDS);
	};

	/**
	 * Builds the Energy-Charts request URL, explicitly querying a two-day window
	 * (today plus tomorrow) - Energy-Charts supports explicit start/end date
	 * parameters (unlike e.g. aWATTar, which only ever returns "whatever it
	 * currently has"), so this always asks for the same, predictable range
	 * regardless of what time of day this happens to run.
	 *
	 * @return the request URL
	 */
	private String buildUrl() {
		var today = LocalDate.now();
		var start = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
		var end = today.plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE);
		return BASE_URL + "?bzn=" + this.config.zone().toBiddingZone() + "&start=" + start + "&end=" + end;
	}

	@Override
	public TimeOfUsePrices getPrices() {
		return TimeOfUsePrices.from(Instant.now(this.componentManager.getClock()), this.prices.get());
	}

	/**
	 * Parse the Energy-Charts JSON to {@link TimeOfUsePrices}.
	 *
	 * <p>
	 * Energy-Charts already reports natively in quarter-hourly resolution (two
	 * parallel arrays {@code unix_seconds}/{@code price}), unlike aWATTar's
	 * hourly values that need manual expansion into four quarters each.
	 *
	 * @param jsonData the Energy-Charts JSON
	 * @return the {@link TimeOfUsePrices}
	 * @throws OpenemsNamedException on error
	 */
	public static TimeOfUsePrices parsePrices(String jsonData) throws OpenemsNamedException {
		var result = ImmutableSortedMap.<Instant, Double>naturalOrder();
		var json = parseToJsonObject(jsonData);
		var timestamps = getAsJsonArray(json, "unix_seconds");
		var priceValues = getAsJsonArray(json, "price");

		for (var i = 0; i < timestamps.size(); i++) {
			var timestamp = Instant.ofEpochSecond(getAsLong(timestamps.get(i)));
			var price = getAsDouble(priceValues.get(i));
			result.put(timestamp, price);
		}
		return TimeOfUsePrices.from(result.build());
	}

	@Override
	public String debugLog() {
		return generateDebugLog(this, this.meta.getCurrency());
	}
}
