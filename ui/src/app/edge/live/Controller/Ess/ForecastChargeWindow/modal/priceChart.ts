// @ts-strict-ignore
import { Component, Input } from "@angular/core";
import { TranslateService } from "@ngx-translate/core";
import { AbstractHistoryChart } from "src/app/edge/history/abstracthistorychart";
import { DEFAULT_TIME_CHART_OPTIONS } from "src/app/edge/history/shared";
import { AbstractHistoryChart as NewAbstractHistoryChart } from "src/app/shared/components/chart/abstracthistorychart";
import { ComponentJsonApiRequest } from "src/app/shared/jsonrpc/request/componentJsonApiRequest";
import { ChannelAddress, Edge, EdgeConfig, Service, Websocket } from "src/app/shared/shared";
import { ChartAxis, HistoryUtils, Utils, YAxisType } from "src/app/shared/utils/utils";
import { GetDayAheadGridSellPrices } from "../jsonrpc/get-day-ahead-grid-sell-prices";

/**
 * Shows today's day-ahead grid-sell price as a bar chart (values only change
 * every 15 minutes, so a line looks stepped/ugly) - already-elapsed
 * quarter-hours as solid bars, the remaining (already known, not-yet-elapsed)
 * quarter-hours as lighter/translucent bars, in one continuous chart.
 *
 * Deliberately not built on queryHistoricTimeseriesData (like the History-page
 * chart for the same Component) - Rrd4j only ever holds already-elapsed data,
 * so it structurally cannot show the future part of a day-ahead price
 * schedule. Instead this queries the Edge live, via getDayAheadGridSellPrices
 * (custom ComponentJsonApi route on ForecastChargeWindowImpl), which reads
 * directly from the in-memory ENTSO-E provider - same "past + planned future
 * in one chart" idea as Controller.Ess.Time-Of-Use-Tariff's schedule chart
 * (powerSocChart.ts), but without needing to hook into the unrelated
 * EnergyScheduleHandler/EnergyScheduler optimization framework, since there is
 * nothing to optimize here - just an already-known price curve to display.
 */
@Component({
    selector: "forecastChargeWindowPriceChart",
    templateUrl: "../../../../../history/abstracthistorychart.html",
    standalone: false,
})
export class PriceChartComponent extends AbstractHistoryChart {

    @Input({ required: true }) public override edge!: Edge;
    @Input({ required: true }) public component!: EdgeConfig.Component;

    constructor(
        protected override service: Service,
        protected override translate: TranslateService,
        private websocket: Websocket,
    ) {
        super("forecastChargeWindow-priceChart", service, translate);
    }

    public ngOnInit() {
        this.service.startSpinner(this.spinnerId);
        this.updateChart();
    }

    protected getChartHeight(): number {
        return this.service.isSmartphoneResolution ? window.innerHeight / 3 : window.innerHeight / 4;
    }

    protected getChannelAddresses(): Promise<ChannelAddress[]> {
        return new Promise(() => { []; });
    }

    protected setLabel() {
        // NOTE: this.createDefaultChartOptions() is broken - it deepCopy()s the
        // DEFAULT_TIME_CHART_OPTIONS function reference itself (deepCopy() only
        // handles plain objects, not functions), instead of its return value,
        // leaving this.options without a `scales` property. Call it directly.
        this.options = Utils.deepCopy(DEFAULT_TIME_CHART_OPTIONS());
    }

    protected updateChart() {
        this.loading = true;
        this.errorResponse = null;
        this.service.startSpinner(this.spinnerId);

        this.edge.sendRequest<GetDayAheadGridSellPrices.Response>(
            this.websocket,
            new ComponentJsonApiRequest({ componentId: this.component.id, payload: new GetDayAheadGridSellPrices.Request({}) }),
        ).then(rawResponse => {
            const response = new GetDayAheadGridSellPrices.Response(rawResponse.id, rawResponse.result);
            this.buildChart(response.prices);
        }).catch(reason => {
            console.error(reason);
            this.initializeChart();
        }).finally(() => {
            this.loading = false;
            this.stopSpinner();
        });
    }

