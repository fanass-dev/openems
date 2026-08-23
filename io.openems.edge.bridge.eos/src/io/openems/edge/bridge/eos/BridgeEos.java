package io.openems.edge.bridge.eos;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

/**
 * Generic, "dumb" HTTP bridge to an Akkudoktor-EOS REST server
 * (https://github.com/Akkudoktor-EOS/EOS). Knows nothing about OpenEMS
 * Channels or which EOS measurement key a value belongs to - callers decide
 * that and pass it in. Multiple components can reference the same
 * Bridge.Eos instance (e.g. by Component-ID), analogous to how BridgeModbus
 * or EntsoeMarketPriceProviderPool are shared.
 */
public interface BridgeEos extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		LAST_REQUEST_FAILED(Doc.of(OpenemsType.BOOLEAN) //
				.text("true, wenn die letzte Anfrage an EOS fehlgeschlagen ist"));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#LAST_REQUEST_FAILED}.
	 *
	 * @param value the next value
	 */
	public default void _setLastRequestFailed(boolean value) {
		this.channel(ChannelId.LAST_REQUEST_FAILED).setNextValue(value);
	}

	/**
	 * Merges a single measurement value into EOS at the given point in time, via
	 * {@code PUT /v1/measurement/value}.
	 *
	 * @param key      the EOS measurement key, as configured in EOS' own
	 *                 {@code MeasurementCommonSettings} (e.g. one of the entries
	 *                 in {@code pv_production_emr_keys})
	 * @param datetime the point in time the value belongs to
	 * @param value    the value, in the unit EOS expects for this key (e.g. kWh
	 *                 for an Energy-Meter-Reading key)
	 * @return a future, completing exceptionally on failure
	 */
	public CompletableFuture<Void> putMeasurementValue(String key, Instant datetime, double value);
}
