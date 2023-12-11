import { formatNumber } from '@angular/common';
import { TranslateService } from '@ngx-translate/core';
import * as Chart from 'chart.js';
import { JsonrpcResponseError } from 'src/app/shared/jsonrpc/base';
import { QueryHistoricTimeseriesDataRequest } from "src/app/shared/jsonrpc/request/queryHistoricTimeseriesDataRequest";
import { QueryHistoricTimeseriesEnergyPerPeriodRequest } from 'src/app/shared/jsonrpc/request/queryHistoricTimeseriesEnergyPerPeriodRequest';
import { QueryHistoricTimeseriesDataResponse } from "src/app/shared/jsonrpc/response/queryHistoricTimeseriesDataResponse";
import { QueryHistoricTimeseriesEnergyPerPeriodResponse } from 'src/app/shared/jsonrpc/response/queryHistoricTimeseriesEnergyPerPeriodResponse';
import { ChartAxis, HistoryUtils, YAxisTitle } from 'src/app/shared/service/utils';
import { ChannelAddress, Edge, EdgeConfig, Service, Utils } from "src/app/shared/shared";
import { DateUtils } from 'src/app/shared/utils/dateutils/dateutils';
import { AbstractHistoryChart as NewAbstractHistoryChart } from 'src/app/shared/genericComponents/chart/abstracthistorychart';

import { calculateResolution, DEFAULT_TIME_CHART_OPTIONS, EMPTY_DATASET, Resolution, setLabelVisible, Unit } from './shared';
import { ColorUtils } from 'src/app/shared/utils/color/color.utils';

// NOTE: Auto-refresh of widgets is currently disabled to reduce server load
export abstract class AbstractHistoryChart {

    public loading: boolean = true;
    protected edge: Edge | null = null;
    protected errorResponse: JsonrpcResponseError | null = null;

    //observable is used to fetch new chart data every 10 minutes
    // private refreshChartData = interval(600000);

    //observable is used to refresh chart height dependend on the window size
    // private refreshChartHeight = fromEvent(window, 'resize', null, null);

    // private ngUnsubscribe: Subject<void> = new Subject<void>();

    public labels: Date[] = [];
    public datasets: Chart.ChartDataset[] = HistoryUtils.createEmptyDataset(this.translate);
    public options: Chart.ChartOptions | null = DEFAULT_TIME_CHART_OPTIONS;
    public colors = [];
    // prevents subscribing more than once
    protected hasSubscribed: boolean = false;

    /** @deprecated*/
    protected unit: YAxisTitle = YAxisTitle.ENERGY;
    /** @deprecated*/
    protected formatNumber: string = '1.0-2';

    // Colors for Phase 1-3
    protected phase1Color = {
        backgroundColor: 'rgba(255,127,80,0.05)',
        borderColor: 'rgba(255,127,80,1)',
    };
    protected phase2Color = {
        backgroundColor: 'rgba(0,0,255,0.1)',
        borderColor: 'rgba(0,0,255,1)',
    };
    protected phase3Color = {
        backgroundColor: 'rgba(128,128,0,0.1)',
        borderColor: 'rgba(128,128,0,1)',
    };

    constructor(
        public readonly spinnerId: string,
        protected service: Service,
        protected translate: TranslateService,
    ) {
    }

    /**
     * Gets the ChannelAddresses that should be queried.
     * 
     * @param edge the current Edge
     * @param config the EdgeConfig
     */
    protected abstract getChannelAddresses(edge: Edge, config: EdgeConfig): Promise<ChannelAddress[]>;


    /**
     * Sends the Historic Timeseries Data Query and makes sure the result is not empty.
     * 
     * @param fromDate the From-Date
     * @param toDate   the To-Date
     * @param edge     the current Edge
     * @param ws       the websocket
     */
    protected queryHistoricTimeseriesData(fromDate: Date, toDate: Date, res?: Resolution): Promise<QueryHistoricTimeseriesDataResponse> {

        // Take custom resolution if passed
        let resolution = res ?? calculateResolution(this.service, fromDate, toDate).resolution;

        this.errorResponse = null;

        let result: Promise<QueryHistoricTimeseriesDataResponse> = new Promise<QueryHistoricTimeseriesDataResponse>((resolve, reject) => {
            this.service.getCurrentEdge().then(edge => {
                this.service.getConfig().then(config => {
                    this.setLabel(config);
                    this.getChannelAddresses(edge, config).then(channelAddresses => {

                        let request = new QueryHistoricTimeseriesDataRequest(DateUtils.maxDate(fromDate, this.edge?.firstSetupProtocol), toDate, channelAddresses, resolution);
                        edge.sendRequest(this.service.websocket, request).then(response => {
                            resolve(response as QueryHistoricTimeseriesDataResponse);
                        }).catch(error => {
                            this.errorResponse = error;
                            resolve(new QueryHistoricTimeseriesDataResponse(error.id, {
                                timestamps: [null], data: { null: null },
                            }));
                        });
                    });
                });
            });
        }).then((response) => {
            if (Utils.isDataEmpty(response)) {
                this.loading = false;
                this.service.stopSpinner(this.spinnerId);
                this.initializeChart();
            }
            return response;
        });

        return result;
    }

