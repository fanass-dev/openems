import { Component } from "@angular/core";
import { AbstractModal } from "src/app/shared/components/modal/abstractModal";
import { ChannelAddress, CurrentData } from "src/app/shared/shared";

/** Factory-ID of the PV production forecast model whose Prediction*Ahead
 * Channels are shown here - independent of ForecastChargeWindow's own
 * Component, resolved by Factory-ID since there is no direct config
 * reference between the two Components. */
const PREDICTOR_FACTORY_ID = "Predictor.Production.LinearModel";

@Component({
    templateUrl: "./modal.html",
    standalone: false,
})
export class ModalComponent extends AbstractModal {

    protected priceWithCurrency: string = "-";
    protected blockStatus: string = "-";
    protected lastDecision: string = "-";
    protected prediction1hAhead: string = "-";
    protected prediction3hAhead: string = "-";
    protected prediction6hAhead: string = "-";
    protected prediction12hAhead: string = "-";

    private predictorComponentId: string | null = null;

    protected override getChannelAddresses(): ChannelAddress[] {
        const channelAddresses = [
            new ChannelAddress(this.component.id, "CurrentGridSellPrice"),
            new ChannelAddress(this.component.id, "CurrentlyBlocked"),
            new ChannelAddress(this.component.id, "LastDecision"),
        ];

        this.predictorComponentId = this.config?.getComponentIdsByFactory(PREDICTOR_FACTORY_ID)?.[0] ?? null;
        if (this.predictorComponentId != null) {
            channelAddresses.push(
                new ChannelAddress(this.predictorComponentId, "PredictorProductionLinearModelPrediction1hAhead"),
                new ChannelAddress(this.predictorComponentId, "PredictorProductionLinearModelPrediction3hAhead"),
                new ChannelAddress(this.predictorComponentId, "PredictorProductionLinearModelPrediction6hAhead"),
                new ChannelAddress(this.predictorComponentId, "PredictorProductionLinearModelPrediction12hAhead"),
            );
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

        if (this.predictorComponentId != null) {
            this.prediction1hAhead = this.Utils.CONVERT_WATT_TO_KILOWATT(
                currentData.allComponents[this.predictorComponentId + "/PredictorProductionLinearModelPrediction1hAhead"]);
            this.prediction3hAhead = this.Utils.CONVERT_WATT_TO_KILOWATT(
                currentData.allComponents[this.predictorComponentId + "/PredictorProductionLinearModelPrediction3hAhead"]);
            this.prediction6hAhead = this.Utils.CONVERT_WATT_TO_KILOWATT(
                currentData.allComponents[this.predictorComponentId + "/PredictorProductionLinearModelPrediction6hAhead"]);
            this.prediction12hAhead = this.Utils.CONVERT_WATT_TO_KILOWATT(
                currentData.allComponents[this.predictorComponentId + "/PredictorProductionLinearModelPrediction12hAhead"]);
        }
    }
}
