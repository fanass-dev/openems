import { Component } from "@angular/core";
import { AbstractModal } from "src/app/shared/components/modal/abstractModal";
import { ChannelAddress, CurrentData } from "src/app/shared/shared";
import { Icon } from "src/app/shared/type/widget";

/** Factory-ID of the PV production forecast model whose Prediction*Ahead/
 * *Realized Channels are shown here - independent of ForecastChargeWindow's
 * own Component, resolved by Factory-ID since there is no direct config
 * reference between the two Components. */
const PREDICTOR_FACTORY_ID = "Predictor.Production.LinearModel";

const HORIZONS_HOURS = [1, 3, 6, 12] as const;

/** One column of the compact forecast table. */
interface PredictionColumn {
    hours: number;
    /** Forecast made now, for 'hours' from now - forward-looking. */
    ahead: string;
    /** Forecast that was made 'hours' ago, for right now - the archived
     * prediction to compare against currentProduction below, NOT the
     * measured value itself (see PredictionPersistenceService). */
    realized: string;
}

@Component({
    templateUrl: "./modal.html",
    standalone: false,
})
export class ModalComponent extends AbstractModal {

    /** Summary-row badge backgrounds - see field comment below for why badges are used. */
    private static readonly NEUTRAL_BADGE_BG = "rgba(128,128,128,0.12)";
    private static readonly WARNING_BADGE_BG = "rgba(255,196,0,0.16)";
    private static readonly DANGER_BADGE_BG = "rgba(200,30,30,0.14)";
    private static readonly SUCCESS_BADGE_BG = "rgba(51,102,0,0.14)";

    protected priceWithCurrency: string = "-";
    protected priceLabel: string = "-";
    protected blockStatus: string = "-";
    protected blockStatusIcon: Icon = { name: "help-outline", color: "medium", size: "small" };
    protected lastDecision: string = "-";
    protected currentProduction: string = "-";
    protected predictionColumns: PredictionColumn[] = HORIZONS_HOURS.map(hours => ({ hours, ahead: "-", realized: "-" }));

    /** Summary-row icons - a compact, at-a-glance alternative to reading 'lastDecision' as
     * text, showing the same three underlying factors (time window / forecast / price).
     * Each is drawn in a small tinted badge (icon color + badge background) rather than
     * plain-colored on the page background, for enough contrast to read at a glance - flat
     * opacity dimming (tried first) made the 'inactive' state look washed out/illegible. */
    protected withinTimeWindowIcon: Icon = { name: "time-outline", color: "medium", size: "large" };
    protected withinTimeWindowBadgeBg: string = ModalComponent.NEUTRAL_BADGE_BG;
    protected forecastIcon: Icon = { name: "help-circle-outline", color: "medium", size: "large" };
    protected forecastBadgeBg: string = ModalComponent.NEUTRAL_BADGE_BG;
    protected forecastedWhText: string | null = null;
    /** ion-icon color name ('success'/'danger') doesn't apply to the inline euro-note SVG below
     * (not an ion-icon), so its color/badge are set directly as CSS colors instead. */
    protected priceIconColor: string = "var(--ion-color-medium)";
    protected priceBadgeBg: string = ModalComponent.NEUTRAL_BADGE_BG;

    private predictorComponentId: string | null = null;

    protected override getChannelAddresses(): ChannelAddress[] {
        const channelAddresses = [
            new ChannelAddress(this.component.id, "CurrentGridSellPrice"),
            new ChannelAddress(this.component.id, "CurrentGridSellPriceProvider"),
            new ChannelAddress(this.component.id, "CurrentlyBlocked"),
            new ChannelAddress(this.component.id, "LastDecision"),
            new ChannelAddress(this.component.id, "WithinTimeWindow"),
            new ChannelAddress(this.component.id, "ForecastLiftedToday"),
            new ChannelAddress(this.component.id, "ForecastedAfternoonProduction"),
            new ChannelAddress(this.component.id, "PriceCurrentlyNegative"),
            new ChannelAddress("_sum", "ProductionActivePower"),
        ];

        this.predictorComponentId = this.config?.getComponentIdsByFactory(PREDICTOR_FACTORY_ID)?.[0] ?? null;
        if (this.predictorComponentId != null) {
            for (const hours of HORIZONS_HOURS) {
                channelAddresses.push(
                    new ChannelAddress(this.predictorComponentId, `PredictorProductionLinearModelPrediction${hours}hAhead`),
                    new ChannelAddress(this.predictorComponentId, `PredictorProductionLinearModelPrediction${hours}hRealized`),
                );
            }
        }

        return channelAddresses;
    }