    /**
     * Sends the Historic Timeseries Energy per Period Query and makes sure the result is not empty.
     * 
     * @param fromDate the From-Date
     * @param toDate   the To-Date
     * @param channelAddresses       the Channel-Addresses
     */
    protected queryHistoricTimeseriesEnergyPerPeriod(fromDate: Date, toDate: Date, channelAddresses: ChannelAddress[]): Promise<QueryHistoricTimeseriesEnergyPerPeriodResponse> {

        // TODO should be removed, edge delivers too much data 
        let resolution = calculateResolution(this.service, fromDate, toDate).resolution;

        this.errorResponse = null;

        let response: Promise<QueryHistoricTimeseriesEnergyPerPeriodResponse> = new Promise<QueryHistoricTimeseriesEnergyPerPeriodResponse>((resolve, reject) => {
            this.service.getCurrentEdge().then(edge => {
                this.service.getConfig().then(config => {
                    edge.sendRequest(this.service.websocket, new QueryHistoricTimeseriesEnergyPerPeriodRequest(DateUtils.maxDate(fromDate, this.edge?.firstSetupProtocol), toDate, channelAddresses, resolution)).then(response => {
                        resolve(response as QueryHistoricTimeseriesEnergyPerPeriodResponse ?? new QueryHistoricTimeseriesEnergyPerPeriodResponse(response.id, {
                            timestamps: [null], data: { null: null },
                        }));
                    }).catch((response) => {
                        this.errorResponse = response;
                        resolve(new QueryHistoricTimeseriesDataResponse("0", {
                            timestamps: [null], data: { null: null },
                        }));
                    });
                });
            });
        }).then((response) => {
            if (Utils.isDataEmpty(response)) {
                this.loading = false;
                this.service.stopSpinner(this.spinnerId);
                this.initializeChart();
            }
            return response;
        });
        return response;
    }

    /**
  * Generates a Tooltip Title string from a 'fromDate' and 'toDate'.
  * 
  * @param fromDate the From-Date
  * @param toDate the To-Date 
  * @param date Date from TooltipItem
  * @returns period for Tooltip Header
  */
    protected static toTooltipTitle(fromDate: Date, toDate: Date, date: Date, service: Service): string {
        let unit = calculateResolution(service, fromDate, toDate).resolution.unit;
        if (unit == Unit.MONTHS) {
            // Yearly view
            return date.toLocaleDateString('default', { month: 'long' });

        } else if (unit == Unit.DAYS) {
            // Monthly view
            return date.toLocaleDateString('default', { day: '2-digit', month: 'long' });

        } else {
            // Default
            return date.toLocaleString('default', { day: '2-digit', month: '2-digit', year: '2-digit' }) + ' ' + date.toLocaleTimeString('default', { hour12: false, hour: '2-digit', minute: '2-digit' });
        }
    }

    /**
     * Creates the default Chart options
     * 
     * @Future TODO change into static method and pass the historyPeriods value
     * 
     * @returns the ChartOptions
     */
    protected createDefaultChartOptions(): Chart.ChartOptions {
        let options = <Chart.ChartOptions>Utils.deepCopy(DEFAULT_TIME_CHART_OPTIONS);
        return options;
    }

    /**
     * checks if chart is allowed to be refreshed
     * 
     */
    // protected checkAllowanceChartRefresh(): boolean {
    //     let currentDate = new Date();
    //     let allowRefresh: boolean = false;
    //     if (isAfter(this.service.historyPeriod.value.to, currentDate) || currentDate.getDate() == this.service.historyPeriod.from.getDate()) {
    //         allowRefresh = true;
    //     } else {
    //         allowRefresh = false;
    //     }
    //     return allowRefresh;
    // }

    /**
     * Subscribe to Chart Refresh if allowed
     * Unsubscribe to Chart Refresh if necessary
     */
    protected autoSubscribeChartRefresh() {
        // XXX disabled to reduce server load

        // if (this.hasSubscribed == false && this.checkAllowanceChartRefresh() == true) {
        //     if (this.ngUnsubscribe.isStopped == true) {
        //         this.ngUnsubscribe.isStopped = false;
        //     }
        //     this.refreshChartData.pipe(takeUntil(this.ngUnsubscribe)).subscribe(() => {
        //         this.updateChart();
        //     })
        //     this.refreshChartHeight.pipe(takeUntil(this.ngUnsubscribe), debounceTime(200), delay(100)).subscribe(() => {
        //         this.getChartHeight();
        //     });
        //     this.hasSubscribed = true;
        // } else if (this.hasSubscribed == true && this.checkAllowanceChartRefresh() == false) {
        //     this.unsubscribeChartRefresh();
        // }
    }

    /**
     * Unsubscribes to 10 minute Interval Observable and Window Resize Observable
     */
    protected unsubscribeChartRefresh() {
        // XXX disabled to reduce server load

        // this.hasSubscribed = false;
        // this.ngUnsubscribe.next();
        // this.ngUnsubscribe.complete();
    }

