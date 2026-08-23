import { Component } from "@angular/core";
import { AbstractFlatWidget } from "src/app/shared/components/flat/abstract-flat-widget";
import { SetChannelValueRequest } from "src/app/shared/jsonrpc/request/setChannelValueRequest";
import { ChannelAddress, CurrentData } from "src/app/shared/shared";

/**
 * Small, standalone tile - not part of the shared, generic Evcs live widget
 * (which every Evcs implementation uses) - specifically for the
 * Evcs.Goe.Gemini.Managed phase-control mode, matched by Factory-ID in
 * live.component.html rather than by the generic Evcs Nature. See
 * io.openems.edge.evcs.goe.geminimanaged/readme.adoc for why this is a
 * separate component instead of extending the shared Evcs widget.
 *
 * <p>
 * Writes {@code SetPhaseControlMode}, NOT {@code SetPhaseSwitchMode} directly
 * - the Component itself is the sole writer of the latter (raw go-e 'psm'
 * value), so a manual "force 1-phase"/"force 3-phase" choice here and its own
 * PV-surplus-based 'Automatic' logic can never race each other.
 */
@Component({
    selector: "Evcs_Goe_Gemini_Managed",
    templateUrl: "./flat.html",
    standalone: false,
})
export class FlatComponent extends AbstractFlatWidget {

    protected static readonly AUTOMATIC = 0;
    protected static readonly FORCE_1_PHASE = 1;
    protected static readonly FORCE_3_PHASE = 2;

    protected currentPhaseControlMode: number | null = null;
    protected pending: boolean = false;

    protected override getChannelAddresses(): ChannelAddress[] {
        return [
            new ChannelAddress(this.component.id, "PhaseControlMode"),
        ];
    }

    protected override onCurrentData(currentData: CurrentData): void {
        this.currentPhaseControlMode = currentData.allComponents[this.component.id + "/PhaseControlMode"] ?? null;
    }

    protected setPhaseControlMode(mode: number) {
        this.pending = true;
        this.edge.sendRequest(this.websocket, new SetChannelValueRequest({
            componentId: this.component.id,
            channelId: "SetPhaseControlMode",
            value: mode,
        })).catch(reason => {
            console.error(reason);
        }).finally(() => {
            this.pending = false;
        });
    }
}
