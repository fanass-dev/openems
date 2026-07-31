// @ts-strict-ignore
import { Component, ViewChild } from "@angular/core";
import { TranslateService } from "@ngx-translate/core";
import { BaseChartDirective } from "ng2-charts";
import { AbstractHistoryChart } from "src/app/shared/components/chart/abstracthistorychart";
import { ChartConstants } from "src/app/shared/components/chart/chart.constants";
import { EvcsComponent } from "src/app/shared/components/edge/config-components/evcs/evcsComponent";
import { ViewUtils } from "src/app/shared/components/navigation/view/shared/shared";
import { QueryHistoricTimeseriesEnergyResponse } from "src/app/shared/jsonrpc/response/queryHistoricTimeseriesEnergyResponse";
import { ChannelAddress, Edge, EdgeConfig, Utils } from "src/app/shared/shared";
import { NumberUtils } from "src/app/shared/utils/number/number-utils";
import { ChartAxis, HistoryUtils, YAxisType } from "src/app/shared/utils/utils";

@Component({
    selector: "energychart",
    templateUrl: "../../../../../shared/components/chart/abstracthistorychart.html",
    standalone: false,
})
export class ChartComponent extends AbstractHistoryChart {
    @ViewChild(BaseChartDirective) private chart?: BaseChartDirective;

