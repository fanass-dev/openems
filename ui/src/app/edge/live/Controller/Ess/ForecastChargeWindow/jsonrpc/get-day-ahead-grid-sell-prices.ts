import { JsonrpcRequest, JsonrpcResponse } from "src/app/shared/jsonrpc/base";
import { EmptyObj } from "src/app/shared/type/utility";

export namespace GetDayAheadGridSellPrices {

    export interface PricePoint {
        time: Date;
        /** EUR/MWh, as delivered by the backend - use Utils.CONVERT_PRICE_TO_CENT_PER_KWH to display. */
        price: number;
    }

    export const METHOD: string = "getDayAheadGridSellPrices";

    export class Request extends JsonrpcRequest {

        public constructor(public override readonly params: EmptyObj) {
            super(GetDayAheadGridSellPrices.METHOD, params);
        }
    }

    export class Response extends JsonrpcResponse {

        public readonly prices: PricePoint[];

        public constructor(
            public override readonly id: string,
            public readonly result: { prices: Array<{ time: string, price: number }>, unit: string },
        ) {
            super(id);
            this.prices = result.prices.map(p => ({
                time: new Date(p.time),
                price: p.price,
            }));
        }
    }
}