    /**
     * Sets the Label of Chart
     */
    protected abstract setLabel(config: EdgeConfig)

    /**
     * Updates and Fills the Chart
     */
    protected abstract updateChart()

    /**
     * Initializes empty chart on error
     * @param spinnerSelector to stop spinner
     */
    protected initializeChart() {
        EMPTY_DATASET[0].label = this.translate.instant('Edge.History.noData');
        this.datasets = EMPTY_DATASET;
        this.labels = [];
        this.loading = false;
        this.stopSpinner();
    }

    /**
     * Sets Chart Height
     */
    protected abstract getChartHeight();

    /**
     * Start NGX-Spinner
     * 
     * Spinner will appear inside html tag only
     * 
     * @example <ngx-spinner name="YOURSELECTOR"></ngx-spinner>
     * 
     * @param selector selector for specific spinner
     */
    public startSpinner() {
        this.service.startSpinner(this.spinnerId);
    }

    /**
     * Stop NGX-Spinner
     * @param selector selector for specific spinner
     */
    public stopSpinner() {
        this.service.stopSpinner(this.spinnerId);
    }

    /**
     * @deprecated used for not refactored charts, 
     */
    public setOptions(options: Chart.ChartOptions) {

        const locale = this.service.translate.currentLang;
        const yAxis: HistoryUtils.yAxes = { position: 'left', unit: this.unit, yAxisId: ChartAxis.LEFT }
        const unit = this.unit;
        const colors = this.colors;

        this.datasets = this.datasets.map(el => {
            el['yAxisID'] = ChartAxis.LEFT;
            return el;
        })

        /** Hide default displayed yAxis */
        options.scales['y'] = {
            display: false
        }

        // Overwrite TooltipsTitle
        options.plugins.tooltip.callbacks.title = (tooltipItems: Chart.TooltipItem<any>[]): string => {
            let date = new Date(tooltipItems[0].label);
            return AbstractHistoryChart.toTooltipTitle(this.service.historyPeriod.value.from, this.service.historyPeriod.value.to, date, this.service);
        };

        options.plugins.tooltip.callbacks.label = function (tooltipItem: Chart.TooltipItem<any>) {
            let label = tooltipItem.dataset.label;
            let value = tooltipItem.dataset.data[tooltipItem.dataIndex];
            return NewAbstractHistoryChart.getTooltipsLabelName(label, unit, value)
        };

        options.plugins.tooltip.callbacks.labelColor = (item: Chart.TooltipItem<any>) => {
            const color = colors[item.datasetIndex];
            return {
                borderColor: color.borderColor,
                backgroundColor: color.backgroundColor,
            };
        };

        options.plugins.legend.labels.generateLabels = function (chart: Chart.Chart) {
            let chartLegendLabelItems: Chart.LegendItem[] = [];
            chart.data.datasets.forEach((dataset, index) => {

                const color = colors[index];
                chartLegendLabelItems.push({
                    text: dataset.label,
                    datasetIndex: index,
                    fillStyle: color.backgroundColor,
                    lineWidth: 2,
                    strokeStyle: color.borderColor,
                });
            });
            return chartLegendLabelItems;
        };

        // Remove duplicates from legend, if legendItem with two or more occurrences in legend, use one legendItem to trigger them both
        options.plugins.legend.onClick = function (event: Chart.ChartEvent, legendItem: Chart.LegendItem, legend) {
            let chart: Chart.Chart = this.chart;

            let legendItems = chart.data.datasets.reduce((arr, ds, i) => {
                if (ds.label == legendItem.text) {
                    arr.push({ label: ds.label, index: i })
                }
                return arr;
            }, []);

            legendItems.forEach(item => {
                // original.call(this, event, legendItem1);
                setLabelVisible(item.label, !chart.isDatasetVisible(legendItem.datasetIndex));
                var meta = chart.getDatasetMeta(item.index);
                // See controller.isDatasetVisible comment
                meta.hidden = meta.hidden === null ? !chart.data.datasets[item.index].hidden : null;
            });

            // We hid a dataset ... rerender the chart
            chart.update();
        };


        if (this.datasets.length === this.colors.length) {
            this.datasets.map((el, index) => {
                el.backgroundColor = this.colors[index].backgroundColor;
                el.borderColor = this.colors[index].borderColor;
            })
        }

        options = NewAbstractHistoryChart.getYAxisOptions(options, yAxis, this.translate, 'line', locale);

        const timeFormat = calculateResolution(this.service, this.service.historyPeriod.value.from, this.service.historyPeriod.value.to).timeFormat;
        options.scales.x['time'].unit = timeFormat;
        switch (timeFormat) {
            case 'hour':
                options.scales.x.ticks['source'] = 'auto';//labels,auto
                options.scales.x.ticks.maxTicksLimit = 31;
                options.scales.x['bounds'] = 'ticks';
                break;
            case 'day':
            case 'month':
                options.scales.x.ticks['source'] = 'data';
                break;
        }
        this.options = options;
    }
}
