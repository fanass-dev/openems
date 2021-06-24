import { Component } from '@angular/core';
import { async, waitForAsync } from '@angular/core/testing';
import { BehaviorSubject } from 'rxjs';
import { ChannelAddress, CurrentData, Utils } from 'src/app/shared/shared';
import { AbstractFlatWidget } from '../flat/abstract-flat-widget';
import { Controller_Ess_FixActivePower_Modal } from './modal/modal.component';

@Component({
  selector: 'Controller_Ess_FixActivePower',
  templateUrl: './Controller_Ess_FixActivePower.html'
})
export class Controller_Ess_FixActivePower extends AbstractFlatWidget {

  private static PROPERTY_POWER: string = "_PropertyPower";

  public chargeState: string;
  public chargeStateValue: BehaviorSubject<number> = new BehaviorSubject(0);

  public readonly CONVERT_WATT_TO_KILOWATT = Utils.CONVERT_WATT_TO_KILOWATT

  public stateConverter = (value: any): string => {
    if (value === 'MANUAL_ON') {
      return this.translate.instant('General.on');
    } else if (value === 'MANUAL_OFF') {
      return this.translate.instant('General.off');
    } else {
      return '-';
    }
  }

  protected getChannelAddresses(): ChannelAddress[] {
    console.log("component2", this.component)
    let channelAddresses: ChannelAddress[] = [new ChannelAddress(this.componentId, Controller_Ess_FixActivePower.PROPERTY_POWER)]
    return channelAddresses;
  }

  protected onCurrentData(currentData: CurrentData) {
    let channelPower = currentData.thisComponent['_PropertyPower'];
    console.log("test channelPower", channelPower)
    if (channelPower >= 0) {
      this.chargeState = 'General.dischargePower';
      this.chargeStateValue.next(channelPower)
    } else {
      this.chargeState = 'General.chargePower';
      this.chargeStateValue.next(channelPower * -1);
    }
  }

  async presentModal() {
    // if (!this.isInitialized) {
    //   return;
    // }
    const modal = await this.modalController.create({
      component: Controller_Ess_FixActivePower_Modal,
      componentProps: {
        component: this.component,
        edge: this.edge,
        chargeState: this.chargeState,
        chargeStateValue: this.chargeStateValue,
        stateConverter: this.stateConverter,
        componentId: this.componentId
      }
    });

    return await modal.present();
  }
}

