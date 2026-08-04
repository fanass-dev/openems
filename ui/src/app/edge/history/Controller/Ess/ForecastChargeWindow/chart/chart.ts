// @ts-strict-ignore
import { ChangeDetectorRef, Component } from "@angular/core";
import { ActivatedRoute } from "@angular/router";
import { TranslateService } from "@ngx-translate/core";
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
        this.chartType = "line";

        return {
            input: [
                {
                    name: "GridSellPrice",
                    powerChannel: ChannelAddress.fromString(this.component.id + "/CurrentGridSellPrice"),
                },
            ],
            output: (data: HistoryUtils.ChannelData) => {
                return [{
                    name: this.translate.instant("EDGE.INDEX.WIDGETS.FORECAST_CHARGE_WINDOW.PRICE"),
                    // fillChart() divides every channel value by 1000 (built for Watt -> kW),
                    // which does not apply to a currency channel - undo it here.
                    converter: () => data["GridSellPrice"]?.map(value => value == null ? null : value * 1000),
                    color: "rgb(51,102,0)",
                    custom: {
                        type: "line",
                        formatNumber: ChartConstants.NumberFormat.TWO,
                    },
                }];
            },
            tooltip: {
                formatNumber: ChartConstants.NumberFormat.TWO,
            },
            yAxes: [{
                unit: YAxisType.CURRENCY,
                position: "left",
                yAxisId: ChartAxis.LEFT,
                scale: {
                    dynamicScale: true,
                },
            }],
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
}
