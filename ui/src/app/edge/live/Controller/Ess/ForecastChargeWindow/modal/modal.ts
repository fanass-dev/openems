import { Component } from "@angular/core";
import { AbstractModal } from "src/app/shared/components/modal/abstractModal";
import { ChannelAddress, CurrentData } from "src/app/shared/shared";

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

    protected priceWithCurrency: string = "-";
    protected blockStatus: string = "-";
    protected lastDecision: string = "-";
    protected currentProduction: string = "-";
    protected predictionColumns: PredictionColumn[] = HORIZONS_HOURS.map(hours => ({ hours, ahead: "-", realized: "-" }));

    private predictorComponentId: string | null = null;

    protected override getChannelAddresses(): ChannelAddress[] {
        const channelAddresses = [
            new ChannelAddress(this.component.id, "CurrentGridSellPrice"),
            new ChannelAddress(this.component.id, "CurrentlyBlocked"),
            new ChannelAddress(this.component.id, "LastDecision"),
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

        const blocked = currentData.allComponents[this.component.id + "/CurrentlyBlocked"];
        this.blockStatus = this.translate.instant(blocked === 1
            ? "EDGE.INDEX.WIDGETS.FORECAST_CHARGE_WINDOW.BLOCKED"
            : "EDGE.INDEX.WIDGETS.FORECAST_CHARGE_WINDOW.UNBLOCKED");

        this.lastDecision = currentData.allComponents[this.component.id + "/LastDecision"] ?? "-";

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
