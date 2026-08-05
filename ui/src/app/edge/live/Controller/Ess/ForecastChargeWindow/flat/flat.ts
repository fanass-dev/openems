import { Component, OnInit } from "@angular/core";
import { AbstractFlatWidget } from "src/app/shared/components/flat/abstract-flat-widget";
import { Modal } from "src/app/shared/components/flat/flat";
import { ChannelAddress, CurrentData, Utils } from "src/app/shared/shared";

import { ModalComponent } from "../modal/modal";

@Component({
    selector: "Controller_Ess_ForecastChargeWindow",
    templateUrl: "./flat.html",
    standalone: false,
})
export class FlatComponent extends AbstractFlatWidget implements OnInit {

    protected priceWithCurrency: string = "-";
    protected blockStatus: string = "-";
    protected modalComponent: Modal | null = null;

    protected override afterIsInitialized(): void {
        this.modalComponent = this.getModalComponent();
    }

    protected getModalComponent(): Modal {
        return {
            component: ModalComponent,
            componentProps: {
                component: this.component,
            },
        };
    }

    protected override getChannelAddresses(): ChannelAddress[] {
        return [
            new ChannelAddress(this.component.id, "CurrentGridSellPrice"),
            new ChannelAddress(this.component.id, "CurrentlyBlocked"),
        ];
    }

    protected override onCurrentData(currentData: CurrentData): void {
        const price = currentData.allComponents[this.component.id + "/CurrentGridSellPrice"];
        this.priceWithCurrency = Utils.CONVERT_PRICE_TO_CENT_PER_KWH(2, "Ct/kWh")(price);

        const blocked = currentData.allComponents[this.component.id + "/CurrentlyBlocked"];
        this.blockStatus = this.translate.instant(blocked === 1
            ? "EDGE.INDEX.WIDGETS.FORECAST_CHARGE_WINDOW.BLOCKED"
            : "EDGE.INDEX.WIDGETS.FORECAST_CHARGE_WINDOW.UNBLOCKED");
    }
}
