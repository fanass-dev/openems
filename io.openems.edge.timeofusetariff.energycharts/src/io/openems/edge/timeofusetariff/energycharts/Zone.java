package io.openems.edge.timeofusetariff.energycharts;

/**
 * Represents different energy market zones supported by the Energy-Charts
 * API.
 */
public enum Zone {

	/**
	 * The energy market zone for Germany (and Luxembourg, shared bidding zone).
	 */
	GERMANY, //

	/**
	 * The energy market zone for Austria.
	 */
	AUSTRIA;

	/**
	 * Returns the Energy-Charts API bidding zone code for this {@link Zone}.
	 *
	 * @return the bidding zone code, e.g. "DE-LU"
	 */
	public String toBiddingZone() {
		return switch (this) {
		case GERMANY -> "DE-LU";
		case AUSTRIA -> "AT";
		};
	}
}