    public static getChartData(config: EdgeConfig | null, chartType: "line" | "bar", translate: TranslateService, edge: Edge | null): HistoryUtils.ChartData {
        // Individually metered consumption loads (EVCS, heat components, plain
        // consumption meters like a heat-pump's dedicated meter) - if any are
        // configured, the aggregate "Consumption" is broken down into one
        // dataset per load plus a "GENERAL.OTHER_CONSUMPTION" remainder,
        // instead of a single combined bar/line. Mirrors the same breakdown
        // already used by the Consumption widget's own detail chart
        // (io.openems.edge.live.common.consumption.history.chart/chart.ts).
        const evcsComponents: EvcsComponent[] = config ? EvcsComponent.getComponents(config, edge) : [];
        const heatComponents: EdgeConfig.Component[] = config
            ?.getComponentsImplementingNature("io.openems.edge.heat.api.Heat")
            .filter(component =>
                !(component.factoryId === "Controller.Heat.Heatingelement") && !component.isEnabled === false) ?? [];
        const consumptionMeters: EdgeConfig.Component[] = config
            ?.getComponentsImplementingNature("io.openems.edge.meter.api.ElectricityMeter")
            .filter(component => {
                const natureIds = config?.getNatureIdsByFactoryId(component.factoryId);
                const isEvcs = natureIds.includes("io.openems.edge.evcs.api.Evcs");
                const isHeat = natureIds.includes("io.openems.edge.heat.api.Heat");
                return component.isEnabled && config?.isTypeConsumptionMetered(component) //
                    && isEvcs === false && isHeat === false;
            }) ?? [];
        const hasConsumptionBreakdown = evcsComponents.length > 0 || heatComponents.length > 0 //
            || consumptionMeters.length > 0;

        const input: HistoryUtils.InputChannel[] =
            config?.widgets.classes.reduce((arr: HistoryUtils.InputChannel[], key) => {
                const newObj = [];
                switch (key) {
                    case "Energymonitor":
                    case "Consumption":
                        newObj.push({
                            name: "Consumption",
                            powerChannel: new ChannelAddress("_sum", "ConsumptionActivePower"),
                            energyChannel: new ChannelAddress("_sum", "ConsumptionActiveEnergy"),
                        });
                        if (hasConsumptionBreakdown) {
                            newObj.push(...evcsComponents.map(evcs => evcs.getChartInputChannel()));
                            newObj.push(...heatComponents.map(component => ({
                                name: component.id + "/ActivePower",
                                powerChannel: new ChannelAddress(component.id, "ActivePower"),
                                energyChannel: new ChannelAddress(component.id, "ActiveProductionEnergy"),
                            })));
                            newObj.push(...consumptionMeters.map(meter => ({
                                name: meter.id + "/ActivePower",
                                powerChannel: ChannelAddress.fromString(meter.id + "/ActivePower"),
                                energyChannel: ChannelAddress.fromString(meter.id + "/ActiveProductionEnergy"),
                            })));
                        }
                        break;
                    case "Common_Autarchy":
                    case "Grid":
                        newObj.push({
                            name: "GridBuy",
                            powerChannel: new ChannelAddress("_sum", "GridActivePower"),
                            energyChannel: new ChannelAddress("_sum", "GridBuyActiveEnergy"),
                            ...(chartType === "line" && { converter: HistoryUtils.ValueConverter.NEGATIVE_AS_ZERO }),
                        }, {
                            name: "GridSell",
                            powerChannel: new ChannelAddress("_sum", "GridActivePower"),
                            energyChannel: new ChannelAddress("_sum", "GridSellActiveEnergy"),
                            ...(chartType === "line" && { converter: HistoryUtils.ValueConverter.POSITIVE_AS_ZERO_AND_INVERT_NEGATIVE }),
                        });
                        break;
                    case "Storage":
                        newObj.push({
                            name: "EssSoc",
                            powerChannel: new ChannelAddress("_sum", "EssSoc"),
                        }, {
                            name: "EssCharge",
                            powerChannel: new ChannelAddress("_sum", "EssActivePower"),
                            energyChannel: new ChannelAddress("_sum", "EssDcChargeEnergy"),
                        }, {
                            name: "EssDischarge",
                            powerChannel: new ChannelAddress("_sum", "EssActivePower"),
                            energyChannel: new ChannelAddress("_sum", "EssDcDischargeEnergy"),
                        });
                        break;
                    case "Common_Selfconsumption":
                    case "Common_Production":
                        newObj.push({
                            name: "ProductionActivePower",
                            powerChannel: new ChannelAddress("_sum", "ProductionActivePower"),
                            energyChannel: new ChannelAddress("_sum", "ProductionActiveEnergy"),
                        }, {
                            name: "ProductionDcActual",
                            powerChannel: new ChannelAddress("_sum", "ProductionDcActualPower"),
                            energyChannel: new ChannelAddress("_sum", "ProductionActiveEnergy"),
                        });
                        break;
                }

                arr.push(...newObj);
                return arr;
            }, []);

        return {
            input: input,
            output: (data: HistoryUtils.ChannelData): HistoryUtils.DisplayValue[] => {
                return [
                    {
                        name: translate.instant("GENERAL.PRODUCTION"),
                        nameSuffix: (energyValues: QueryHistoricTimeseriesEnergyResponse) => energyValues.result.data["_sum/ProductionActiveEnergy"],
                        converter: () => data["ProductionActivePower"],
                        color: ChartConstants.Colors.GREEN,
                        stack: 0,
                        hiddenOnInit: chartType == "line" ? false : true,
                        order: 1,
                    },

                    // DirectConsumption, displayed in stack 1 & 2, only one legenItem
                    ...[chartType === "bar" && {
                        name: translate.instant("GENERAL.DIRECT_CONSUMPTION"),
                        nameSuffix: (energyValues: QueryHistoricTimeseriesEnergyResponse) => {
                            return Utils.subtractSafely(energyValues.result.data["_sum/ProductionActiveEnergy"], energyValues.result.data["_sum/GridSellActiveEnergy"], energyValues.result.data["_sum/EssDcChargeEnergy"]);
                        },
                        converter: () =>
                            data["ProductionActivePower"]?.map((value, index) => Utils.subtractSafely(value, data["GridSell"][index], data["EssCharge"][index]))
                                ?.map(value => HistoryUtils.ValueConverter.NEGATIVE_AS_ZERO(value)),
                        color: ChartConstants.Colors.ORANGE,
                        stack: [1, 2],
                        order: 2,
                    }],

                    // Charge Power - same color as Discharge (both "storage", yellow)
                    {
                        name: translate.instant("GENERAL.CHARGE"),
                        nameSuffix: (energyValues: QueryHistoricTimeseriesEnergyResponse) => energyValues.result.data["_sum/EssDcChargeEnergy"],
                        converter: () => chartType === "line" //
                            ? data["EssCharge"]?.map((value, index) => {
                                return HistoryUtils.ValueConverter.POSITIVE_AS_ZERO_AND_INVERT_NEGATIVE(Utils.subtractSafely(value, data["ProductionDcActual"]?.[index]));
                            }) : data["EssCharge"],
                        color: ChartConstants.Colors.YELLOW,
                        stack: 1,
                        ...(chartType === "line" && { order: 6 }),
                    },

                    // Discharge Power - drawn as negative (below the zero line), while
                    // nameSuffix (legend/tooltip sum) keeps reporting the plain positive
                    // energy value; this is purely a display-side sign flip. Same color
                    // as Charge (both "storage", yellow).
                    {
                        name: translate.instant("GENERAL.DISCHARGE"),
                        nameSuffix: (energyValues: QueryHistoricTimeseriesEnergyResponse) => energyValues.result.data["_sum/EssDcDischargeEnergy"],
                        converter: () => {
                            const values = chartType === "line" ?
                                data["EssDischarge"]?.map((value, index) => {
                                    return HistoryUtils.ValueConverter.NEGATIVE_AS_ZERO(Utils.subtractSafely(value, data["ProductionDcActual"]?.[index]));
                                }) : data["EssDischarge"];
                            return values?.map(value => Utils.multiplySafely(value, -1));
                        },
                        color: ChartConstants.Colors.YELLOW,
                        stack: 2,
                        ...(chartType === "line" && { order: 5 }),
                    },

                    // Sell to grid - drawn as negative (below the zero line), while
                    // nameSuffix (legend/tooltip sum) keeps reporting the plain positive
                    // energy value; this is purely a display-side sign flip. Same color
                    // as Buy from Grid (both "grid", red).
                    {
                        name: translate.instant("GENERAL.GRID_SELL_ADVANCED"),
                        nameSuffix: (energyValues: QueryHistoricTimeseriesEnergyResponse) => energyValues.result.data["_sum/GridSellActiveEnergy"],
                        converter: () => data["GridSell"]?.map(value => Utils.multiplySafely(value, -1)),
                        color: ChartConstants.Colors.RED,
                        stack: 1,
                        ...(chartType === "line" && { order: 4 }),
                    },

                    // Buy from Grid - same color as Sell to grid (both "grid", red).
                    {
                        name: translate.instant("GENERAL.GRID_BUY_ADVANCED"),
                        nameSuffix: (energyValues: QueryHistoricTimeseriesEnergyResponse) => energyValues.result.data["_sum/GridBuyActiveEnergy"],
                        converter: () => data["GridBuy"],
                        color: ChartConstants.Colors.RED,
                        stack: 2,
                        ...(chartType === "line" && { order: 2 }),
                    },

                    // Consumption - either a single aggregate dataset (no individually
                    // metered loads configured), or broken down into one dataset per
                    // load plus a "Sonstiger Verbrauch"/"Other" remainder, with the
                    // aggregate kept as a hidden-by-default reference on its own stack.
                    ...(!hasConsumptionBreakdown ? [{
                        name: translate.instant("GENERAL.CONSUMPTION"),
                        nameSuffix: (energyValues: QueryHistoricTimeseriesEnergyResponse) => energyValues.result.data["_sum/ConsumptionActiveEnergy"],
                        converter: () => data["Consumption"],
                        color: ChartConstants.Colors.PURPLE,
                        stack: 3,
                        hiddenOnInit: chartType == "line" ? false : true,
                        ...(chartType === "line" && { order: 0 }),
                    } as HistoryUtils.DisplayValue] : [
                        {
                            name: translate.instant("GENERAL.CONSUMPTION"),
                            nameSuffix: (energyValues: QueryHistoricTimeseriesEnergyResponse) => energyValues.result.data["_sum/ConsumptionActiveEnergy"],
                            converter: () => data["Consumption"],
                            color: ChartConstants.Colors.PURPLE,
                            stack: 4,
                            hiddenOnInit: true,
                            ...(chartType === "line" && { order: 0 }),
                        } as HistoryUtils.DisplayValue,
                        ...evcsComponents.map((evcs, index) => ({
                            name: evcs.alias,
                            nameSuffix: (energyValues: QueryHistoricTimeseriesEnergyResponse) => energyValues?.result.data[evcs.energyChannel.toString()],
                            converter: () => data[evcs.powerChannel.toString()] ?? null,
                            color: ChartConstants.Colors.BLUE,
                            stack: 3,
                        } as HistoryUtils.DisplayValue)),
                        // Offset by +1 into SHADES_OF_GREEN so its first shade doesn't
                        // exactly collide with Production's flat GREEN above.
                        ...heatComponents.map((component, index) => ({
                            name: component.alias,
                            nameSuffix: (energyValues: QueryHistoricTimeseriesEnergyResponse) => energyValues?.result.data[component.id + "/ActiveProductionEnergy"],
                            converter: () => data[component.id + "/ActivePower"] ?? null,
                            color: ChartConstants.Colors.SHADES_OF_GREEN[(index + 1) % ChartConstants.Colors.SHADES_OF_GREEN.length],
                            stack: 3,
                        } as HistoryUtils.DisplayValue)),
                        ...consumptionMeters.map((meter, index) => ({
                            name: meter.alias,
                            nameSuffix: (energyValues: QueryHistoricTimeseriesEnergyResponse) => energyValues?.result.data[meter.id + "/ActiveProductionEnergy"],
                            converter: () => data[meter.id + "/ActivePower"] ?? null,
                            color: ChartConstants.Colors.TURQUOISE,
                            stack: 3,
                        } as HistoryUtils.DisplayValue)),
                        {
                            name: translate.instant("GENERAL.OTHER_CONSUMPTION"),
                            nameSuffix: (energyValues: QueryHistoricTimeseriesEnergyResponse) =>
                                Utils.calculateOtherConsumptionTotal(energyValues, evcsComponents, heatComponents, consumptionMeters),
                            converter: () => data["Consumption"]?.map((value, index) => {
                                if (value == null) {
                                    return null;
                                }
                                let other = value;
                                evcsComponents.forEach(evcs => {
                                    other = Utils.subtractSafely(other, data[evcs.powerChannel.toString()]?.[index]);
                                });
                                heatComponents.forEach(component => {
                                    other = Utils.subtractSafely(other, data[component.id + "/ActivePower"]?.[index]);
                                });
                                consumptionMeters.forEach(meter => {
                                    other = Utils.subtractSafely(other, data[meter.id + "/ActivePower"]?.[index]);
                                });
                                return Utils.roundSlightlyNegativeValues(other);
                            }),
                            color: ChartConstants.Colors.GREY,
                            stack: 3,
                        } as HistoryUtils.DisplayValue,
                    ]),
                    ...(chartType === "line" ?
                        [{
                            name: translate.instant("GENERAL.SOC"),
                            converter: () => data["EssSoc"]?.map(value => Utils.multiplySafely(value, 1000)),
                            color: "rgb(189, 195, 199)",
                            borderDash: [10, 10],
                            yAxisId: ChartAxis.RIGHT,
                            stack: 1,
                        } as HistoryUtils.DisplayValue] : []),
                ];
            },
            tooltip: {
                formatNumber: "1.0-2",
                afterTitle: (stack: string) => {

                    if (chartType === "bar") {
                        if (stack === "1") {
                            return translate.instant("GENERAL.PRODUCTION");
                        } else if (stack === "2") {
                            return translate.instant("GENERAL.CONSUMPTION");
                        }
                    }
                    return null;
                },
            },
            yAxes: [

                // Left YAxis
                {
                    unit: YAxisType.ENERGY,
                    position: "left",
                    yAxisId: ChartAxis.LEFT,
                },

                // Right Yaxis, only shown for line-chart
                ...(chartType === "line" ? [{
                    unit: YAxisType.PERCENTAGE,
                    customTitle: "%",
                    position: "right" as const,
                    yAxisId: ChartAxis.RIGHT,
                    displayGrid: false,
                }] : []),
            ],
            normalizeOutputData: true,
        };
    }

    public override getChartData() {
        return ChartComponent.getChartData(this.config, this.chartType, this.translate, this.edge);
    }

    public override ngAfterViewInit(): void {
        setTimeout(() => {
            this.viewHeight = NumberUtils.divideSafely(ViewUtils.getChartContentHeightInVh(window.innerHeight, this.navigationService.position()), 2);
            this.chart?.chart?.resize();
            this.chart?.update();
        }, 100);
    }
}
