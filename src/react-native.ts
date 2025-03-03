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
  UnifiedViewingKey,
  UnifiedSpendingKey
} from './types'
export * from './types'

const { VerusLightClient } = NativeModules

type Callback = (...args: any[]) => any

export const Tools = {
  deriveViewingKey: async (
    seedBytesHex: string,
    network: Network = 'VRSC'
  ): Promise<UnifiedViewingKey> => {
    console.log("deriveShieldedViewingkey called!")
    const result = await VerusLightClient.deriveViewingKey(seedBytesHex, network)
    return result
  },
  deriveSaplingSpendingKey: async (
    seedBytesHex: string,
    network: Network = 'VRSC'
  ): Promise<UnifiedSpendingKey> => {
    console.log("deriveShieldedSpendkey called!")
    const result = await VerusLightClient.deriveSaplingSpendingKey(seedBytesHex, network)
    return result
  },
  deriveShieldedAddress: async (
    seedBytesHex: string,
    network: Network = 'VRSC'
  ): Promise<String> => {
    console.log("deriveShieldedAddress called!")
    const result = await VerusLightClient.deriveShieldedAddress(seedBytesHex, network)
    return result
  },
  deriveShieldedAddressFromSeed: async (
    seedBytesHex: string,
    network: Network = 'VRSC'
  ): Promise<String> => {
    console.log("deriveShieldedAddressFromSeed called!")
    const result = await VerusLightClient.deriveShieldedAddressFromSeed(seedBytesHex, network)
    return result
  },
  getBirthdayHeight: async (host: string, port: number): Promise<number> => {
    const result = await VerusLightClient.getBirthdayHeight(host, port)
    return result
  },
  isValidAddress: async (
    address: string,
    network: Network = 'VRSC'
  ): Promise<boolean> => {
    const result = await VerusLightClient.isValidAddress(address, network)
    return result
  }
}

export class Synchronizer {
  eventEmitter: NativeEventEmitter
  subscriptions: EventSubscription[]
  alias: string
  network: string

  constructor(alias: string, network: string) {
    this.eventEmitter = new NativeEventEmitter(/*VerusLightClient*/)
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
    console.warn("within initialize func, before await")
    console.warn("mnemonicSeed: " + initializerConfig.mnemonicSeed);
    console.warn("wif: " + initializerConfig.wif);
    console.warn("birthday: " + initializerConfig.birthdayHeight);
    console.warn("alias: " + initializerConfig.alias);
    console.warn("networkName: " + initializerConfig.networkName);
    console.warn("host: " + initializerConfig.defaultHost);
    console.warn("port: " + initializerConfig.defaultPort);
    console.warn("newWallet: " + initializerConfig.newWallet);

    await VerusLightClient.initialize(
      initializerConfig.mnemonicSeed,
      initializerConfig.wif,
      initializerConfig.birthdayHeight,
      initializerConfig.alias,
      initializerConfig.networkName,
      initializerConfig.defaultHost,
      initializerConfig.defaultPort,
      initializerConfig.newWallet
    )
    console.warn("within initialize func, after await")
  }

  async deriveUnifiedAddress(): Promise<Addresses> {
    const result = await VerusLightClient.deriveUnifiedAddress(this.alias)
    return result
  }

  async deriveSaplingAddress(): Promise<string> {
    const result = await VerusLightClient.deriveSaplingAddress(this.alias)
    return result
  }

  async deriveShieledAddress(): Promise<Addresses> {
    const result = await VerusLightClient.deriveShieldedAddress(this.alias)
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
  console.warn("before constructor in makeSynchronizer")
  const synchronizer = new Synchronizer(
    initializerConfig.alias,
    initializerConfig.networkName
  )
  console.warn("before synchronizer.initialize()")
  await synchronizer.initialize(initializerConfig)
  console.warn("before return synchronizer")
  return synchronizer
}

export const getSaplingAddress = async (
  alias: string,
  networkName: string
): Promise<String> => {
  console.warn("before calling Synchronizer.getSaplingAddress")
  const synchronizer = new Synchronizer(
    alias,
    networkName
  )
  const address = await synchronizer.deriveSaplingAddress()
  console.warn("before return saplingAddress: " + address)
  return address
}
