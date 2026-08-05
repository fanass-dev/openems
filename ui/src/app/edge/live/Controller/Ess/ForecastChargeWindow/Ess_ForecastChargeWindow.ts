import { NgModule } from "@angular/core";
import { BrowserModule } from "@angular/platform-browser";
import { ModalModule } from "src/app/shared/components/modal/modal.module";
import { SharedModule } from "src/app/shared/shared.module";
import { FlatComponent } from "./flat/flat";
import { ModalComponent } from "./modal/modal";
import { PriceChartComponent } from "./modal/priceChart";

@NgModule({
    imports: [
        BrowserModule,
        SharedModule,
        ModalModule,
    ],
    declarations: [
        ModalComponent,

        FlatComponent,
        PriceChartComponent,
    ],
    exports: [
        FlatComponent,
    ],
})
export class Controller_Ess_ForecastChargeWindow { }
