import { NgModule } from "@angular/core";
import { ChannelThreshold } from "./ChannelThreshold/channelThreshold.module";
import { EnerixControl } from "./EnerixControl/enerixControl.module";
import { ControllerEss } from "./Ess/ess.module";
import { ForecastChargeWindow } from "./Ess/ForecastChargeWindow/forecastChargeWindow.module";
import { GridOptimizeCharge } from "./Ess/GridoptimizedCharge/gridOptimizeCharge.module";
import { TimeOfUseTariff } from "./Ess/TimeOfUseTariff/timeOfUseTariff.module";
import { ControllerHeat } from "./Heat/heat.module";
import { ControllerIo } from "./Io/Io.module";
import { ModbusTcpApi } from "./ModbusTcpApi/modbusTcpApi.module";

@NgModule({
    imports: [
        ControllerEss,
        ControllerHeat,
        ControllerIo,
        ChannelThreshold,
        EnerixControl,
        TimeOfUseTariff,
        ModbusTcpApi,
        GridOptimizeCharge,
        ForecastChargeWindow,
    ],
    exports: [
        ControllerEss,
        ControllerHeat,
        ControllerIo,
        ChannelThreshold,
        EnerixControl,
        TimeOfUseTariff,
        ModbusTcpApi,
        GridOptimizeCharge,
        ForecastChargeWindow,
    ],
})
export class Controller { }
