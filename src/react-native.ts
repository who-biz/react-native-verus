import { add } from 'biggystring'
import {
  EventSubscription,
  NativeEventEmitter,
  NativeModules
} from 'react-native'

import {
  Addresses,
  InitializerConfig,
  Network,
  ShieldFundsInfo,
  SpendFailure,
  SpendInfo,
  SpendSuccess,
  SynchronizerCallbacks,
  Transaction,
  UnifiedViewingKey
} from './types'
export * from './types'

const { VerusLightClient } = NativeModules

type Callback = (...args: any[]) => any

export const Tools = {
  deriveViewingKey: async (
    seedBytesHex: string,
    network: Network
  ): Promise<UnifiedViewingKey> => {
    const result = await VerusLightClient.deriveViewingKey(seedBytesHex, network)
    return result
  },
  getBirthdayHeight: async (host: string, port: number): Promise<number> => {
    const result = await VerusLightClient.getBirthdayHeight(host, port)
    return result
  },
  isValidAddress: async (
    address: string,
    network: Network = 'mainnet'
  ): Promise<boolean> => {
    const result = await VerusLightClient.isValidAddress(address, network)
    return result
  }
}

export class Synchronizer {
  eventEmitter: NativeEventEmitter
  subscriptions: EventSubscription[]
  alias: string
  network: Network

  constructor(alias: string, network: Network) {
    this.eventEmitter = new NativeEventEmitter(VerusLightClient)
    this.subscriptions = []
    this.alias = alias
    this.network = network
  }

  async stop(): Promise<string> {
    this.unsubscribe()
    const result = await VerusLightClient.stop(this.alias)
    return result
  }

  async initialize(initializerConfig: InitializerConfig): Promise<void> {
    await VerusLightClient.initialize(
      initializerConfig.mnemonicSeed,
      initializerConfig.birthdayHeight,
      initializerConfig.alias,
      initializerConfig.networkName,
      initializerConfig.defaultHost,
      initializerConfig.defaultPort,
      initializerConfig.newWallet
    )
  }

  async deriveUnifiedAddress(): Promise<Addresses> {
    const result = await VerusLightClient.deriveUnifiedAddress(this.alias)
    return result
  }

  async getLatestNetworkHeight(alias: string): Promise<number> {
    const result = await VerusLightClient.getLatestNetworkHeight(alias)
    return result
  }

  async rescan(): Promise<void> {
    await VerusLightClient.rescan(this.alias)
  }

  async sendToAddress(
    spendInfo: SpendInfo
  ): Promise<SpendSuccess | SpendFailure> {
    const result = await VerusLightClient.sendToAddress(
      this.alias,
      spendInfo.zatoshi,
      spendInfo.toAddress,
      spendInfo.memo,
      spendInfo.mnemonicSeed
    )
    return result
  }

  async shieldFunds(shieldFundsInfo: ShieldFundsInfo): Promise<Transaction> {
    const result = await VerusLightClient.shieldFunds(
      this.alias,
      shieldFundsInfo.seed,
      shieldFundsInfo.memo,
      shieldFundsInfo.threshold
    )
    return result
  }

  // Events

  subscribe({
    onBalanceChanged,
    onStatusChanged,
    onTransactionsChanged,
    onUpdate
  }: SynchronizerCallbacks): void {
    this.setListener('BalanceEvent', event => {
      const {
        transparentAvailableZatoshi,
        transparentTotalZatoshi,
        saplingAvailableZatoshi,
        saplingTotalZatoshi
      } = event

      event.availableZatoshi = add(
        transparentAvailableZatoshi,
        saplingAvailableZatoshi
      )
      event.totalZatoshi = add(transparentTotalZatoshi, saplingTotalZatoshi)
      onBalanceChanged(event)
    })
    this.setListener('StatusEvent', onStatusChanged)
    this.setListener('TransactionEvent', onTransactionsChanged)
    this.setListener('UpdateEvent', onUpdate)
  }

  private setListener<T>(
    eventName: string,
    callback: Callback = (t: any) => null
  ): void {
    this.subscriptions.push(
      this.eventEmitter.addListener(eventName, arg =>
        arg.alias === this.alias ? callback(arg) : null
      )
    )
  }

  unsubscribe(): void {
    this.subscriptions.forEach(subscription => {
      subscription.remove()
    })
  }
}

export const makeSynchronizer = async (
  initializerConfig: InitializerConfig
): Promise<Synchronizer> => {
  const synchronizer = new Synchronizer(
    initializerConfig.alias,
    initializerConfig.networkName
  )
  await synchronizer.initialize(initializerConfig)
  return synchronizer
}
