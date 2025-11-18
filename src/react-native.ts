import { add } from 'biggystring'
import {
  EventSubscription,
  NativeEventEmitter,
  NativeModules
} from 'react-native'

import {
  Addresses,
  InfoResponse,
  InitializerConfig,
  Network,
  PrivateBalanceResponse,
  PrivateTransactionsResponse,
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

let synchronizerInstance: Synchronizer | null = null;

export const Tools = {
  bech32Decode: async (
    bech32Key: string
  ): Promise<String> => {
    const result = await VerusLightClient.bech32Decode(bech32Key)
    return result
  },
  deterministicSeedBytes: async (
    seed: string
  ): Promise<String> => {
    const result = await VerusLightClient.deterministicSeedBytes(seed);
    return result
  },
  deriveViewingKey: async (
    extsk?: string,
    seedBytesHex?: string,
    network: Network = 'VRSC'
  ): Promise<UnifiedViewingKey> => {
    const result = await VerusLightClient.deriveViewingKey(extsk, seedBytesHex, network)
    return result
  },
  deriveSaplingSpendingKey: async (
    seedBytesHex: string,
    network: Network = 'VRSC'
  ): Promise<String> => {
    const result = await VerusLightClient.deriveSaplingSpendingKey(seedBytesHex, network)
    return result
  },
  deriveUnifiedSpendingKey: async (
    seedBytesHex: string,
    network: Network = 'VRSC'
  ): Promise<UnifiedSpendingKey> => {
    const result = await VerusLightClient.deriveUnifiedSpendingKey(seedBytesHex, network)
    return result
  },
  deriveShieldedAddress: async (
    extsk?: string,
    seedBytesHex?: string,
    network: Network = 'VRSC'
  ): Promise<String> => {
    const result = await VerusLightClient.deriveShieldedAddress(extsk, seedBytesHex, network)
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
    this.eventEmitter = new NativeEventEmitter(VerusLightClient)
    this.subscriptions = []
    this.alias = alias
    this.network = network
  }

  async stop(): Promise<string> {
    this.unsubscribe()
    const result = await VerusLightClient.stop(this.alias)
    if (synchronizerInstance && synchronizerInstance.alias === this.alias) {
      synchronizerInstance = null
    }
    return result
  }

  async stopAndDeleteWallet(): Promise<boolean> {
    this.unsubscribe()
    const result = await VerusLightClient.stopAndDeleteWallet(this.alias)
    if (synchronizerInstance && synchronizerInstance.alias === this.alias) {
      synchronizerInstance = null
    }
    return result
  }

  async initialize(initializerConfig: InitializerConfig): Promise<void> {
    if (
      this.alias !== initializerConfig.alias ||
      this.network !== initializerConfig.networkName
    ) {
      this.setIdentity(initializerConfig.alias, initializerConfig.networkName)
    }

    await VerusLightClient.initialize(
      initializerConfig.mnemonicSeed,
      initializerConfig.wif,
      initializerConfig.extsk,
      initializerConfig.birthdayHeight,
      initializerConfig.alias,
      initializerConfig.networkName,
      initializerConfig.defaultHost,
      initializerConfig.defaultPort,
      initializerConfig.newWallet
    )
  }

  async getInfo(): Promise<InfoResponse> {
    const result = await VerusLightClient.getInfo(this.alias)
    return result
  }

  async getPrivateBalance(): Promise<PrivateBalanceResponse> {
    const result = await VerusLightClient.getPrivateBalance(this.alias)
    return result
  }

  async getPrivateTransactions(): Promise<PrivateTransactionsResponse> {
    const result = await VerusLightClient.getPrivateTransactions(this.alias)
    return result
  }

  async getUnifiedAddress(): Promise<Addresses> {
    const result = await VerusLightClient.getUnifiedAddress(this.alias)
    return result
  }

  async getSaplingAddress(): Promise<string> {
    const result = await VerusLightClient.getSaplingAddress(this.alias)
    return result
  }

  async getLatestNetworkHeight(): Promise<number> {
    const result = await VerusLightClient.getLatestNetworkHeight(this.alias)
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
      spendInfo.extsk,
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

  setIdentity(alias: string, network: string) {
    this.unsubscribe()
    this.alias = alias
    this.network = network
  }

  unsubscribe(): void {
    this.subscriptions.forEach(subscription => {
      subscription.remove()
    })
  }
}

export const getSynchronizerInstance = (
  alias: string,
  network: string
): Synchronizer => {
  if (!synchronizerInstance) {
    synchronizerInstance = new Synchronizer(alias, network)
  } else if (
    synchronizerInstance.alias !== alias ||
    synchronizerInstance.network !== network
  ) {
    synchronizerInstance.setIdentity(alias, network)
  }

  return synchronizerInstance
}

export const makeSynchronizer = async (
  initializerConfig: InitializerConfig
): Promise<Synchronizer> => {
  const sync = getSynchronizerInstance(
    initializerConfig.alias,
    initializerConfig.networkName
  )
  await sync.initialize(initializerConfig)
  return sync
}