    private buildChart(points: GetDayAheadGridSellPrices.PricePoint[]) {
        this.setLabel();
        this.labels = points.map(point => point.time);

        // EUR/MWh (backend unit) -> ct/kWh, matching what YAxisType.CURRENCY assumes
        const priceInCentPerKwh = points.map(point => point.price / 10);

        // Split into two mutually-exclusive (per index, one is always null) datasets
        // so past/future can be styled differently - nulls contribute nothing to a
        // stacked bar, so together they render as one continuous bar series.
        const now = Date.now();
        const pastData: (number | null)[] = [];
        const futureData: (number | null)[] = [];
        // Per-bar colors (Chart.js accepts one color per data point, not just one
        // for the whole dataset) - red for negative prices, the usual green
        // otherwise, at the same opacity already used to distinguish past/future.
        const pastBackgroundColor: string[] = [];
        const pastBorderColor: string[] = [];
        const futureBackgroundColor: string[] = [];
        const futureBorderColor: string[] = [];

        points.forEach((point, i) => {
            const negative = point.price < 0;
            // Chart.js indexes a per-point color array by the same index as the
            // data array, so both arrays need one entry per point regardless of
            // which of the two (mutually-exclusive, null-padded) series is
            // actually active at that index - the color for the null slot is
            // simply never drawn.
            if (point.time.getTime() <= now) {
                pastData.push(priceInCentPerKwh[i]);
                futureData.push(null);
            } else {
                pastData.push(null);
                futureData.push(priceInCentPerKwh[i]);
            }
            pastBackgroundColor.push(negative ? "rgba(200,30,30,0.8)" : "rgba(51,102,0,0.8)");
            pastBorderColor.push(negative ? "rgb(200,30,30)" : "rgb(51,102,0)");
            futureBackgroundColor.push(negative ? "rgba(200,30,30,0.35)" : "rgba(51,102,0,0.35)");
            futureBorderColor.push(negative ? "rgba(200,30,30,0.35)" : "rgba(51,102,0,0.35)");
        });

        const label = this.translate.instant("EDGE.INDEX.WIDGETS.FORECAST_CHARGE_WINDOW.PRICE");
        this.datasets = [
            {
                type: "bar",
                label: label,
                data: pastData,
                backgroundColor: pastBackgroundColor,
                borderColor: pastBorderColor,
                stack: "price",
                // Required for getYAxisOptions()'s dynamicScale min/max calculation
                // (matches datasets to a yAxis by this field) and for Chart.js
                // itself to plot against the 'left' ct-scale below, rather than an
                // implicit default axis - normally set automatically by fillChart()
                // for History-page charts, but this one builds datasets manually.
                yAxisID: ChartAxis.LEFT,
            },
            {
                type: "bar",
                label: label,
                data: futureData,
                backgroundColor: futureBackgroundColor,
                borderColor: futureBorderColor,
                stack: "price",
                yAxisID: ChartAxis.LEFT,
            },
        ];

        const leftYAxis: HistoryUtils.yAxes = {
            position: "left",
            unit: YAxisType.CURRENCY,
            yAxisId: ChartAxis.LEFT,
            scale: { dynamicScale: true },
        };
        this.options = NewAbstractHistoryChart.getYAxisOptions(this.options, leftYAxis, this.translate, "bar",
            this.datasets, true);

        this.options.scales.x["ticks"] = { source: "auto", autoSkip: false };
        this.options.scales.x.ticks.callback = (value: number) => {
            const date = new Date(value);
            return date.getMinutes() === 0 ? date.getHours() + ":00" : "";
        };
        this.options.plugins.legend.display = false;
        this.options["animation"] = false;

        // Thin vertical line marking 'now' at the moment the dialog was opened -
        // deliberately not updated afterwards while the dialog stays open, so
        // reuses the same 'now' already computed above for the past/future bar
        // split, rather than a fresh timestamp.
        this.options.plugins["annotation"] = {
            annotations: {
                nowLine: {
                    type: "line",
                    xMin: now,
                    xMax: now,
                    borderColor: "red",
                    borderWidth: 1,
                },
            },
        };
    }
}
