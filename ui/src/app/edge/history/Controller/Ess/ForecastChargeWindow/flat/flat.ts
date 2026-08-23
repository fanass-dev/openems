import { Component } from "@angular/core";

import { AbstractFlatWidget } from "src/app/shared/components/flat/abstract-flat-widget";
import { ChannelAddress, CurrentData } from "src/app/shared/shared";

@Component({
    selector: "forecastChargeWindowWidget",
    templateUrl: "./flat.html",
    standalone: false,
})
export class FlatComponent extends AbstractFlatWidget {

    protected negativePriceDurationOverPeriod: number | null = null;

    override getChannelAddresses(): ChannelAddress[] {
        if (this.componentId == null) {
            return [];
        }
        return [
            new ChannelAddress(this.componentId, "NegativePriceDuration"),
        ];
    }

    protected override onCurrentData(currentData: CurrentData) {
        this.negativePriceDurationOverPeriod = currentData.allComponents[this.componentId + "/NegativePriceDuration"];
    }
}
