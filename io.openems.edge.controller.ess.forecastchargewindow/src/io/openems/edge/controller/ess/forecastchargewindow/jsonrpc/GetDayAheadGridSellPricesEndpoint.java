package io.openems.edge.controller.ess.forecastchargewindow.jsonrpc;

import static io.openems.common.jsonrpc.serialization.JsonSerializerUtil.jsonObjectSerializer;

import java.time.Instant;
import java.util.List;

import io.openems.common.jsonrpc.serialization.EmptyObject;
import io.openems.common.jsonrpc.serialization.EndpointRequestType;
import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.common.jsonrpc.serialization.JsonSerializerUtil;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.controller.ess.forecastchargewindow.jsonrpc.GetDayAheadGridSellPricesEndpoint.Response;

/**
 * JSON-RPC Request for the "getDayAheadGridSellPrices" method.
 *
 * <p>
 * Unlike {@code CurrentGridSellPrice} (a historized Channel holding only the
 * already-elapsed current-quarter-hour value), this returns the full
 * day-ahead price array as currently known - including the not-yet-elapsed
 * remainder of today - straight from the in-memory
 * {@code EntsoeMarketPriceProvider}, for a "past + planned future" Live-view
 * chart (analogous to {@code Controller.Ess.Time-Of-Use-Tariff}'s schedule
 * chart, but without needing to hook into the unrelated
 * EnergyScheduleHandler/EnergyScheduler optimization framework).
 *
 * <p>
 * Example request:
 *
 * <pre>
 * {
 *   "method": "getDayAheadGridSellPrices",
 *   "params": {}
 * }
 * </pre>
 */
public class GetDayAheadGridSellPricesEndpoint implements EndpointRequestType<EmptyObject, Response> {

	@Override
	public String getMethod() {
		return "getDayAheadGridSellPrices";
	}

	@Override
	public JsonSerializer<EmptyObject> getRequestSerializer() {
		return EmptyObject.serializer();
	}

	@Override
	public JsonSerializer<Response> getResponseSerializer() {
		return Response.serializer();
	}

	public record Response(//
			List<PricePoint> prices, //
			String unit //
	) {

		public record PricePoint(Instant time, double price) {

			/**
			 * Returns a {@link JsonSerializer} for a {@link PricePoint}.
			 *
			 * @return the created {@link JsonSerializer}
			 */
			public static JsonSerializer<PricePoint> serializer() {
				return jsonObjectSerializer(PricePoint.class, json -> {
					return new PricePoint(//
							json.getInstant("time"), //
							json.getDouble("price"));
				}, obj -> JsonUtils.buildJsonObject()//
						.addProperty("time", obj.time())//
						.addProperty("price", obj.price())//
						.build());
			}
		}

		/**
		 * Returns a {@link JsonSerializer} for a {@link Response}.
		 *
		 * @return the created {@link JsonSerializer}
		 */
		public static JsonSerializer<Response> serializer() {
			return jsonObjectSerializer(Response.class, json -> {
				return new Response(//
						json.getList("prices", PricePoint.serializer()), //
						json.getString("unit"));
			}, obj -> JsonUtils.buildJsonObject()//
					.add("prices", PricePoint.serializer().toListSerializer().serialize(obj.prices()))//
					.addProperty("unit", obj.unit())//
					.build());
		}
	}
}
