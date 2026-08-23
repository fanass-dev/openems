package io.openems.edge.bridge.eos;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.bridge.http.api.BridgeHttp;
import io.openems.common.bridge.http.api.BridgeHttpFactory;
import io.openems.common.bridge.http.api.UrlBuilder;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Bridge.Eos", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class BridgeEosImpl extends AbstractOpenemsComponent implements BridgeEos, OpenemsComponent {

	private final Logger log = LoggerFactory.getLogger(BridgeEosImpl.class);

	@Reference
	private BridgeHttpFactory httpBridgeFactory;
	private BridgeHttp httpBridge;

	private Config config;

	public BridgeEosImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				BridgeEos.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.httpBridge = this.httpBridgeFactory.get();
	}

	@Override
	@Deactivate
	protected void deactivate() {
		this.httpBridgeFactory.unget(this.httpBridge);
		this.httpBridge = null;
		super.deactivate();
	}

	@Override
	public CompletableFuture<Void> putMeasurementValue(String key, Instant datetime, double value) {
		var url = UrlBuilder.parse(this.config.baseUrl()) //
				.withPath("/v1/measurement/value") //
				.withQueryParam("datetime", datetime.toString()) //
				.withQueryParam("key", key) //
				.withQueryParam("value", Double.toString(value)) //
				.toEncodedString();

		var result = new CompletableFuture<Void>();
		this.httpBridge.put(url) //
				.thenAccept(response -> {
					this._setLastRequestFailed(false);
					result.complete(null);
				}) //
				.exceptionally(e -> {
					this._setLastRequestFailed(true);
					this.logWarn(this.log, "EOS-Anfrage [" + url + "] fehlgeschlagen: " + e.getMessage());
					result.completeExceptionally(e);
					return null;
				});
		return result;
	}
}