    protected override onCurrentData(currentData: CurrentData): void {
        const price = currentData.allComponents[this.component.id + "/CurrentGridSellPrice"];
        this.priceWithCurrency = this.Utils.CONVERT_PRICE_TO_CENT_PER_KWH(2, "Ct/kWh")(price);
        const priceProvider = currentData.allComponents[this.component.id + "/CurrentGridSellPriceProvider"];
        const priceLabelBase = this.translate.instant("EDGE.INDEX.WIDGETS.FORECAST_CHARGE_WINDOW.PRICE");
        this.priceLabel = priceProvider ? `${priceLabelBase} (${priceProvider})` : priceLabelBase;

        const blocked = currentData.allComponents[this.component.id + "/CurrentlyBlocked"];
        this.blockStatus = this.translate.instant(blocked === 1
            ? "EDGE.INDEX.WIDGETS.FORECAST_CHARGE_WINDOW.BLOCKED"
            : "EDGE.INDEX.WIDGETS.FORECAST_CHARGE_WINDOW.UNBLOCKED");
        this.blockStatusIcon = blocked === 1
            ? { name: "lock-closed-outline", color: "danger", size: "small" }
            : { name: "lock-open-outline", color: "success", size: "small" };

        this.lastDecision = currentData.allComponents[this.component.id + "/LastDecision"] ?? "-";

        const withinTimeWindow = currentData.allComponents[this.component.id + "/WithinTimeWindow"];
        this.withinTimeWindowIcon = withinTimeWindow === 1
            ? { name: "time-outline", color: "dark", size: "large" }
            : { name: "time-outline", color: "medium", size: "large" };

        const forecastedWh = currentData.allComponents[this.component.id + "/ForecastedAfternoonProduction"];
        const forecastLiftedToday = currentData.allComponents[this.component.id + "/ForecastLiftedToday"];
        if (forecastedWh == null) {
            this.forecastIcon = { name: "help-circle-outline", color: "medium", size: "large" };
            this.forecastBadgeBg = ModalComponent.NEUTRAL_BADGE_BG;
            this.forecastedWhText = null;
        } else if (forecastLiftedToday === 1) {
            // Insufficient afternoon PV forecast (below threshold) - cloudy.
            this.forecastIcon = { name: "cloudy-outline", color: "medium", size: "large" };
            this.forecastBadgeBg = ModalComponent.NEUTRAL_BADGE_BG;
            this.forecastedWhText = this.Utils.CONVERT_TO_KILO_WATTHOURS(forecastedWh);
        } else {
            // Sufficient afternoon PV forecast (at/above threshold) - sunny.
            this.forecastIcon = { name: "sunny-outline", color: "warning", size: "large" };
            this.forecastBadgeBg = ModalComponent.WARNING_BADGE_BG;
            this.forecastedWhText = this.Utils.CONVERT_TO_KILO_WATTHOURS(forecastedWh);
        }

        const priceCurrentlyNegative = currentData.allComponents[this.component.id + "/PriceCurrentlyNegative"];
        this.priceIconColor = priceCurrentlyNegative === 1
            ? "var(--ion-color-danger)"
            : "var(--ion-color-success)";
        this.priceBadgeBg = priceCurrentlyNegative === 1
            ? ModalComponent.DANGER_BADGE_BG
            : ModalComponent.SUCCESS_BADGE_BG;

        const predictorComponentId = this.predictorComponentId;
        if (predictorComponentId != null) {
            this.currentProduction = this.Utils.CONVERT_WATT_TO_KILOWATT(currentData.allComponents["_sum/ProductionActivePower"]);
            this.predictionColumns = HORIZONS_HOURS.map(hours => ({
                hours,
                ahead: this.Utils.CONVERT_WATT_TO_KILOWATT(
                    currentData.allComponents[predictorComponentId + `/PredictorProductionLinearModelPrediction${hours}hAhead`]),
                realized: this.Utils.CONVERT_WATT_TO_KILOWATT(
                    currentData.allComponents[predictorComponentId + `/PredictorProductionLinearModelPrediction${hours}hRealized`]),
            }));
        }
    }
}
