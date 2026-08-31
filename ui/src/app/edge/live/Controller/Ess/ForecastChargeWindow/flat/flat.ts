import { Component, OnInit } from "@angular/core";
import { AbstractFlatWidget } from "src/app/shared/components/flat/abstract-flat-widget";
import { Modal } from "src/app/shared/components/flat/flat";
import { ChannelAddress, CurrentData, Utils } from "src/app/shared/shared";
import { Icon } from "src/app/shared/type/widget";

import { ModalComponent } from "../modal/modal";

@Component({
    selector: "Controller_Ess_ForecastChargeWindow",
    templateUrl: "./flat.html",
    standalone: false,
})
export class FlatComponent extends AbstractFlatWidget implements OnInit {

    /** Summary-row badge backgrounds - mirrors modal.ts, see field comment below. */
    private static readonly NEUTRAL_BADGE_BG = "rgba(128,128,128,0.12)";
    private static readonly WARNING_BADGE_BG = "rgba(255,196,0,0.16)";
    private static readonly DANGER_BADGE_BG = "rgba(200,30,30,0.14)";
    private static readonly SUCCESS_BADGE_BG = "rgba(51,102,0,0.14)";

    protected priceWithCurrency: string = "-";
    protected blockStatus: string = "-";
    protected blockStatusIcon: Icon = { name: "help-outline", color: "medium", size: "small" };
    protected modalComponent: Modal | null = null;

    /** Summary-row icons - same at-a-glance breakdown (time window / forecast / price)
     * shown in the modal's 'Letzte Entscheidung' row, duplicated here for the tile since
     * flat.ts and modal.ts are independent components with their own Channel subscriptions. */
    protected withinTimeWindowIcon: Icon = { name: "time-outline", color: "medium", size: "small" };
    protected withinTimeWindowBadgeBg: string = FlatComponent.NEUTRAL_BADGE_BG;
    protected forecastIcon: Icon = { name: "help-circle-outline", color: "medium", size: "small" };
    protected forecastBadgeBg: string = FlatComponent.NEUTRAL_BADGE_BG;
    protected forecastedWhText: string | null = null;
    protected priceIconColor: string = "var(--ion-color-medium)";
    protected priceBadgeBg: string = FlatComponent.NEUTRAL_BADGE_BG;

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
            new ChannelAddress(this.component.id, "WithinTimeWindow"),
            new ChannelAddress(this.component.id, "ForecastLiftedToday"),
            new ChannelAddress(this.component.id, "ForecastedAfternoonProduction"),
            new ChannelAddress(this.component.id, "PriceCurrentlyNegative"),
        ];
    }

    protected override onCurrentData(currentData: CurrentData): void {
        const price = currentData.allComponents[this.component.id + "/CurrentGridSellPrice"];
        this.priceWithCurrency = Utils.CONVERT_PRICE_TO_CENT_PER_KWH(2, "Ct/kWh")(price);

        const blocked = currentData.allComponents[this.component.id + "/CurrentlyBlocked"];
        this.blockStatus = this.translate.instant(blocked === 1
            ? "EDGE.INDEX.WIDGETS.FORECAST_CHARGE_WINDOW.BLOCKED"
            : "EDGE.INDEX.WIDGETS.FORECAST_CHARGE_WINDOW.UNBLOCKED");
        this.blockStatusIcon = blocked === 1
            ? { name: "lock-closed-outline", color: "danger", size: "small" }
            : { name: "lock-open-outline", color: "success", size: "small" };

        const withinTimeWindow = currentData.allComponents[this.component.id + "/WithinTimeWindow"];
        this.withinTimeWindowIcon = withinTimeWindow === 1
            ? { name: "time-outline", color: "dark", size: "small" }
            : { name: "time-outline", color: "medium", size: "small" };

        const forecastedWh = currentData.allComponents[this.component.id + "/ForecastedAfternoonProduction"];
        const forecastLiftedToday = currentData.allComponents[this.component.id + "/ForecastLiftedToday"];
        if (forecastedWh == null) {
            this.forecastIcon = { name: "help-circle-outline", color: "medium", size: "small" };
            this.forecastBadgeBg = FlatComponent.NEUTRAL_BADGE_BG;
            this.forecastedWhText = null;
        } else if (forecastLiftedToday === 1) {
            this.forecastIcon = { name: "cloudy-outline", color: "medium", size: "small" };
            this.forecastBadgeBg = FlatComponent.NEUTRAL_BADGE_BG;
            this.forecastedWhText = Utils.CONVERT_TO_KILO_WATTHOURS(forecastedWh);
        } else {
            this.forecastIcon = { name: "sunny-outline", color: "warning", size: "small" };
            this.forecastBadgeBg = FlatComponent.WARNING_BADGE_BG;
            this.forecastedWhText = Utils.CONVERT_TO_KILO_WATTHOURS(forecastedWh);
        }

        const priceCurrentlyNegative = currentData.allComponents[this.component.id + "/PriceCurrentlyNegative"];
        this.priceIconColor = priceCurrentlyNegative === 1
            ? "var(--ion-color-danger)"
            : "var(--ion-color-success)";
        this.priceBadgeBg = priceCurrentlyNegative === 1
            ? FlatComponent.DANGER_BADGE_BG
            : FlatComponent.SUCCESS_BADGE_BG;
    }
}
