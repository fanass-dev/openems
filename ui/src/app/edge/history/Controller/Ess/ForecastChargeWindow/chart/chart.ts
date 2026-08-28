// @ts-strict-ignore
import { ChangeDetectorRef, Component } from "@angular/core";
import { ActivatedRoute } from "@angular/router";
import { TranslateService } from "@ngx-translate/core";
import * as Chart from "chart.js";
import { calculateResolution, ChronoUnit, Resolution } from "src/app/edge/history/shared";
import { AbstractHistoryChart } from "src/app/shared/components/chart/abstracthistorychart";
import { ChartConstants } from "src/app/shared/components/chart/chart.constants";
import { NavigationService } from "src/app/shared/components/navigation/service/navigation.service";
import { ChannelAddress, Logger, Service } from "src/app/shared/shared";
import { ChartAxis, HistoryUtils, YAxisType } from "src/app/shared/utils/utils";

@Component({
    selector: "forecastChargeWindowChart",
    templateUrl: "../../../../../../shared/components/chart/abstracthistorychart.html",
    standalone: false,
})
export class ChartComponent extends AbstractHistoryChart {

    constructor(
        public override service: Service,
        public override cdRef: ChangeDetectorRef,
        protected override translate: TranslateService,
        protected override route: ActivatedRoute,
        protected override logger: Logger,
        protected override navigationService: NavigationService,
    ) {
        super(service, cdRef, translate, route, logger, navigationService);
    }

    protected override getChartData(): HistoryUtils.ChartData {
        const componentId: string = this.config.getComponentIdsByFactory("Controller.Ess.ForecastChargeWindow")[0];
        this.component = this.config.components[componentId];
        this.chartType = "bar";

        return {
            input: [
                {
                    name: "GridSellPrice",
                    powerChannel: ChannelAddress.fromString(this.component.id + "/CurrentGridSellPrice"),
                },
                {
                    name: "CurrentlyBlocked",
                    powerChannel: ChannelAddress.fromString(this.component.id + "/CurrentlyBlocked"),
                },
            ],
            output: (data: HistoryUtils.ChannelData) => {
                return [
                    {
                        name: this.translate.instant("EDGE.INDEX.WIDGETS.FORECAST_CHARGE_WINDOW.PRICE") + " (Ct/kWh)",
                        // fillChart() divides every channel value by 1000 (built for Watt -> kW),
                        // which does not apply to a currency channel - undo it (*1000), then convert
                        // from the channel's EUR/MWh to the ct/kWh that YAxisType.CURRENCY assumes (/10).
                        converter: () => data["GridSellPrice"]?.map(value => value == null ? null : value * 100),
                        color: "rgb(51,102,0)",
                        custom: {
                            type: "bar",
                            formatNumber: ChartConstants.NumberFormat.TWO,
                        },
                    },
                    {
                        name: this.translate.instant("EDGE.INDEX.WIDGETS.FORECAST_CHARGE_WINDOW.BLOCKED"),
                        // Same /1000-undo as above - CurrentlyBlocked is a boolean (0/1) channel,
                        // not a power value, so fillChart()'s implicit Watt -> kW conversion has to
                        // be reversed and the result thresholded back to a clean 0/1 for the
                        // RELAY y-axis (On/Off ticks, see AbstractHistoryChart.getYAxisOptions).
                        converter: () => data["CurrentlyBlocked"]?.map(value => value == null ? null : (value > 0 ? 1 : 0)),
                        color: "rgb(200,30,30)",
                        yAxisId: ChartAxis.RIGHT,
                        custom: {
                            type: "line",
                        },
                    },
                ];
            },
            tooltip: {
                formatNumber: ChartConstants.NumberFormat.TWO,
            },
            yAxes: [
                {
                    unit: YAxisType.CURRENCY,
                    position: "left",
                    yAxisId: ChartAxis.LEFT,
                    scale: {
                        dynamicScale: true,
                    },
                },
                {
                    unit: YAxisType.RELAY,
                    position: "right",
                    yAxisId: ChartAxis.RIGHT,
                },
            ],
        };
    }

    protected override async loadChart() {
        this.labels = [];
        this.errorResponse = null;

        const unit: Resolution = { unit: ChronoUnit.Type.MINUTES, value: 15 };
        this.queryHistoricTimeseriesData(this.service.historyPeriod.value.from, this.service.historyPeriod.value.to, unit)
            .then((dataResponse) => {
                this.chartObject = this.getChartData();

                const displayValues = AbstractHistoryChart.fillChart(this.chartType, this.chartObject, dataResponse);
                this.datasets = displayValues.datasets;
                this.colorizeNegativePrices(this.datasets[0]);
                this.legendOptions = displayValues.legendOptions;
                this.labels = displayValues.labels;
                this.setChartLabel();

                this.chartObject.yAxes.forEach((element) => {
                    this.options = AbstractHistoryChart.getYAxisOptions(this.options, element, this.translate, this.chartType, this.datasets, true, this.chartObject.tooltip.formatNumber);
                });

                this.options.scales.x["time"].unit = calculateResolution(this.service, this.service.historyPeriod.value.from, this.service.historyPeriod.value.to).timeFormat;
                this.options.scales.x.ticks["source"] = "auto";
                this.options.scales.x.grid = { offset: false };
                this.options.plugins.tooltip.mode = "index";
                this.options.scales.x.ticks.maxTicksLimit = 30;
                this.options.scales.x["bounds"] = "ticks";
                this.options.scales.x["offset"] = false;
                this.options["animation"] = false;
            });
    }

    /**
     * The shared fillChart()/AbstractHistoryChart.getColors() pipeline only
     * supports one static color per dataset (getChartData()'s 'color'
     * field) - override it here with one color per bar (Chart.js accepts a
     * color array indexed like the data array), red for negative day-ahead
     * prices and the usual green otherwise, matching the Live-page's
     * day-ahead price chart (priceChart.ts).
     */
    private colorizeNegativePrices(dataset: Chart.ChartDataset) {
        if (!dataset?.data) {
            return;
        }
        const green = AbstractHistoryChart.getColors("rgb(51,102,0)", this.chartType);
        const red = AbstractHistoryChart.getColors("rgb(200,30,30)", this.chartType);
        const data = dataset.data as (number | null)[];
        dataset.backgroundColor = data.map(value => value != null && value < 0 ? red.backgroundColor : green.backgroundColor);
        dataset.borderColor = data.map(value => value != null && value < 0 ? red.borderColor : green.borderColor);
    }
}
