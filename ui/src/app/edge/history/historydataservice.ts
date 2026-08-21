// @ts-strict-ignore
import { Inject, Injectable } from "@angular/core";

import { ChartConstants } from "src/app/shared/components/chart/chart.constants";
import { QueryHistoricTimeseriesEnergyRequest } from "src/app/shared/jsonrpc/request/queryHistoricTimeseriesEnergyRequest";
import { Service } from "src/app/shared/service/service";
import { Websocket } from "src/app/shared/service/websocket";
import { DateUtils } from "src/app/shared/utils/date/dateutils";
import { DataService } from "../../shared/components/shared/dataservice";
import { QueryHistoricTimeseriesEnergyResponse } from "../../shared/jsonrpc/response/queryHistoricTimeseriesEnergyResponse";
import { ChannelAddress, Edge } from "../../shared/shared";

@Injectable()
export class HistoryDataService extends DataService {

    public queryChannelsTimeout: ReturnType<typeof setTimeout> | null = null;
    protected override timestamps: string[] = [];
    private activeQueryData: string;
    private channelAddresses: { [sourceId: string]: ChannelAddress } = {};

    constructor(
        @Inject(Websocket) protected websocket: Websocket,
        @Inject(Service) protected service: Service,
    ) {
        super(service);
    }
    public subscribeChannels(channelAddresses: ChannelAddress[], edge: Edge, componentId: string) {

        for (const channelAddress of channelAddresses) {
            this.channelAddresses[channelAddress.toString()] = channelAddress;
        }

        this.scheduleQuery(edge);
    }

    public override unsubscribeFromChannels(channels: ChannelAddress[]) {
        return;
    }

    public override refresh(ev: CustomEvent) {
        this.subscribeChannels(Object.values(this.channelAddresses), this.edge, "");
        setTimeout(() => {
            (ev.target as HTMLIonRefresherElement).complete();
        }, 1000);
    }

    /**
     * Plant eine gebündelte Abfrage aller aktuell in {@link channelAddresses} gesammelten
     * Kanäle - sofern nicht bereits eine Anfrage läuft. Wird beim Abschluss einer Anfrage
     * automatisch erneut aufgerufen, falls währenddessen neue Kanäle hinzugekommen sind,
     * damit diese nicht dauerhaft "gestrandet" bleiben (siehe Analyse zum Sonstiges/NaN-Bug).
     */
    private scheduleQuery(edge: Edge) {

        if (this.queryChannelsTimeout != null) {
            // Es läuft bereits ein Debounce-Timeout oder eine Anfrage - die soeben über
            // subscribeChannels() hinzugefügten Kanäle liegen bereits in this.channelAddresses
            // und werden automatisch von der aktuell laufenden bzw. der nächsten Runde erfasst.
            return;
        }

        this.queryChannelsTimeout = setTimeout(() => {
            if (Object.entries(this.channelAddresses).length > 0) {

                // Schnappschuss: welche Kanäle nehmen wir JETZT tatsächlich in die Anfrage auf?
                const requestedKeys = Object.keys(this.channelAddresses);

                this.service.historyPeriod.subscribe(date => {

                    const request = new QueryHistoricTimeseriesEnergyRequest(
                        DateUtils.maxDate(date.from, edge?.firstSetupProtocol),
                        date.to,
                        Object.values(this.channelAddresses),
                    );

                    this.activeQueryData = request.id;

                    edge.sendRequest(this.websocket, request)
                        .then((response) => {
                            if (this.activeQueryData === response.id) {
                                const allComponents = {};
                                const result = (response as QueryHistoricTimeseriesEnergyResponse).result;

                                for (const [key, value] of Object.entries(result.data)) {
                                    allComponents[key] = value;
                                }

                                this.currentValue.set({ allComponents: allComponents });
                                this.timestamps = response.result["timestamps"] ?? [];
                            }
                        })
                        .catch(err => console.warn(err))
                        .finally(() => {
                            this.queryChannelsTimeout = null;

                            // Waren waehrend des Server-Roundtrips (zwischen Anfrage-Aufbau oben
                            // und diesem finally()) weitere Kanaele per subscribeChannels()
                            // hinzugekommen, die NICHT Teil von requestedKeys waren, wuerden sie
                            // ohne diesen Check dauerhaft gestrandet bleiben - solange kein
                            // anderes Widget zufaellig nochmal subscribeChannels() aufruft.
                            // Stattdessen sofort eine weitere Runde fuer genau diese Differenz
                            // anstossen.
                            const currentKeys = Object.keys(this.channelAddresses);
                            const hasStrandedChannels = currentKeys.some(key => !requestedKeys.includes(key));
                            if (hasStrandedChannels) {
                                this.scheduleQuery(edge);
                            }
                        });
                });
            }
        }, ChartConstants.REQUEST_TIMEOUT);
    }
}
