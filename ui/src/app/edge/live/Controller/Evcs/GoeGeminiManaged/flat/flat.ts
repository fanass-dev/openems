import { Component } from "@angular/core";
import { AbstractFlatWidget } from "src/app/shared/components/flat/abstract-flat-widget";
import { SetChannelValueRequest } from "src/app/shared/jsonrpc/request/setChannelValueRequest";
import { ChannelAddress, CurrentData } from "src/app/shared/shared";

/**
 * Small, standalone tile - not part of the shared, generic Evcs live widget
 * (which every Evcs implementation uses) - specifically for the
 * Evcs.Goe.Gemini.Managed phase-switch control ('psm'), matched by Factory-ID
 * in live.component.html rather than by the generic Evcs Nature. See
 * io.openems.edge.evcs.goe.geminimanaged/readme.adoc for why this is a
 * separate component instead of extending the shared Evcs widget.
 */
@Component({
    selector: "Evcs_Goe_Gemini_Managed",
    templateUrl: "./flat.html",
    standalone: false,
})
export class FlatComponent extends AbstractFlatWidget {

    protected static readonly FORCE_1_PHASE = 1;
    protected static readonly FORCE_3_PHASE = 2;

    protected currentPhaseSwitchMode: number | null = null;
    protected pending: boolean = false;

    protected override getChannelAddresses(): ChannelAddress[] {
        return [
            new ChannelAddress(this.component.id, "PhaseSwitchMode"),
        ];
    }

    protected override onCurrentData(currentData: CurrentData): void {
        this.currentPhaseSwitchMode = currentData.allComponents[this.component.id + "/PhaseSwitchMode"] ?? null;
    }

    protected setPhaseSwitchMode(mode: number) {
        this.pending = true;
        this.edge.sendRequest(this.websocket, new SetChannelValueRequest({
            componentId: this.component.id,
            channelId: "SetPhaseSwitchMode",
            value: mode,
        })).catch(reason => {
            console.error(reason);
        }).finally(() => {
            this.pending = false;
        });
    }
}
